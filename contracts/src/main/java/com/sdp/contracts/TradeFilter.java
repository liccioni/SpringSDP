package com.sdp.contracts;

/**
 * Part of a TradeHistoryQuery: a single filter condition on one column,
 * mirroring AG Grid's own filter model. {@code type} is one of contains,
 * equals, startsWith, lessThan, greaterThan, or inRange (issue #130);
 * {@code valueTo} is only used by inRange. One condition per column - no
 * AND/OR compounds in v1.
 */
public record TradeFilter(String column, String type, String value, String valueTo) {
}
