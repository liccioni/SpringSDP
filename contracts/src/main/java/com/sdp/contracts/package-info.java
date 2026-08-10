/**
 * Message-contract types shared between the WebSocket Gateway, Market Data
 * Service, and Backend/Trading Service (see ADR 0022). These are the payload
 * shapes carried over RabbitMQ once #90-#93 migrate each flow off the
 * in-process EventBus - a plain library with no Spring/AMQP dependency, so
 * every service depends on the wire shape without depending on each other or
 * on a particular broker client.
 */
package com.sdp.contracts;
