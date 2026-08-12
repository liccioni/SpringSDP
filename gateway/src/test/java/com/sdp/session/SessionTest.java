package com.sdp.session;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTest {

    @Test
    void exposesFields() {
        Session session = new Session("connection-1", "trader1", Set.of("trader"));

        assertThat(session.id()).isEqualTo("connection-1");
        assertThat(session.username()).isEqualTo("trader1");
        assertThat(session.roles()).containsExactly("trader");
    }

    @Test
    void ownsItsOwnSymbolSubscription() {
        Session session = new Session("connection-1", "trader1", Set.of("trader"));

        assertThat(session.subscriptions()).isNotNull();
    }

    @Test
    void ownsItsOwnPendingTradeIds() {
        Session session = new Session("connection-1", "trader1", Set.of("trader"));

        assertThat(session.pendingTrades()).isNotNull();
    }
}
