package com.sdp.trade;

import com.sdp.common.Side;

import java.math.BigDecimal;

public record TradeRejected(String symbol, Side side, BigDecimal price, BigDecimal quantity, String reason) {
}
