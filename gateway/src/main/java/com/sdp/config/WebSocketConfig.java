package com.sdp.config;

import com.sdp.websocket.SdpWebSocketHandler;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

@Configuration
public class WebSocketConfig {

	@Bean
	public HandlerMapping webSocketHandlerMapping(SdpWebSocketHandler handler) {
		Map<String, WebSocketHandler> handlers = Map.of("/ws", handler);
		SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping(handlers);
		mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return mapping;
	}

	@Bean
	public WebSocketHandlerAdapter webSocketHandlerAdapter() {
		return new WebSocketHandlerAdapter();
	}
}
