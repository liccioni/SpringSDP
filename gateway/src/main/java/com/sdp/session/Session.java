package com.sdp.session;

import com.sdp.market.SymbolSubscription;
import com.sdp.trade.PendingTradeIds;

/**
 * An authenticated WebSocket connection's identity for its lifetime - see
 * ADR 0017 for why this is 1:1 with the connection rather than surviving a
 * reconnect. {@code id} is the underlying WebSocket connection's own id.
 *
 * Owns this connection's market data subscriptions (see ADR 0017's
 * consequences, and issue #26), so PRICE_TICK visibility is a property of
 * the session rather than a value threaded separately through the handler.
 *
 * Also owns the ids of this connection's own PendingTrades still awaiting
 * CONFIRM_TRADE/CANCEL_TRADE (issue #79), so SdpWebSocketHandler can cancel
 * whatever's left when the connection closes.
 */
public record Session(String id, String username, SymbolSubscription subscriptions, PendingTradeIds pendingTrades) {

    public Session(String id, String username) {
        this(id, username, new SymbolSubscription(), new PendingTradeIds());
    }
}
