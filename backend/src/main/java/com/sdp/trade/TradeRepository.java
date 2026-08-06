package com.sdp.trade;

import com.sdp.common.Trade;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Flux;

/**
 * R2DBC access to the trades table. Trade history is returned oldest-first;
 * callers decide their own display order.
 */
public interface TradeRepository extends ReactiveCrudRepository<Trade, String> {

    Flux<Trade> findAllByOrderByTimestampAsc();
}
