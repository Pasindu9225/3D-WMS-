package lk.wms.layout.api;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lk.wms.auth.service.SessionService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.tenant.TenantContext;
import lk.wms.layout.service.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * Live bin status for the 3D viewer: STOMP over WebSocket at /ws (v0.3 task 7).
 *
 * <ul>
 *   <li>The handshake goes through the normal security chain, so it needs a valid session cookie.</li>
 *   <li>Only our own origin (and the CORS allow-list) may connect: browsers send cookies with WebSocket
 *       handshakes, so another site could otherwise open a socket as the user (cross-site WebSocket hijacking).</li>
 *   <li>Clients may only subscribe to /topic/wh/{warehouse}/bins for a warehouse they can see; they can never
 *       send messages.</li>
 *   <li>Sockets are closed when their session is revoked (checked every minute) or after 15 minutes, the
 *       access-token lifetime; the browser refreshes its session and reconnects, so permission changes apply.</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class LiveConfig implements WebSocketMessageBrokerConfigurer {

    static final Pattern BIN_TOPIC = Pattern.compile("^/topic/wh/([0-9A-HJKMNP-TV-Z]{26})/bins$");
    static final Duration MAX_SOCKET_AGE = Duration.ofMinutes(15);
    private static final Logger log = LoggerFactory.getLogger(LiveConfig.class);

    private final WarehouseService warehouses;
    private final SessionService sessions;
    private final String[] allowedOrigins;
    private final Map<String, Tracked> open = new ConcurrentHashMap<>();

    private record Tracked(WebSocketSession session, Instant opened) {
    }

    public LiveConfig(WarehouseService warehouses, SessionService sessions,
            @Value("${wms.cors.allowed-origins:}") List<String> allowedOrigins) {
        this.warehouses = warehouses;
        this.sessions = sessions;
        // Same-origin is always allowed; extra origins only from the explicit allow-list (SEC-62).
        this.allowedOrigins = allowedOrigins.stream().map(String::trim).filter(o -> !o.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        ThreadPoolTaskScheduler heartbeat = new ThreadPoolTaskScheduler();
        heartbeat.setPoolSize(1);
        heartbeat.setThreadNamePrefix("ws-heartbeat-");
        heartbeat.initialize();
        // Heartbeats every 25 s keep proxies (Nginx closes idle connections after 60 s) from dropping sockets.
        registry.enableSimpleBroker("/topic").setHeartbeatValue(new long[] {25_000, 25_000}).setTaskScheduler(heartbeat);
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(8 * 1024) // clients only send small STOMP frames
                .setSendBufferSizeLimit(512 * 1024)
                .setSendTimeLimit(10_000)
                .addDecoratorFactory(this::track);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                guard(StompHeaderAccessor.wrap(message));
                return message;
            }
        });
    }

    /** Inbound STOMP rules: who may connect, what may be subscribed, nothing may be sent. */
    void guard(StompHeaderAccessor frame) {
        StompCommand command = frame.getCommand();
        if (command == null || command == StompCommand.UNSUBSCRIBE || command == StompCommand.DISCONNECT) {
            return; // heartbeats and leaving are always fine
        }
        CurrentUser user = frame.getUser() instanceof Authentication a && a.getPrincipal() instanceof CurrentUser u ? u : null;
        if (user == null) {
            throw new MessageDeliveryException("not signed in");
        }
        switch (command) {
            case CONNECT, STOMP -> { }
            case SUBSCRIBE -> {
                Matcher m = BIN_TOPIC.matcher(String.valueOf(frame.getDestination()));
                if (!m.matches() || !user.permissions().contains("warehouses:read")) {
                    throw new MessageDeliveryException("subscription not allowed");
                }
                try {
                    TenantContext.callAs(user.tenantId(), () -> warehouses.requireAccess(user, m.group(1)));
                } catch (ApiException e) {
                    throw new MessageDeliveryException("subscription not allowed"); // same answer as missing
                }
            }
            default -> throw new MessageDeliveryException("clients cannot send messages");
        }
    }

    private WebSocketHandler track(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                open.put(session.getId(), new Tracked(session, Instant.now()));
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                open.remove(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    /** Every minute: close sockets whose session was revoked or that outlived the access token. */
    @Scheduled(fixedDelay = 60_000)
    public void closeStaleSockets() {
        Instant cutoff = Instant.now().minus(MAX_SOCKET_AGE);
        for (Tracked t : open.values()) {
            CurrentUser user = t.session().getPrincipal() instanceof Authentication a
                    && a.getPrincipal() instanceof CurrentUser u ? u : null;
            boolean stale = t.opened().isBefore(cutoff) || user == null || sessions.verify(user.sessionId()).isEmpty();
            if (stale) {
                try {
                    t.session().close(CloseStatus.SESSION_NOT_RELIABLE.withReason("session check"));
                } catch (IOException e) {
                    log.debug("closing stale socket failed", e);
                }
                open.remove(t.session().getId());
            }
        }
    }

    public int openSockets() {
        return open.size();
    }
}
