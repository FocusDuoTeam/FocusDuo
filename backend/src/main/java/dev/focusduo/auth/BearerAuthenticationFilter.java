package dev.focusduo.auth;

import dev.focusduo.api.ApiErrors;
import dev.focusduo.api.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class BearerAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(BearerAuthenticationFilter.class);
    private final AuthService auth;
    private final ApiErrors errors;
    public BearerAuthenticationFilter(AuthService auth, ApiErrors errors) { this.auth = auth; this.errors = errors; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> headers = Collections.list(request.getHeaders("Authorization"));
        if (!headers.isEmpty()) {
            try {
                if (headers.size() != 1) throw ApiException.unauthorized();
                String header = headers.getFirst();
                if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) throw ApiException.unauthorized();
                AuthPrincipal principal = auth.authenticate(header.substring(7));
                var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (ApiException exception) {
                errors.write(request, response, exception);
                return;
            } catch (RuntimeException exception) {
                // Authentication executes outside MVC exception advice; keep the same safe error envelope.
                log.error("Authentication failure exceptionType={}", exception.getClass().getName());
                errors.write(request, response,
                        new ApiException(500, "INTERNAL_ERROR", "An unexpected server error occurred"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
