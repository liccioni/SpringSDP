package com.sdp.common;

import java.math.BigDecimal;
import java.time.Instant;

public record Trade(String id, String symbol, Side side, BigDecimal price, BigDecimal quantity, Instant timestamp) {
}
