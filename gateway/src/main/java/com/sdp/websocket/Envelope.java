package com.sdp.websocket;

/**
 * Message envelope for the WebSocket protocol: every message carries a type and a payload.
 *
 * {@code correlationId} is a generic, reusable request/reply correlation
 * mechanism (issue #131) - null/absent for every envelope type except
 * GET_TRADE_HISTORY/TRADE_HISTORY today, which needs it so more than one
 * request can be in flight at once per connection.
 */
public record Envelope(String type, Object payload, String correlationId) {
	public Envelope(String type, Object payload) {
		this(type, payload, null);
	}
}
