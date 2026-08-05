package com.sdp.common;

import java.time.Instant;

public record Trade(String id, String symbol, Side side, double price, double quantity, Instant timestamp) {
}
