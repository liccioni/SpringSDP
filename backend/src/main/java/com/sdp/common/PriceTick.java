package com.sdp.common;

import java.time.Instant;

public record PriceTick(String symbol, double bid, double ask, Instant timestamp) {
}
