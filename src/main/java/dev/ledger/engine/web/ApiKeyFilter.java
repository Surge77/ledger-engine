package dev.ledger.engine.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledger.engine.config.LedgerProperties;
import dev.ledger.engine.dto.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects any request to a protected path without a valid X-Api-Key. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Api-Key";

    private final byte[] expectedKey;
    private final ObjectMapper objectMapper;

    public ApiKeyFilter(LedgerProperties properties, ObjectMapper objectMapper) {
        this.expectedKey = properties.apiKey().getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only liveness checks and the API docs are public. Everything else under
        // /actuator (metrics, prometheus, info) requires the key — an exact match,
        // never a prefix, so a newly exposed actuator endpoint can't silently go
        // public. The OpenAPI doc + Swagger UI expose only the API shape (which
        // documents the X-Api-Key requirement), never ledger data.
        String path = request.getRequestURI();
        return path.equals("/health")
                || path.equals("/actuator/health")
                || path.equals("/v3/api-docs")
                || path.startsWith("/v3/api-docs/")
                || path.startsWith("/swagger-ui");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8), expectedKey)) {
            writeUnauthorized(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "ApiKey");
        objectMapper.writeValue(response.getOutputStream(),
                ApiError.of("UNAUTHORIZED", "missing or invalid API key"));
    }
}
