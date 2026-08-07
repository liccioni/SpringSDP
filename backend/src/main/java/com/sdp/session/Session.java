package com.sdp.session;

/**
 * An authenticated WebSocket connection's identity for its lifetime - see
 * ADR 0017 for why this is 1:1 with the connection rather than surviving a
 * reconnect. {@code id} is the underlying WebSocket connection's own id.
 */
public record Session(String id, String username) {}
