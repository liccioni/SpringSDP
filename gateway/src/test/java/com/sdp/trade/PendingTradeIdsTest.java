package com.sdp.trade;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PendingTradeIdsTest {

    private final PendingTradeIds pendingTradeIds = new PendingTradeIds();

    @Test
    void startsEmpty() {
        assertThat(pendingTradeIds.all()).isEmpty();
    }

    @Test
    void tracksAnAddedId() {
        pendingTradeIds.add("pending-1");

        assertThat(pendingTradeIds.all()).containsExactly("pending-1");
    }

    @Test
    void forgetsARemovedId() {
        pendingTradeIds.add("pending-1");
        pendingTradeIds.remove("pending-1");

        assertThat(pendingTradeIds.all()).isEmpty();
    }

    @Test
    void removingAnUnknownIdIsANoop() {
        pendingTradeIds.remove("never-added");

        assertThat(pendingTradeIds.all()).isEmpty();
    }
}
