package com.medix.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwt; private final IdentityService identities;
    public JwtAuthenticationFilter(JwtService jwt, IdentityService identities) { this.jwt=jwt; this.identities=identities; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String token = bearer(request);
        if (token == null && request.getCookies()!=null) token = Arrays.stream(request.getCookies()).filter(c -> "MEDIX_SESSION".equals(c.getName())).map(Cookie::getValue).findFirst().orElse(null);
        String username = token == null ? null : jwt.verify(token);
        String credentials = token;
        identities.find(username == null ? "" : username).ifPresent(p -> SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, credentials, p.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_"+r)).toList())));
        chain.doFilter(request,response);
    }
    private String bearer(HttpServletRequest r) { String h=r.getHeader("Authorization"); return h!=null&&h.startsWith("Bearer ")?h.substring(7):null; }
}
