package com.sdp.trade;

import com.sdp.common.Trade;
import com.sdp.eventbus.DomainEvent;

/**
 * A connection's opt-in to live trade-blotter updates, and the rule that a
 * Trade is only visible to a connection subscribed to it - see ADR 0028.
 * Unlike SymbolSubscription, this isn't filtered by trade criteria:
 * TradeBlotter.tsx never inspects the trade payload, it just triggers a
 * refetch that re-applies whatever filter is already active server-side, so
 * a plain subscribed/unsubscribed flag is enough. Not a Spring bean -
 * per-connection instantiated state, same as SymbolSubscription's.
 */
public class BlotterSubscription {

    private volatile boolean subscribed = false;

    public void subscribe() {
        subscribed = true;
    }

    public void unsubscribe() {
        subscribed = false;
    }

    public boolean isVisible(DomainEvent event) {
        return !(event instanceof Trade) || subscribed;
    }
}
