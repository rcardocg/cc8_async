package com.gigapixel.server.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final TileWebSocketHandlerV2 handler;

    public WebSocketConfig(TileWebSocketHandlerV2 handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // El cliente y sus recursos se sirven desde este mismo servidor Java.
        // GTP/2 soporta GTP/1 legacy para demo y catálogo plano.
        registry.addHandler(handler, "/ws/tiles");
    }
}
