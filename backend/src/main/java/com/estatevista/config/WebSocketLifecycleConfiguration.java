package com.estatevista.config;

import com.estatevista.websocket.WebSocketSessionRevocationService;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;

import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Lifecycle wiring that closes stale sockets on Spring Session ID rotation. */
@Configuration
@EnableScheduling
public class WebSocketLifecycleConfiguration {

    /**
     * Shared scheduler for broker heartbeats and bounded session revalidation.
     * Spring's simple broker refuses to start with heartbeat values unless an
     * explicit scheduler is present, so this bean is part of the security
     * foundation rather than an optional convenience.
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ws-lifecycle-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    public ServletListenerRegistrationBean<HttpSessionIdListener> webSocketSessionIdListener(
            WebSocketSessionRevocationService revocation) {
        HttpSessionIdListener listener = (HttpSessionEvent event, String oldSessionId) -> {
            HttpSession session = event.getSession();
            revocation.revokeStaleSession(oldSessionId, session == null ? null : session.getId());
        };
        return new ServletListenerRegistrationBean<>(listener);
    }
}
