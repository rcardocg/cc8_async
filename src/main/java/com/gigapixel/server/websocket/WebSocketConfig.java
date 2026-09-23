package com.gigapixel.server.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Registramos la ruta /ws/tiles y permitimos conexiones de cualquier origen (CORS)
        registry.addHandler(new TileWebSocketHandler(), "/ws/tiles").setAllowedOrigins("*");
    }
}