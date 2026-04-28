package com.wallet.infrastructure.web;

import com.wallet.infrastructure.log.LogSanitizer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import com.wallet.infrastructure.id.UuidV7Generator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdMdcFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestIdMdcFilter.class);

    public static final String MDC_KEY = "requestId";
    public static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UuidV7Generator.next().toString();
        }
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        if (isMutatingWalletPath(request) && log.isDebugEnabled()) {
            String ikh = request.getHeader("Idempotency-Key");
            log.debug(
                "{} {} idempotencyKey={}",
                request.getMethod(),
                request.getRequestURI(),
                ikh == null || ikh.isBlank() ? "none" : LogSanitizer.shortHash(ikh)
            );
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static boolean isMutatingWalletPath(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        return path != null && path.startsWith("/users");
    }
}
