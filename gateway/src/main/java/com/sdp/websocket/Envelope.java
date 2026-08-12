package com.sdp.websocket;

/**
 * Message envelope for the WebSocket protocol: every message carries a type and a payload.
 */
public record Envelope(String type, Object payload) {
}
