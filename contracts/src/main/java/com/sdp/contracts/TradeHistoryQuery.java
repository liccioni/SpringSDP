package com.sdp.contracts;

import java.util.List;

/**
 * Wire shape for a GET_TRADE_HISTORY request: {@code cursor} is null for the
 * first page and otherwise the {@code nextCursor} from a prior
 * TradeHistoryPage; {@code sort} is null for the default order (issue #130).
 */
public record TradeHistoryQuery(int pageSize, String cursor, TradeSort sort, List<TradeFilter> filters) {
}
