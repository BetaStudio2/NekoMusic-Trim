package com.neko.music.handlers;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** 锁定 Jetty 启动阶段能部署听歌识曲 WebSocket 端点。 */
class MusicRecognitionWebSocketDeploymentTest {

    @Test
    void endpointDeploysDuringServerStartup() {
        Server server = new Server(0);
        ServletContextHandler context = new ServletContextHandler();
        JakartaWebSocketServletContainerInitializer.configure(context, (servletContext, serverContainer) ->
                serverContainer.addEndpoint(MusicRecognitionWebSocketEndpoint.endpointConfig()));
        server.setHandler(context);

        assertDoesNotThrow(() -> {
            try {
                server.start();
            } finally {
                server.stop();
            }
        });
    }
}
