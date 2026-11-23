use base64::Engine;
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use std::time::Duration;
use tokio::sync::{mpsc, watch};
use tokio_tungstenite::tungstenite::{client::IntoClientRequest, http::HeaderValue, Message};
use uuid::Uuid;

use crate::codec::{encode_wire_frame, pcm_le_bytes_to_samples, samples_to_pcm_le_bytes, AudioCodec};

const OPENAI_LIVE_WS_URL: &str = "wss://api.openai.com/v1/live/sessions";
const OPENAI_LIVE_MODEL: &str = "gpt-live-1.1";
const FRAME_SAMPLES_20MS: usize = 480; // 24 kHz * 20 ms

#[derive(Debug, Clone)]
pub struct LiveSessionParams {
    pub api_key: String,
    pub voice: String,
    pub instructions: String,
    pub opening_commentary: String,
    pub codec: AudioCodec,
}

#[derive(Debug, Clone)]
pub enum LiveEvent {
    OutputAudioFrame { wire_bytes: Vec<u8> },
    SpeakingState { speaking: bool, level: f32 },
    TranscriptDelta { speaker: String, delta: String },
    Error(String),
    Ended,
}

pub async fn run_live_session(
    params: LiveSessionParams,
    mut mic_pcm_rx: mpsc::Receiver<Vec<i16>>,
    mut stop_rx: watch::Receiver<bool>,
    event_tx: mpsc::Sender<LiveEvent>,
) {
    let res = run_live_session_inner(params, &mut mic_pcm_rx, &mut stop_rx, &event_tx).await;
    if let Err(err) = res {
        let _ = event_tx.send(LiveEvent::Error(err)).await;
    }
    let _ = event_tx
        .send(LiveEvent::SpeakingState {
            speaking: false,
            level: 0.0,
        })
        .await;
    let _ = event_tx.send(LiveEvent::Ended).await;
}

async fn run_live_session_inner(
    params: LiveSessionParams,
    mic_pcm_rx: &mut mpsc::Receiver<Vec<i16>>,
    stop_rx: &mut watch::Receiver<bool>,
    event_tx: &mpsc::Sender<LiveEvent>,
) -> Result<(), String> {
    if params.api_key.trim().is_empty() {
        return Err("OpenAI API key is not configured".to_string());
    }

    let mut req = OPENAI_LIVE_WS_URL
        .into_client_request()
        .map_err(|e| format!("invalid OpenAI URL: {e}"))?;
    let auth = HeaderValue::from_str(&format!("Bearer {}", params.api_key.trim()))
        .map_err(|e| format!("invalid API key header: {e}"))?;
    req.headers_mut().insert("Authorization", auth);

    let (ws_stream, _) = tokio_tungstenite::connect_async(req)
        .await
        .map_err(|e| format!("OpenAI Live WebSocket connect failed: {e}"))?;

    let (mut ws_tx, mut ws_rx) = ws_stream.split();

    // Start GPT Live 1.1 session with NO internet tools / web search.
    let start_evt = json!({
        "type": "session.start",
        "event_id": format!("evt_start_{}", Uuid::new_v4().simple()),
        "session": {
            "model": OPENAI_LIVE_MODEL,
            "instructions": params.instructions,
            "audio": {
                "format": {
                    "type": "audio/pcm",
                    "rate": 24000
                },
                "output": {
                    "voice": params.voice
                }
            }
        }
    });

    ws_tx
        .send(Message::Text(start_evt.to_string().into()))
        .await
        .map_err(|e| format!("failed to send session.start: {e}"))?;

    let b64 = base64::engine::general_purpose::STANDARD;
    let mut session_started = false;
    let mut out_pcm_buf: Vec<i16> = Vec::with_capacity(FRAME_SAMPLES_20MS * 4);
    let mut speaking = false;
    let mut silence_deadline = tokio::time::Instant::now() + Duration::from_secs(3600);

    loop {
        tokio::select! {
            biased;

            changed = stop_rx.changed() => {
                if changed.is_err() || *stop_rx.borrow() {
                    let _ = ws_tx.send(Message::Close(None)).await;
                    break;
                }
            }

            _ = tokio::time::sleep_until(silence_deadline), if speaking => {
                speaking = false;
                let _ = event_tx.try_send(LiveEvent::SpeakingState { speaking: false, level: 0.0 });
                silence_deadline = tokio::time::Instant::now() + Duration::from_secs(3600);
            }

            maybe_pcm = mic_pcm_rx.recv() => {
                let Some(samples) = maybe_pcm else {
                    break;
                };
                if !session_started || samples.is_empty() {
                    continue;
                }
                let raw_bytes = samples_to_pcm_le_bytes(&samples);
                let encoded = b64.encode(&raw_bytes);
                let msg = json!({
                    "type": "session.input_audio.append",
                    "audio": encoded
                });
                if ws_tx.send(Message::Text(msg.to_string().into())).await.is_err() {
                    break;
                }
            }

            maybe_msg = ws_rx.next() => {
                let Some(frame) = maybe_msg else {
                    break;
                };
                let frame = frame.map_err(|e| format!("OpenAI stream error: {e}"))?;
                match frame {
                    Message::Text(txt) => {
                        let Ok(val) = serde_json::from_str::<Value>(&txt) else {
                            continue;
                        };
                        let kind = val.get("type").and_then(|t| t.as_str()).unwrap_or("");
                        match kind {
                            "session.started" => {
                                session_started = true;
                                if !params.opening_commentary.trim().is_empty() {
                                    let greet = json!({
                                        "type": "session.commentary.append",
                                        "event_id": format!("evt_open_{}", Uuid::new_v4().simple()),
                                        "content": params.opening_commentary
                                    });
                                    let _ = ws_tx.send(Message::Text(greet.to_string().into())).await;
                                }
                            }
                            "session.output_audio.delta"
                            | "response.output_audio.delta"
                            | "response.audio.delta" => {
                                if let Some(delta_b64) = val.get("delta").and_then(|d| d.as_str()) {
                                    if let Ok(raw) = b64.decode(delta_b64) {
                                        let samples = pcm_le_bytes_to_samples(&raw);
                                        out_pcm_buf.extend_from_slice(&samples);
                                        while out_pcm_buf.len() >= FRAME_SAMPLES_20MS {
                                            let chunk: Vec<i16> = out_pcm_buf.drain(..FRAME_SAMPLES_20MS).collect();
                                            let (wire_bytes, level) = encode_wire_frame(&chunk, params.codec);
                                            let active_now = level > 0.03;
                                            if active_now {
                                                speaking = true;
                                                silence_deadline = tokio::time::Instant::now() + Duration::from_millis(340);
                                            }
                                            let _ = event_tx.try_send(LiveEvent::SpeakingState {
                                                speaking: speaking || active_now,
                                                level,
                                            });
                                            if event_tx
                                                .send(LiveEvent::OutputAudioFrame { wire_bytes })
                                                .await
                                                .is_err()
                                            {
                                                return Ok(());
                                            }
                                        }
                                    }
                                }
                            }
                            "session.input_transcript.delta"
                            | "conversation.item.input_audio_transcription.delta" => {
                                if let Some(delta) = val.get("delta").and_then(|d| d.as_str()) {
                                    if !delta.is_empty() {
                                        let _ = event_tx.send(LiveEvent::TranscriptDelta {
                                            speaker: "user".to_string(),
                                            delta: delta.to_string(),
                                        }).await;
                                    }
                                }
                            }
                            "session.output_transcript.delta"
                            | "response.output_audio_transcript.delta"
                            | "response.audio_transcript.delta" => {
                                if let Some(delta) = val.get("delta").and_then(|d| d.as_str()) {
                                    if !delta.is_empty() {
                                        let _ = event_tx.send(LiveEvent::TranscriptDelta {
                                            speaker: "agent".to_string(),
                                            delta: delta.to_string(),
                                        }).await;
                                    }
                                }
                            }
                            "error" | "session.error" => {
                                let msg = val
                                    .pointer("/error/message")
                                    .or_else(|| val.get("message"))
                                    .and_then(|m| m.as_str())
                                    .unwrap_or("OpenAI Live API returned an error")
                                    .to_string();
                                return Err(msg);
                            }
                            _ => {}
                        }
                    }
                    Message::Ping(payload) => {
                        let _ = ws_tx.send(Message::Pong(payload)).await;
                    }
                    Message::Close(_) => break,
                    _ => {}
                }
            }
        }
    }

    Ok(())
}
