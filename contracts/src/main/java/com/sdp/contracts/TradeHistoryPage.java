package com.sdp.contracts;

import java.util.List;

/**
 * Reply payload for a GET_TRADE_HISTORY request (issue #130): one page of
 * trade history, plus the cursor to request the next page and whether one
 * exists. {@code nextCursor} is null once {@code hasMore} is false.
 */
public record TradeHistoryPage(List<Trade> rows, String nextCursor, boolean hasMore) {
}
