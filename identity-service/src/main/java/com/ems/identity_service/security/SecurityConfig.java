package com.ems.identity_service.security;

import com.ems.common.error.ErrorResponseWriter;
import com.ems.common.security.GatewayAuthenticationFilter;
import com.ems.identity_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> userRepository
                .findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService());
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.cors(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                                (request, response, authException) -> ErrorResponseWriter.write(
                                        objectMapper,
                                        request,
                                        response,
                                        HttpStatus.UNAUTHORIZED,
                                        "Unauthorized",
                                        "Authentication is required to access this resource"))
                        .accessDeniedHandler((request, response, accessDeniedException) -> ErrorResponseWriter.write(
                                objectMapper,
                                request,
                                response,
                                HttpStatus.FORBIDDEN,
                                "Forbidden",
                                "You do not have permission to access this resource")))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/auth/validate",
                                "/api/auth/refresh",
                                // Both are reached from a mail client or from the sign-in
                                // page, neither of which has a token to present. The
                                // verification token in the query string is the credential.
                                "/api/auth/verify",
                                "/api/auth/resend-verification",
                                // Password reset. Someone who cannot sign in is exactly who
                                // needs these, so requiring a token would make them useless;
                                // the emailed reset token is the credential for the second.
                                "/api/auth/forgot-password",
                                "/api/auth/reset-password",
                                "/.well-known/jwks.json")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(authenticationProvider())
                // Authentication is whatever the gateway already established and forwarded as
                // headers: no token parsing and no user lookup on the request path. Constructed
                // here rather than declared as a @Bean, which would also register it with the
                // servlet container and run it a second time outside this chain.
                .addFilterBefore(new GatewayAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
