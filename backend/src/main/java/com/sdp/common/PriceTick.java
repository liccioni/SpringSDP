package com.sdp.common;

import java.math.BigDecimal;
import java.time.Instant;

public record PriceTick(String symbol, BigDecimal bid, BigDecimal ask, Instant timestamp) {
}
