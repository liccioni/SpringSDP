package com.sdp.trade;

/**
 * CREATE_TRADE's two possible outcomes (issue #152, ADR 0028) - previously
 * a rejection was silently discarded (Mono.empty()) because TRADE_REJECTED
 * reached the submitter via a since-removed broadcast instead. Now both
 * outcomes are real, correlated replies, so SdpWebSocketHandler needs to
 * see which one it got to emit the right envelope.
 */
public sealed interface TradeRequestOutcome {

    record Pending(PendingTrade pending) implements TradeRequestOutcome {
    }

    record Rejected(com.sdp.contracts.TradeRejected rejected) implements TradeRequestOutcome {
    }
}
