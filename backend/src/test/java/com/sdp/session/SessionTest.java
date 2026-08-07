package com.sdp.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTest {

    @Test
    void exposesFields() {
        Session session = new Session("connection-1", "trader1");

        assertThat(session.id()).isEqualTo("connection-1");
        assertThat(session.username()).isEqualTo("trader1");
    }

    @Test
    void ownsItsOwnSymbolSubscription() {
        Session session = new Session("connection-1", "trader1");

        assertThat(session.subscriptions()).isNotNull();
    }
}
