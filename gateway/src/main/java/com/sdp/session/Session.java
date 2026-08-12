package com.sdp.session;

import com.sdp.market.SymbolSubscription;

/**
 * An authenticated WebSocket connection's identity for its lifetime - see
 * ADR 0017 for why this is 1:1 with the connection rather than surviving a
 * reconnect. {@code id} is the underlying WebSocket connection's own id.
 *
 * Owns this connection's market data subscriptions (see ADR 0017's
 * consequences, and issue #26), so PRICE_TICK visibility is a property of
 * the session rather than a value threaded separately through the handler.
 */
public record Session(String id, String username, SymbolSubscription subscriptions) {

    public Session(String id, String username) {
        this(id, username, new SymbolSubscription());
    }
}
