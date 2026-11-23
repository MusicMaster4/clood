mod agent_api;
mod calls;
mod codec;
mod config;
mod openai_live;
mod phone_server;
mod startup;
mod tailscale;

use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};
use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Manager, RunEvent, State, WebviewWindowBuilder, WindowEvent,
};
use tokio::sync::watch;

use crate::calls::{AppState, CallRecord, PairingPayload, RuntimeStatus, StartCallRequest};
use crate::config::{generate_token, CloodConfig};

#[tauri::command]
async fn get_config(state: State<'_, Arc<AppState>>) -> Result<CloodConfig, String> {
    Ok(state.config.read().await.clone())
}

#[tauri::command]
async fn save_config(
    state: State<'_, Arc<AppState>>,
    config: CloodConfig,
) -> Result<CloodConfig, String> {
    state.update_config(config).await
}

#[tauri::command]
async fn get_status(state: State<'_, Arc<AppState>>) -> Result<RuntimeStatus, String> {
    Ok(state.status().await)
}

#[tauri::command]
async fn refresh_tailscale(state: State<'_, Arc<AppState>>) -> Result<RuntimeStatus, String> {
    Ok(state.refresh_tailscale().await)
}

#[tauri::command]
async fn get_pairing_payload(state: State<'_, Arc<AppState>>) -> Result<PairingPayload, String> {
    state.pairing_payload().await
}

#[tauri::command]
async fn rotate_pairing_token(state: State<'_, Arc<AppState>>) -> Result<PairingPayload, String> {
    let mut cfg = state.config.read().await.clone();
    cfg.pairing_token = generate_token();
    cfg.paired_device_name = None;
    state.update_config(cfg).await?;
    // Notify and disconnect currently connected phone so it must re-scan the new QR code
    {
        let mut phone = state.phone.lock().await;
        if let Some(p) = phone.take() {
            let _ = p
                .tx
                .try_send(crate::calls::PhoneOutbound::Text(
                    r#"{"type":"unpaired"}"#.to_string(),
                ));
        }
    }
    state.emit_status().await;
    state.pairing_payload().await
}

#[tauri::command]
async fn start_test_call(state: State<'_, Arc<AppState>>) -> Result<CallRecord, String> {
    let (record, _rx) = state
        .start_call(StartCallRequest {
            context: "This is a quick test call triggered from the Clood desktop app to verify Tailscale audio and GPT Live 1.1.".to_string(),
            opening_line: None,
            system_prompt: None,
            caller_name: None,
            voice: None,
            orb_color: None,
            ring_timeout_sec: Some(45),
            max_duration_sec: Some(300),
            wait: Some(false),
        })
        .await?;
    Ok(record)
}

#[tauri::command]
async fn hangup_active_call(state: State<'_, Arc<AppState>>) -> Result<(), String> {
    let _ = state.hangup_call(None).await;
    Ok(())
}

#[tauri::command]
async fn list_calls(state: State<'_, Arc<AppState>>) -> Result<Vec<CallRecord>, String> {
    Ok(state.list_calls(50).await)
}

#[tauri::command]
async fn delete_call(state: State<'_, Arc<AppState>>, call_id: String) -> Result<(), String> {
    state.delete_call(&call_id)
}

#[derive(Default)]
struct UpdateInstallation(AtomicBool);

#[tauri::command]
fn set_update_installing(installing: bool, state: State<'_, UpdateInstallation>) {
    state.0.store(installing, Ordering::Relaxed);
}

fn show_main_window(app: &AppHandle) -> tauri::Result<()> {
    if let Some(win) = app.get_webview_window("main") {
        win.show()?;
        win.unminimize()?;
        win.set_focus()
    } else {
        let config = app
            .config()
            .app
            .windows
            .iter()
            .find(|config| config.label == "main")
            .expect("main window configuration");
        WebviewWindowBuilder::from_config(app, config)?.build()?;
        Ok(())
    }
}

fn reopen_main_window(app: &AppHandle) {
    if let Err(err) = show_main_window(app) {
        eprintln!("Failed to open Clood window: {err}");
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let _ = rustls::crypto::ring::default_provider().install_default();

    // The tray service runs socket I/O and one call at a time.
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .expect("create Clood async runtime");
    tauri::async_runtime::set(runtime.handle().clone());

    let state = AppState::new();
    let state_for_setup = Arc::clone(&state);

    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            let app = app.clone();
            tauri::async_runtime::spawn(async move {
                let window_app = app.clone();
                let _ = app.run_on_main_thread(move || reopen_main_window(&window_app));
            });
        }))
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .manage(state)
        .manage(UpdateInstallation::default())
        .invoke_handler(tauri::generate_handler![
            get_config,
            save_config,
            get_status,
            refresh_tailscale,
            get_pairing_payload,
            rotate_pairing_token,
            start_test_call,
            hangup_active_call,
            list_calls,
            delete_call,
            set_update_installing,
        ])
        .setup(move |app| {
            let handle = app.handle().clone();
            let state_bg = Arc::clone(&state_for_setup);
            let launched_minimized = std::env::args().any(|a| a == "--minimized");

            if !launched_minimized {
                show_main_window(&handle)?;
            }

            // System tray so Clood can stay resident for agent calls
            let show_i = MenuItem::with_id(app, "show", "Open Clood", true, None::<&str>)?;
            let quit_i = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show_i, &quit_i])?;

            let mut tray_builder = TrayIconBuilder::new()
                .menu(&menu)
                .tooltip("Clood")
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "show" => reopen_main_window(app),
                    "quit" => {
                        app.exit(0);
                    }
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        reopen_main_window(tray.app_handle());
                    }
                });

            if let Some(icon) = app.default_window_icon().cloned() {
                tray_builder = tray_builder.icon(icon);
            }
            tray_builder.build(app)?;

            tauri::async_runtime::spawn(async move {
                state_bg.set_app_handle(handle).await;
                let (restart_tx, restart_rx) = watch::channel(0u64);
                state_bg.set_phone_server_restart_tx(restart_tx).await;

                let phone_state = Arc::clone(&state_bg);
                tokio::spawn(async move {
                    phone_server::supervise_phone_server(phone_state, restart_rx).await;
                });

                let api_state = Arc::clone(&state_bg);
                tokio::spawn(async move {
                    agent_api::run_agent_api(api_state).await;
                });
            });

            Ok(())
        })
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                // The updater reports progress and requests a relaunch through this webview.
                if window
                    .state::<UpdateInstallation>()
                    .0
                    .load(Ordering::Relaxed)
                {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .build(tauri::generate_context!())
        .expect("error while building Clood")
        .run(|_, event| {
            // Closing the last window frees the webview while the tray service stays up.
            if let RunEvent::ExitRequested {
                code: None, api, ..
            } = event
            {
                api.prevent_exit();
            }
        });
}
