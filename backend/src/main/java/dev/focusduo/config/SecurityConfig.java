package dev.focusduo.config;

import dev.focusduo.api.ApiErrors;
import dev.focusduo.api.ApiException;
import dev.focusduo.auth.AuthService;
import dev.focusduo.auth.BearerAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, AuthService auth, ApiErrors errors) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(s -> s.securityContextRepository(new NullSecurityContextRepository()))
                .authorizeHttpRequests(r -> r
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, exception) -> errors.write(request, response, ApiException.unauthorized()))
                        .accessDeniedHandler((request, response, exception) -> errors.write(request, response, ApiException.forbidden())))
                .addFilterBefore(new BearerAuthenticationFilter(auth, errors), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
