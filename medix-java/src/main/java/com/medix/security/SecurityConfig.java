package com.medix.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder(){ return new BCryptPasswordEncoder(); }
    @Bean SecurityFilterChain security(HttpSecurity http, JwtAuthenticationFilter filter) throws Exception {
        return http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/register", "/actuator/health", "/error",
                                "/", "/index.html", "/favicon.ico", "/assets/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN").anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((q,r,x)->{r.setStatus(401);r.setContentType("application/json");r.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"请先登录\"}");})
                        .accessDeniedHandler((q,r,x)->{r.setStatus(403);r.setContentType("application/json");r.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"权限不足\"}");}))
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class).build();
    }
}
