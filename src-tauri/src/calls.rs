use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::HashMap;
use std::fs;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter};
use tokio::sync::{mpsc, watch, Mutex, RwLock};
use uuid::Uuid;

use crate::codec::AudioCodec;
use crate::config::{calls_dir, load_config, save_config, CloodConfig};
use crate::openai_live::{run_live_session, LiveEvent, LiveSessionParams};
use crate::tailscale::{detect_tailscale, TailscaleInfo};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TranscriptEntry {
    pub speaker: String,
    pub text: String,
    pub timestamp_ms: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CallStatus {
    Ringing,
    Active,
    Completed,
    Declined,
    Missed,
    Failed,
    Cancelled,
}

impl CallStatus {
    pub fn is_terminal(self) -> bool {
        matches!(
            self,
            Self::Completed | Self::Declined | Self::Missed | Self::Failed | Self::Cancelled
        )
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CallRecord {
    pub call_id: String,
    pub caller_name: String,
    pub status: CallStatus,
    pub context: String,
    pub started_at_ms: u64,
    pub answered_at_ms: Option<u64>,
    pub ended_at_ms: Option<u64>,
    pub duration_sec: u64,
    pub transcript: Vec<TranscriptEntry>,
    pub transcript_text: String,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuntimeStatus {
    pub tailscale: TailscaleInfo,
    pub phone_connected: bool,
    pub phone_device_name: Option<String>,
    pub phone_server_running: bool,
    pub api_url: String,
    pub active_call: Option<CallRecord>,
    pub agent_speaking: bool,
    pub audio_level: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairingPayload {
    pub uri: String,
    pub host: String,
    pub port: u16,
    pub token: String,
    pub name: String,
    pub color: String,
    pub codec: AudioCodec,
    pub tailnet: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct StartCallRequest {
    #[serde(default)]
    pub context: String,
    pub opening_line: Option<String>,
    pub system_prompt: Option<String>,
    pub caller_name: Option<String>,
    pub voice: Option<String>,
    pub orb_color: Option<String>,
    pub ring_timeout_sec: Option<u64>,
    pub max_duration_sec: Option<u64>,
    pub wait: Option<bool>,
}

#[derive(Debug, Clone)]
pub enum PhoneOutbound {
    Text(String),
    Binary(Vec<u8>),
}

pub struct ConnectedPhone {
    pub conn_id: String,
    pub device_name: String,
    pub tx: mpsc::Sender<PhoneOutbound>,
}

struct ActiveCallRuntime {
    record: CallRecord,
    voice: String,
    codec: AudioCodec,
    instructions: String,
    opening_commentary: String,
    max_duration_sec: u64,
    mic_tx: Option<mpsc::Sender<Vec<i16>>>,
    stop_tx: Option<watch::Sender<bool>>,
    done_tx: watch::Sender<Option<CallRecord>>,
}

pub struct AppState {
    pub config: RwLock<CloodConfig>,
    pub tailscale: RwLock<TailscaleInfo>,
    pub phone_server_running: RwLock<bool>,
    pub phone: Mutex<Option<ConnectedPhone>>,
    active_call: Mutex<Option<ActiveCallRuntime>>,
    finished_waiters: Mutex<HashMap<String, watch::Receiver<Option<CallRecord>>>>,
    speaking: RwLock<(bool, f32)>,
    app_handle: RwLock<Option<AppHandle>>,
    phone_server_restart_tx: Mutex<Option<watch::Sender<u64>>>,
}

impl AppState {
    pub fn new() -> Arc<Self> {
        let cfg = load_config();
        let ts = detect_tailscale();
        Arc::new(Self {
            config: RwLock::new(cfg),
            tailscale: RwLock::new(ts),
            phone_server_running: RwLock::new(false),
            phone: Mutex::new(None),
            active_call: Mutex::new(None),
            finished_waiters: Mutex::new(HashMap::new()),
            speaking: RwLock::new((false, 0.0)),
            app_handle: RwLock::new(None),
            phone_server_restart_tx: Mutex::new(None),
        })
    }

    pub async fn set_app_handle(&self, handle: AppHandle) {
        *self.app_handle.write().await = Some(handle);
    }

    pub async fn set_phone_server_restart_tx(&self, tx: watch::Sender<u64>) {
        *self.phone_server_restart_tx.lock().await = Some(tx);
    }

    pub async fn trigger_phone_server_restart(&self) {
        if let Some(tx) = self.phone_server_restart_tx.lock().await.as_ref() {
            let next = *tx.borrow() + 1;
            let _ = tx.send(next);
        }
    }

    pub async fn status(&self) -> RuntimeStatus {
        let cfg = self.config.read().await.clone();
        let ts = self.tailscale.read().await.clone();
        let running = *self.phone_server_running.read().await;
        let (phone_connected, phone_device_name) = {
            let guard = self.phone.lock().await;
            match guard.as_ref() {
                Some(p) => (true, Some(p.device_name.clone())),
                None => (false, cfg.paired_device_name.clone()),
            }
        };
        let active_call = self
            .active_call
            .lock()
            .await
            .as_ref()
            .map(|c| c.record.clone());
        let (agent_speaking, audio_level) = *self.speaking.read().await;

        RuntimeStatus {
            tailscale: ts,
            phone_connected,
            phone_device_name,
            phone_server_running: running,
            api_url: format!("http://127.0.0.1:{}", cfg.api_port),
            active_call,
            agent_speaking,
            audio_level,
        }
    }

    pub async fn emit_status(&self) {
        let st = self.status().await;
        if let Some(handle) = self.app_handle.read().await.as_ref() {
            let _ = handle.emit("clood://status", &st);
        }
    }

    pub async fn emit_audio_level(&self, speaking: bool, level: f32) {
        *self.speaking.write().await = (speaking, level);
        if let Some(handle) = self.app_handle.read().await.as_ref() {
            let _ = handle.emit(
                "clood://audio-level",
                json!({ "speaking": speaking, "level": level }),
            );
        }
    }

    pub async fn pairing_payload(&self) -> Result<PairingPayload, String> {
        let cfg = self.config.read().await.clone();
        let ts = self.tailscale.read().await.clone();
        let host = ts
            .ipv4
            .clone()
            .ok_or_else(|| ts.error.unwrap_or_else(|| "Tailscale is offline".to_string()))?;

        let uri = format!(
            "clood://pair?host={}&port={}&token={}&name={}&color={}&codec={}",
            urlencoding::encode(&host),
            cfg.tailscale_port,
            urlencoding::encode(&cfg.pairing_token),
            urlencoding::encode(&cfg.agent_name),
            urlencoding::encode(&cfg.orb_color),
            match cfg.audio_codec {
                AudioCodec::Adpcm24k => "adpcm_24k",
                AudioCodec::Ulaw24k => "ulaw_24k",
            }
        );

        Ok(PairingPayload {
            uri,
            host,
            port: cfg.tailscale_port,
            token: cfg.pairing_token,
            name: cfg.agent_name,
            color: cfg.orb_color,
            codec: cfg.audio_codec,
            tailnet: ts.tailnet_name.or(ts.dns_name),
        })
    }

    pub async fn send_config_sync_to_phone(&self) {
        let cfg = self.config.read().await.clone();
        let payload = json!({
            "type": "config_sync",
            "agent_name": cfg.agent_name,
            "orb_color": cfg.orb_color,
            "audio_codec": cfg.audio_codec
        })
        .to_string();
        if let Some(phone) = self.phone.lock().await.as_ref() {
            let _ = phone.tx.try_send(PhoneOutbound::Text(payload));
        }
    }

    pub async fn update_config(&self, mut next: CloodConfig) -> Result<CloodConfig, String> {
        let prev = self.config.read().await.clone();
        if next.pairing_token.trim().is_empty() {
            next.pairing_token = prev.pairing_token.clone();
        }
        if next.agent_name.trim().is_empty() {
            next.agent_name = "Clood".to_string();
        }
        save_config(&next)?;
        let port_changed = next.tailscale_port != prev.tailscale_port;
        *self.config.write().await = next.clone();
        self.send_config_sync_to_phone().await;
        if port_changed {
            self.trigger_phone_server_restart().await;
        }
        self.emit_status().await;
        Ok(next)
    }

    pub async fn refresh_tailscale(&self) -> RuntimeStatus {
        let ts = detect_tailscale();
        *self.tailscale.write().await = ts;
        self.trigger_phone_server_restart().await;
        self.emit_status().await;
        self.status().await
    }

    pub async fn register_phone(
        &self,
        conn_id: String,
        device_name: String,
        tx: mpsc::Sender<PhoneOutbound>,
    ) {
        {
            let mut guard = self.phone.lock().await;
            *guard = Some(ConnectedPhone {
                conn_id,
                device_name: device_name.clone(),
                tx,
            });
        }
        {
            let mut cfg = self.config.write().await;
            if cfg.paired_device_name.as_deref() != Some(&device_name) {
                cfg.paired_device_name = Some(device_name);
                let _ = save_config(&cfg);
            }
        }
        self.send_config_sync_to_phone().await;
        self.emit_status().await;
    }

    pub async fn unregister_phone(self: &Arc<Self>, conn_id: &str) {
        let removed = {
            let mut guard = self.phone.lock().await;
            if guard.as_ref().map(|p| p.conn_id.as_str()) == Some(conn_id) {
                *guard = None;
                true
            } else {
                false
            }
        };
        if removed {
            // If a call was active or ringing, terminate it cleanly.
            let active_id = self
                .active_call
                .lock()
                .await
                .as_ref()
                .map(|c| c.record.call_id.clone());
            if let Some(call_id) = active_id {
                self.finish_call(
                    &call_id,
                    CallStatus::Completed,
                    Some("Phone disconnected".to_string()),
                    false,
                )
                .await;
            }
            self.emit_status().await;
        }
    }

    pub async fn start_call(
        self: &Arc<Self>,
        req: StartCallRequest,
    ) -> Result<(CallRecord, watch::Receiver<Option<CallRecord>>), String> {
        let cfg = self.config.read().await.clone();
        if cfg.openai_api_key.trim().is_empty() {
            return Err("OpenAI API key is not configured in Clood settings".to_string());
        }

        let phone_tx = {
            let guard = self.phone.lock().await;
            guard
                .as_ref()
                .map(|p| p.tx.clone())
                .ok_or_else(|| "No phone is connected to Clood over Tailscale".to_string())?
        };

        let mut active_guard = self.active_call.lock().await;
        if let Some(existing) = active_guard.as_ref() {
            return Err(format!(
                "Another call is already in progress ({})",
                existing.record.call_id
            ));
        }

        let call_id = format!("call_{}", Uuid::new_v4().simple());
        let caller_name = req
            .caller_name
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| cfg.agent_name.clone());
        let voice = req
            .voice
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| cfg.voice.clone());
        let orb_color = req
            .orb_color
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| cfg.orb_color.clone());
        let ring_timeout_sec = req.ring_timeout_sec.unwrap_or(45).clamp(10, 180);
        let max_duration_sec = req.max_duration_sec.unwrap_or(900).clamp(30, 3600);

        let mut instructions = cfg.base_instructions.clone();
        if let Some(extra) = req.system_prompt.as_deref() {
            if !extra.trim().is_empty() {
                instructions.push_str("\n\nAdditional instructions:\n");
                instructions.push_str(extra.trim());
            }
        }
        if !req.context.trim().is_empty() {
            instructions.push_str("\n\nCall context provided by the calling agent:\n");
            instructions.push_str(req.context.trim());
        }
        instructions.push_str(
            "\n\nImportant: You have no internet access. Rely only on the provided context and what the user says during the call. Keep answers crisp and conversational.",
        );

        let opening_commentary = if let Some(opening) = req.opening_line.filter(|s| !s.trim().is_empty()) {
            format!(
                "The user just answered the phone. Greet them naturally and open the call with: {opening}"
            )
        } else if !req.context.trim().is_empty() {
            "The user just answered the phone. Greet them naturally, briefly state why you are calling based on the context in your instructions, and ask for their input.".to_string()
        } else {
            format!(
                "The user just answered the phone. Greet them warmly as {caller_name} and let them know the Clood Tailscale audio connection is working."
            )
        };

        let record = CallRecord {
            call_id: call_id.clone(),
            caller_name: caller_name.clone(),
            status: CallStatus::Ringing,
            context: req.context,
            started_at_ms: now_ms(),
            answered_at_ms: None,
            ended_at_ms: None,
            duration_sec: 0,
            transcript: Vec::new(),
            transcript_text: String::new(),
            error: None,
        };

        let (done_tx, done_rx) = watch::channel(None);
        *active_guard = Some(ActiveCallRuntime {
            record: record.clone(),
            voice,
            codec: cfg.audio_codec,
            instructions,
            opening_commentary,
            max_duration_sec,
            mic_tx: None,
            stop_tx: None,
            done_tx,
        });
        drop(active_guard);

        {
            let mut waiters = self.finished_waiters.lock().await;
            waiters.insert(call_id.clone(), done_rx.clone());
            if waiters.len() > 64 {
                waiters.retain(|k, _| k == &call_id);
            }
        }

        let ring_msg = json!({
            "type": "call_incoming",
            "call_id": call_id,
            "caller_name": caller_name,
            "orb_color": orb_color,
            "audio_codec": cfg.audio_codec,
            "ring_timeout_sec": ring_timeout_sec
        })
        .to_string();

        if phone_tx.send(PhoneOutbound::Text(ring_msg)).await.is_err() {
            self.finish_call(
                &call_id,
                CallStatus::Failed,
                Some("Failed to deliver ring signal to phone".to_string()),
                false,
            )
            .await;
            return Err("Failed to deliver ring signal to phone".to_string());
        }

        self.emit_status().await;

        // Ring timeout watchdog
        let state_clone = Arc::clone(self);
        let timeout_call_id = call_id.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_secs(ring_timeout_sec)).await;
            let still_ringing = {
                let guard = state_clone.active_call.lock().await;
                guard.as_ref().is_some_and(|c| {
                    c.record.call_id == timeout_call_id && c.record.status == CallStatus::Ringing
                })
            };
            if still_ringing {
                state_clone
                    .finish_call(
                        &timeout_call_id,
                        CallStatus::Missed,
                        Some("Call timed out without answer".to_string()),
                        true,
                    )
                    .await;
            }
        });

        Ok((record, done_rx))
    }

    pub async fn handle_phone_accept(self: &Arc<Self>, call_id: &str) {
        let cfg = self.config.read().await.clone();
        let (params, mic_rx, stop_rx, max_duration_sec) = {
            let mut guard = self.active_call.lock().await;
            let Some(active) = guard.as_mut() else {
                return;
            };
            if active.record.call_id != call_id || active.record.status != CallStatus::Ringing {
                return;
            }
            active.record.status = CallStatus::Active;
            active.record.answered_at_ms = Some(now_ms());

            let (mic_tx, mic_rx) = mpsc::channel::<Vec<i16>>(128);
            let (stop_tx, stop_rx) = watch::channel(false);
            active.mic_tx = Some(mic_tx);
            active.stop_tx = Some(stop_tx);

            let params = LiveSessionParams {
                api_key: cfg.openai_api_key.clone(),
                voice: active.voice.clone(),
                instructions: active.instructions.clone(),
                opening_commentary: active.opening_commentary.clone(),
                codec: active.codec,
            };
            (params, mic_rx, stop_rx, active.max_duration_sec)
        };

        self.emit_status().await;

        let (event_tx, mut event_rx) = mpsc::channel::<LiveEvent>(256);
        let live_call_id = call_id.to_string();
        let state_for_events = Arc::clone(self);

        tokio::spawn(run_live_session(params, mic_rx, stop_rx, event_tx));

        // Max call duration guard
        let state_for_cap = Arc::clone(self);
        let cap_call_id = live_call_id.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_secs(max_duration_sec)).await;
            let still_active = {
                let guard = state_for_cap.active_call.lock().await;
                guard.as_ref().is_some_and(|c| c.record.call_id == cap_call_id)
            };
            if still_active {
                state_for_cap
                    .finish_call(
                        &cap_call_id,
                        CallStatus::Completed,
                        Some("Max call duration reached".to_string()),
                        true,
                    )
                    .await;
            }
        });

        // Bridge OpenAI events -> Phone audio + Transcript + PC UI orb
        tokio::spawn(async move {
            let mut live_err: Option<String> = None;
            while let Some(ev) = event_rx.recv().await {
                match ev {
                    LiveEvent::OutputAudioFrame { wire_bytes } => {
                        let phone_tx = {
                            state_for_events
                                .phone
                                .lock()
                                .await
                                .as_ref()
                                .map(|p| p.tx.clone())
                        };
                        if let Some(tx) = phone_tx {
                            let _ = tx.try_send(PhoneOutbound::Binary(wire_bytes));
                        }
                    }
                    LiveEvent::SpeakingState { speaking, level } => {
                        state_for_events.emit_audio_level(speaking, level).await;
                    }
                    LiveEvent::TranscriptDelta { speaker, delta } => {
                        state_for_events
                            .append_transcript(&live_call_id, &speaker, &delta)
                            .await;
                    }
                    LiveEvent::Error(err) => {
                        live_err = Some(err);
                    }
                    LiveEvent::Ended => break,
                }
            }

            let still_same = {
                state_for_events
                    .active_call
                    .lock()
                    .await
                    .as_ref()
                    .is_some_and(|c| c.record.call_id == live_call_id)
            };
            if still_same {
                let status = if live_err.is_some() {
                    CallStatus::Failed
                } else {
                    CallStatus::Completed
                };
                state_for_events
                    .finish_call(&live_call_id, status, live_err, true)
                    .await;
            }
        });
    }

    pub async fn handle_phone_mic_pcm(&self, samples: Vec<i16>) {
        let tx = {
            let guard = self.active_call.lock().await;
            guard.as_ref().and_then(|c| c.mic_tx.clone())
        };
        if let Some(tx) = tx {
            let _ = tx.try_send(samples);
        }
    }

    pub async fn handle_phone_decline(self: &Arc<Self>, call_id: &str) {
        self.finish_call(call_id, CallStatus::Declined, None, false)
            .await;
    }

    pub async fn handle_phone_hangup(self: &Arc<Self>, call_id: &str) {
        let status = {
            let guard = self.active_call.lock().await;
            match guard.as_ref() {
                Some(c) if c.record.call_id == call_id && c.record.status == CallStatus::Ringing => {
                    CallStatus::Declined
                }
                _ => CallStatus::Completed,
            }
        };
        self.finish_call(call_id, status, None, false).await;
    }

    pub async fn hangup_call(self: &Arc<Self>, call_id: Option<&str>) -> Option<CallRecord> {
        let target_id = {
            let guard = self.active_call.lock().await;
            let active = guard.as_ref()?;
            if let Some(req_id) = call_id {
                if active.record.call_id != req_id {
                    return None;
                }
            }
            active.record.call_id.clone()
        };

        let status = {
            let guard = self.active_call.lock().await;
            match guard.as_ref() {
                Some(c) if c.record.status == CallStatus::Ringing => CallStatus::Cancelled,
                _ => CallStatus::Completed,
            }
        };

        self.finish_call(&target_id, status, None, true).await
    }

    async fn append_transcript(&self, call_id: &str, speaker: &str, delta: &str) {
        let mut guard = self.active_call.lock().await;
        let Some(active) = guard.as_mut() else {
            return;
        };
        if active.record.call_id != call_id {
            return;
        }
        let now = now_ms();
        if let Some(last) = active.record.transcript.last_mut() {
            if last.speaker == speaker {
                last.text.push_str(delta);
                return;
            }
        }
        active.record.transcript.push(TranscriptEntry {
            speaker: speaker.to_string(),
            text: delta.to_string(),
            timestamp_ms: now,
        });
    }

    pub async fn finish_call(
        &self,
        call_id: &str,
        status: CallStatus,
        error: Option<String>,
        notify_phone: bool,
    ) -> Option<CallRecord> {
        let finalized = {
            let mut guard = self.active_call.lock().await;
            let Some(active) = guard.as_ref() else {
                return None;
            };
            if active.record.call_id != call_id {
                return None;
            }
            let mut active = guard.take().unwrap();
            if let Some(stop_tx) = active.stop_tx.take() {
                let _ = stop_tx.send(true);
            }
            active.mic_tx.take();

            let ended = now_ms();
            active.record.status = status;
            active.record.ended_at_ms = Some(ended);
            active.record.duration_sec = active
                .record
                .answered_at_ms
                .map(|ans| ended.saturating_sub(ans) / 1000)
                .unwrap_or(0);
            if error.is_some() {
                active.record.error = error;
            }
            active.record.transcript_text = format_transcript(&active.record);
            let record = active.record.clone();
            let _ = active.done_tx.send(Some(record.clone()));
            record
        };

        persist_call(&finalized);

        if notify_phone {
            let msg = json!({
                "type": "call_ended",
                "call_id": call_id,
                "reason": status
            })
            .to_string();
            if let Some(phone) = self.phone.lock().await.as_ref() {
                let _ = phone.tx.try_send(PhoneOutbound::Text(msg));
            }
        }

        self.emit_audio_level(false, 0.0).await;
        self.emit_status().await;
        Some(finalized)
    }

    pub async fn get_call(&self, call_id: &str) -> Option<CallRecord> {
        {
            let guard = self.active_call.lock().await;
            if let Some(active) = guard.as_ref() {
                if active.record.call_id == call_id {
                    let mut rec = active.record.clone();
                    rec.transcript_text = format_transcript(&rec);
                    return Some(rec);
                }
            }
        }
        load_persisted_call(call_id)
    }

    pub async fn wait_for_call(
        &self,
        call_id: &str,
        timeout_sec: u64,
    ) -> Result<CallRecord, String> {
        if let Some(existing) = load_persisted_call(call_id) {
            if existing.status.is_terminal() {
                return Ok(existing);
            }
        }

        let mut rx = {
            let waiters = self.finished_waiters.lock().await;
            waiters
                .get(call_id)
                .cloned()
                .ok_or_else(|| format!("Call not found: {call_id}"))?
        };

        if let Some(done) = rx.borrow().clone() {
            return Ok(done);
        }

        let wait_fut = async move {
            loop {
                if rx.changed().await.is_err() {
                    break;
                }
                if let Some(done) = rx.borrow().clone() {
                    return Ok(done);
                }
            }
            Err("Call waiter closed unexpectedly".to_string())
        };

        match tokio::time::timeout(Duration::from_secs(timeout_sec), wait_fut).await {
            Ok(res) => res,
            Err(_) => Err(format!("Timed out waiting for call {call_id}")),
        }
    }

    pub async fn list_calls(&self, limit: usize) -> Vec<CallRecord> {
        let mut items = load_all_persisted_calls();
        if let Some(active) = self.active_call.lock().await.as_ref() {
            let mut rec = active.record.clone();
            rec.transcript_text = format_transcript(&rec);
            items.retain(|c| c.call_id != rec.call_id);
            items.insert(0, rec);
        }
        items.truncate(limit);
        items
    }

    pub fn delete_call(&self, call_id: &str) -> Result<(), String> {
        let safe_id: String = call_id
            .chars()
            .filter(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-')
            .collect();
        let path = calls_dir().join(format!("{safe_id}.json"));
        if path.exists() {
            fs::remove_file(path).map_err(|e| e.to_string())?;
        }
        Ok(())
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn format_transcript(record: &CallRecord) -> String {
    record
        .transcript
        .iter()
        .map(|e| {
            let label = if e.speaker == "agent" {
                record.caller_name.as_str()
            } else {
                "User"
            };
            format!("{label}: {}", e.text.trim())
        })
        .collect::<Vec<_>>()
        .join("\n")
}

fn persist_call(record: &CallRecord) {
    let dir = calls_dir();
    if fs::create_dir_all(&dir).is_err() {
        return;
    }
    let path = dir.join(format!("{}.json", record.call_id));
    if let Ok(json) = serde_json::to_string_pretty(record) {
        let _ = fs::write(path, json);
    }
}

fn load_persisted_call(call_id: &str) -> Option<CallRecord> {
    let safe_id: String = call_id
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-')
        .collect();
    let path = calls_dir().join(format!("{safe_id}.json"));
    let raw = fs::read_to_string(path).ok()?;
    serde_json::from_str(&raw).ok()
}

fn load_all_persisted_calls() -> Vec<CallRecord> {
    let dir = calls_dir();
    let Ok(entries) = fs::read_dir(dir) else {
        return Vec::new();
    };
    let mut list: Vec<CallRecord> = entries
        .flatten()
        .filter(|e| e.path().extension().and_then(|s| s.to_str()) == Some("json"))
        .filter_map(|e| fs::read_to_string(e.path()).ok())
        .filter_map(|raw| serde_json::from_str::<CallRecord>(&raw).ok())
        .collect();
    list.sort_by(|a, b| b.started_at_ms.cmp(&a.started_at_ms));
    list
}
