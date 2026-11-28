package com.estatevista.websocket;

/** Trusted handshake attributes; no client-controlled value is stored here. */
public final class WebSocketSessionAttributes {

    public static final String HANDSHAKE_ID = "ev.ws.handshakeId";
    public static final String SPRING_SESSION_ID = "ev.ws.springSessionId";
    public static final String USER_ID = "ev.ws.userId";
    public static final String ROLE = "ev.ws.role";
    public static final String PRINCIPAL = "ev.ws.principal";
    public static final String CLIENT_IP = "ev.ws.clientIp";
    public static final String ORIGIN = "ev.ws.origin";
    public static final String CONNECTED_AT = "ev.ws.connectedAt";
    public static final String TRANSPORT = "ev.ws.transport";
    /** Session attribute holding the session ID to which sockets were bound. */
    public static final String SESSION_BINDING = "ev.ws.sessionBinding";

    private WebSocketSessionAttributes() {
    }
}
