package com.sdp.contracts;

/**
 * Part of a TradeHistoryQuery: the column to sort trade history by and its
 * direction. {@code column} must be one of the allow-listed sortable columns
 * (issue #130) - single-column sort only, no compound sort in v1.
 */
public record TradeSort(String column, boolean descending) {
}
