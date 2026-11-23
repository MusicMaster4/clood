use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        ConnectInfo, Query, State,
    },
    http::StatusCode,
    response::IntoResponse,
    routing::{get, post},
    Json, Router,
};
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use serde_json::{json, Value};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::{mpsc, watch};
use uuid::Uuid;

use crate::calls::{AppState, PhoneOutbound};
use crate::codec::decode_wire_frame;
use crate::tailscale::{detect_tailscale, is_tailscale_ipv4};

#[derive(Debug, Deserialize)]
struct PairVerifyBody {
    token: String,
    device_name: Option<String>,
}

#[derive(Debug, Deserialize)]
struct WsQuery {
    token: String,
    device: Option<String>,
}

fn constant_time_eq(a: &str, b: &str) -> bool {
    let ab = a.as_bytes();
    let bb = b.as_bytes();
    if ab.len() != bb.len() {
        return false;
    }
    let mut diff = 0u8;
    for (&x, &y) in ab.iter().zip(bb.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

fn is_allowed_peer(addr: SocketAddr) -> bool {
    match addr.ip() {
        IpAddr::V4(v4) => is_tailscale_ipv4(v4) || v4.is_loopback(),
        IpAddr::V6(_) => false,
    }
}

async fn pair_verify(
    State(state): State<Arc<AppState>>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    Json(body): Json<PairVerifyBody>,
) -> impl IntoResponse {
    if !is_allowed_peer(addr) {
        return (
            StatusCode::FORBIDDEN,
            Json(json!({ "ok": false, "error": "Tailscale peers only" })),
        );
    }

    let cfg = state.config.read().await.clone();
    if !constant_time_eq(&body.token, &cfg.pairing_token) {
        return (
            StatusCode::UNAUTHORIZED,
            Json(json!({ "ok": false, "error": "Invalid pairing token" })),
        );
    }

    if let Some(name) = body.device_name.filter(|s| !s.trim().is_empty()) {
        let mut next = cfg.clone();
        next.paired_device_name = Some(name);
        let _ = state.update_config(next).await;
    }

    (
        StatusCode::OK,
        Json(json!({
            "ok": true,
            "agent_name": cfg.agent_name,
            "orb_color": cfg.orb_color,
            "audio_codec": cfg.audio_codec
        })),
    )
}

async fn ws_phone(
    ws: WebSocketUpgrade,
    State(state): State<Arc<AppState>>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    Query(query): Query<WsQuery>,
) -> impl IntoResponse {
    if !is_allowed_peer(addr) {
        return (StatusCode::FORBIDDEN, "Tailscale peers only").into_response();
    }
    let token = state.config.read().await.pairing_token.clone();
    if !constant_time_eq(&query.token, &token) {
        return (StatusCode::UNAUTHORIZED, "Invalid pairing token").into_response();
    }
    let device_name = query
        .device
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| format!("Phone ({})", addr.ip()));

    ws.on_upgrade(move |socket| handle_phone_socket(state, socket, device_name))
}

async fn handle_phone_socket(state: Arc<AppState>, socket: WebSocket, device_name: String) {
    let conn_id = Uuid::new_v4().to_string();
    let (mut ws_tx, mut ws_rx) = socket.split();
    let (out_tx, mut out_rx) = mpsc::channel::<PhoneOutbound>(256);

    state
        .register_phone(conn_id.clone(), device_name, out_tx.clone())
        .await;

    let writer = tokio::spawn(async move {
        while let Some(item) = out_rx.recv().await {
            let msg = match item {
                PhoneOutbound::Text(t) => Message::Text(t.into()),
                PhoneOutbound::Binary(b) => Message::Binary(b.into()),
            };
            if ws_tx.send(msg).await.is_err() {
                break;
            }
        }
    });

    while let Some(Ok(msg)) = ws_rx.next().await {
        match msg {
            Message::Text(txt) => {
                let Ok(val) = serde_json::from_str::<Value>(&txt) else {
                    continue;
                };
                let kind = val.get("type").and_then(|v| v.as_str()).unwrap_or("");
                match kind {
                    "ping" => {
                        let _ = out_tx.try_send(PhoneOutbound::Text(
                            json!({ "type": "pong" }).to_string(),
                        ));
                    }
                    "call_accept" => {
                        if let Some(call_id) = val.get("call_id").and_then(|v| v.as_str()) {
                            state.handle_phone_accept(call_id).await;
                        }
                    }
                    "call_decline" => {
                        if let Some(call_id) = val.get("call_id").and_then(|v| v.as_str()) {
                            state.handle_phone_decline(call_id).await;
                        }
                    }
                    "hangup" => {
                        if let Some(call_id) = val.get("call_id").and_then(|v| v.as_str()) {
                            let reason = val.get("reason").and_then(|v| v.as_str());
                            state.handle_phone_hangup(call_id, reason).await;
                        }
                    }
                    _ => {}
                }
            }
            Message::Binary(bytes) => {
                if let Some((samples, _)) = decode_wire_frame(&bytes) {
                    state.handle_phone_mic_pcm(samples).await;
                }
            }
            Message::Close(_) => break,
            _ => {}
        }
    }

    writer.abort();
    state.unregister_phone(&conn_id).await;
}

/// Supervises the Tailscale-bound phone server. Strictly binds ONLY to the local Tailscale IPv4.
pub async fn supervise_phone_server(state: Arc<AppState>, mut restart_rx: watch::Receiver<u64>) {
    const RETRY_INTERVAL: Duration = Duration::from_secs(12);
    const MAX_OFFLINE_RETRY_INTERVAL: Duration = Duration::from_secs(60);
    let mut delay = RETRY_INTERVAL;

    let initial = detect_tailscale().await;
    *state.tailscale.write().await = initial;
    loop {
        let port = state.config.read().await.tailscale_port;
        let ts_ip = state
            .tailscale
            .read()
            .await
            .ipv4
            .as_ref()
            .and_then(|s| s.parse::<Ipv4Addr>().ok())
            .filter(|&ip| is_tailscale_ipv4(ip));

        if let Some(ip) = ts_ip {
            delay = RETRY_INTERVAL;
            let addr = SocketAddr::new(IpAddr::V4(ip), port);
            match tokio::net::TcpListener::bind(addr).await {
                Ok(listener) => {
                    *state.phone_server_running.write().await = true;
                    state.emit_status().await;

                    let app = Router::new()
                        .route("/pair/verify", post(pair_verify))
                        .route("/ws/phone", get(ws_phone))
                        .with_state(Arc::clone(&state));

                    let mut stop_watch = restart_rx.clone();
                    let _ = axum::serve(
                        listener,
                        app.into_make_service_with_connect_info::<SocketAddr>(),
                    )
                    .with_graceful_shutdown(async move {
                        let _ = stop_watch.changed().await;
                    })
                    .await;

                    *state.phone_server_running.write().await = false;
                    state.emit_status().await;
                }
                Err(_) => {
                    *state.phone_server_running.write().await = false;
                    state.emit_status().await;
                }
            }
        } else {
            *state.phone_server_running.write().await = false;
            state.emit_status().await;
        }

        // Settings and manual refresh wake the server immediately. Back off while offline.
        tokio::select! {
            change = restart_rx.changed() => {
                if change.is_err() { break; }
                delay = RETRY_INTERVAL;
            }
            _ = tokio::time::sleep(delay) => {
                let fresh = detect_tailscale().await;
                *state.tailscale.write().await = fresh;
                delay = (delay * 2).min(MAX_OFFLINE_RETRY_INTERVAL);
            }
        }
    }
}
