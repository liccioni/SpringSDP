package com.sdp.trading;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface TradeRepository extends ReactiveCrudRepository<Trade, String> {
}
