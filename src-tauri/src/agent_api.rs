use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    response::IntoResponse,
    routing::{get, post},
    Json, Router,
};
use serde::Deserialize;
use serde_json::json;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;

use crate::calls::{AppState, StartCallRequest};

#[derive(Debug, Deserialize)]
struct ListQuery {
    limit: Option<usize>,
}

#[derive(Debug, Deserialize)]
struct WaitQuery {
    timeout_sec: Option<u64>,
}

async fn health() -> impl IntoResponse {
    Json(json!({
        "ok": true,
        "app": "clood",
        "version": env!("CARGO_PKG_VERSION"),
        "model": "gpt-live-1.1"
    }))
}

async fn api_status(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    let st = state.status().await;
    let cfg = state.config.read().await.clone();
    let ready = st.tailscale.available
        && st.phone_connected
        && !cfg.openai_api_key.trim().is_empty()
        && st.active_call.is_none();

    Json(json!({
        "ok": true,
        "ready_to_call": ready,
        "model": "gpt-live-1.1",
        "agent_name": cfg.agent_name,
        "voice": cfg.voice,
        "orb_color": cfg.orb_color,
        "tailscale": st.tailscale,
        "phone_connected": st.phone_connected,
        "phone_device_name": st.phone_device_name,
        "openai_configured": !cfg.openai_api_key.trim().is_empty(),
        "active_call": st.active_call
    }))
}

async fn create_call(
    State(state): State<Arc<AppState>>,
    Json(req): Json<StartCallRequest>,
) -> impl IntoResponse {
    let wait = req.wait.unwrap_or(true);
    let max_wait = req.max_duration_sec.unwrap_or(900) + req.ring_timeout_sec.unwrap_or(45) + 30;

    let (record, _rx) = match state.start_call(req).await {
        Ok(pair) => pair,
        Err(err) => {
            return (
                StatusCode::CONFLICT,
                Json(json!({ "ok": false, "error": err })),
            );
        }
    };

    if !wait {
        return (
            StatusCode::ACCEPTED,
            Json(json!({
                "ok": true,
                "call_id": record.call_id,
                "status": record.status,
                "call": record
            })),
        );
    }

    match state.wait_for_call(&record.call_id, max_wait).await {
        Ok(completed) => (
            StatusCode::OK,
            Json(json!({
                "ok": true,
                "call_id": completed.call_id,
                "status": completed.status,
                "duration_sec": completed.duration_sec,
                "transcript": completed.transcript,
                "transcript_text": completed.transcript_text,
                "error": completed.error,
                "call": completed
            })),
        ),
        Err(err) => (
            StatusCode::GATEWAY_TIMEOUT,
            Json(json!({
                "ok": false,
                "call_id": record.call_id,
                "error": err
            })),
        ),
    }
}

async fn get_calls(
    State(state): State<Arc<AppState>>,
    Query(query): Query<ListQuery>,
) -> impl IntoResponse {
    let limit = query.limit.unwrap_or(20).clamp(1, 100);
    let calls = state.list_calls(limit).await;
    Json(json!({ "ok": true, "calls": calls }))
}

async fn get_call(
    State(state): State<Arc<AppState>>,
    Path(call_id): Path<String>,
) -> impl IntoResponse {
    match state.get_call(&call_id).await {
        Some(call) => (StatusCode::OK, Json(json!({ "ok": true, "call": call }))),
        None => (
            StatusCode::NOT_FOUND,
            Json(json!({ "ok": false, "error": "Call not found" })),
        ),
    }
}

async fn wait_call(
    State(state): State<Arc<AppState>>,
    Path(call_id): Path<String>,
    Query(query): Query<WaitQuery>,
) -> impl IntoResponse {
    let timeout_sec = query.timeout_sec.unwrap_or(1800).clamp(5, 3600);
    match state.wait_for_call(&call_id, timeout_sec).await {
        Ok(call) => (
            StatusCode::OK,
            Json(json!({
                "ok": true,
                "call_id": call.call_id,
                "status": call.status,
                "duration_sec": call.duration_sec,
                "transcript": call.transcript,
                "transcript_text": call.transcript_text,
                "error": call.error,
                "call": call
            })),
        ),
        Err(err) => (
            StatusCode::GATEWAY_TIMEOUT,
            Json(json!({ "ok": false, "error": err })),
        ),
    }
}

async fn hangup_call(
    State(state): State<Arc<AppState>>,
    Path(call_id): Path<String>,
) -> impl IntoResponse {
    match state.hangup_call(Some(&call_id)).await {
        Some(call) => (StatusCode::OK, Json(json!({ "ok": true, "call": call }))),
        None => (
            StatusCode::NOT_FOUND,
            Json(json!({ "ok": false, "error": "No matching active call" })),
        ),
    }
}

/// Runs the local Agent HTTP API strictly on 127.0.0.1 (loopback only).
pub async fn run_agent_api(state: Arc<AppState>) {
    let port = state.config.read().await.api_port;
    let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port);

    let app = Router::new()
        .route("/api/v1/health", get(health))
        .route("/api/v1/status", get(api_status))
        .route("/api/v1/calls", get(get_calls).post(create_call))
        .route("/api/v1/calls/{id}", get(get_call))
        .route("/api/v1/calls/{id}/wait", get(wait_call))
        .route("/api/v1/calls/{id}/hangup", post(hangup_call))
        .with_state(state);

    if let Ok(listener) = tokio::net::TcpListener::bind(addr).await {
        let _ = axum::serve(listener, app).await;
    }
}
