package com.sdp.contracts;

import java.util.List;

/**
 * The single source of truth for tradable currency pairs (issue #159),
 * replacing three independently hardcoded copies (market-data-service's
 * seed prices, trading-service's validation set, and the frontend's
 * PriceGrid). A compile-time shared constant rather than a runtime
 * broadcast - every consumer already depends on this module, so no new
 * messaging infra is needed.
 *
 * {@code symbols} is the full tradable catalog; {@code majors} is the
 * fixed default-subscribed subset a new connection starts with (see ADR
 * 0029). Neither list persists per-session choices across a reconnect,
 * matching Session's existing no-reconnect-continuity model (ADR 0017).
 */
public record SymbolCatalog(List<String> symbols, List<String> majors) {

    public static final List<String> ALL_SYMBOLS = List.of(
            "EUR/USD", "GBP/USD", "USD/JPY", "USD/CHF", "AUD/USD", "USD/CAD",
            "NZD/USD", "EUR/GBP", "EUR/JPY", "GBP/JPY", "EUR/CHF", "AUD/JPY",
            "USD/CNH", "USD/MXN");

    public static final List<String> MAJORS = List.of(
            "EUR/USD", "GBP/USD", "USD/JPY", "USD/CHF", "AUD/USD", "USD/CAD");

    public static SymbolCatalog defaultCatalog() {
        return new SymbolCatalog(ALL_SYMBOLS, MAJORS);
    }
}
