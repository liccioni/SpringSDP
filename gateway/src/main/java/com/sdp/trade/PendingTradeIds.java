package com.sdp.trade;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The set of a connection's own PendingTrade ids still awaiting
 * CONFIRM_TRADE/CANCEL_TRADE. Not a Spring bean - per-connection
 * instantiated state, same as SymbolSubscription's. Lets
 * SdpWebSocketHandler cancel whatever's left when the connection closes
 * (issue #79) without trading-service needing any session/connection
 * concept of its own.
 */
public class PendingTradeIds {

    private final Set<String> ids = ConcurrentHashMap.newKeySet();

    public void add(String id) {
        ids.add(id);
    }

    public void remove(String id) {
        ids.remove(id);
    }

    public Set<String> all() {
        return Set.copyOf(ids);
    }
}
