package com.estatevista.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Aligns Spring Session's cookie with the documented servlet session cookie
 * policy so JDBC-backed sessions use the approved name and attributes.
 */
@Configuration
public class SessionConfig {

    @Bean
    public CookieSerializer cookieSerializer(
            @Value("${server.servlet.session.cookie.name:EV_SESSION}") String cookieName,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secure,
            @Value("${server.servlet.session.cookie.same-site:lax}") String sameSite) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(cookieName);
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(secure);
        serializer.setSameSite(sameSite);
        return serializer;
    }
}
