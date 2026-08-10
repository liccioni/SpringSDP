package com.sdp.trading;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Flux;

public interface TradeRepository extends ReactiveCrudRepository<Trade, String> {

    Flux<Trade> findAllByOrderByTimestampAsc();
}
