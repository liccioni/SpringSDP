package com.sdp.market;

import com.sdp.common.PriceTick;
import com.sdp.eventbus.DomainEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A connection's set of subscribed symbols, and the rule that a PriceTick
 * is only visible to a connection subscribed to its symbol. Not a Spring
 * bean - per-connection instantiated state, same as MarketDataService's and
 * TradeService's in-memory state.
 */
public class SymbolSubscription {

    private final Set<String> symbols = ConcurrentHashMap.newKeySet();

    public void subscribe(String symbol) {
        symbols.add(symbol);
    }

    public void unsubscribe(String symbol) {
        symbols.remove(symbol);
    }

    public boolean isVisible(DomainEvent event) {
        return !(event instanceof PriceTick tick) || symbols.contains(tick.symbol());
    }
}
