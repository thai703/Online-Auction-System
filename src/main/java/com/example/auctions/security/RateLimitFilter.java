package com.example.auctions.security;

import com.example.auctions.model.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate limiting with two strategies:
 * - Authenticated users: limit by User ID (fair when sharing WiFi)
 * - Unauthenticated requests: limit by IP (higher limits for shared networks)
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final long WINDOW_MS = 60_000; // 1 minute

    // Authenticated: limit per user — strict, fair
    private static final List<RateLimitRule> USER_RULES = List.of(
            new RateLimitRule("/bids/place", "POST", 20),
            new RateLimitRule("/wallet/top-up", "POST", 10),
            new RateLimitRule("/transactions/pay", "POST", 5),
            new RateLimitRule("/transactions/create", "POST", 5),
            new RateLimitRule("/auctions/", "POST", 15),
            new RateLimitRule("/profile", "POST", 10)
    );
    private static final int USER_DEFAULT_MAX = 60;

    // Unauthenticated: limit per IP — higher limits for shared networks
    private static final List<RateLimitRule> IP_RULES = List.of(
            new RateLimitRule("/login", "POST", 30),
            new RateLimitRule("/api/auth/login", "POST", 30),
            new RateLimitRule("/register", "POST", 20),
            new RateLimitRule("/register/verify", "POST", 30),
            new RateLimitRule("/register/resend", "POST", 10),
            new RateLimitRule("/forgot-password", "POST", 20),
            new RateLimitRule("/reset-password", "POST", 20)
    );
    private static final int IP_DEFAULT_MAX = 120;

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // Determine identity and limits
        String identity;
        int maxRequests;

        Long userId = getAuthenticatedUserId();
        if (userId != null) {
            // Authenticated: rate limit by user ID
            identity = "user:" + userId;
            maxRequests = getMaxRequests(path, method, USER_RULES, USER_DEFAULT_MAX);
        } else {
            // Unauthenticated: rate limit by IP with higher limits
            identity = "ip:" + getClientIp(request);
            maxRequests = getMaxRequests(path, method, IP_RULES, IP_DEFAULT_MAX);
        }

        String key = identity + ":" + method + ":" + path;
        WindowCounter counter = counters.compute(key, (k, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null || now - existing.windowStart > WINDOW_MS) {
                return new WindowCounter(now);
            }
            return existing;
        });

        int count = counter.count.incrementAndGet();

        if (count > maxRequests) {
            logger.warn("Rate limit exceeded: {} on {} {} ({}/{})",
                    identity, method, path, count, maxRequests);
            response.setStatus(429);
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("<h3>Too many requests. Please try again later.</h3>");
            return;
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/css/") || path.startsWith("/js/")
                || path.startsWith("/images/") || path.startsWith("/webjars/");
    }

    private Long getAuthenticatedUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof User user) {
            return user.getId();
        }
        return null;
    }

    private int getMaxRequests(String path, String method, List<RateLimitRule> rules, int defaultMax) {
        for (RateLimitRule rule : rules) {
            if (path.startsWith(rule.pathPrefix) && method.equalsIgnoreCase(rule.method)) {
                return rule.maxRequests;
            }
        }
        return defaultMax;
    }

    private String getClientIp(HttpServletRequest request) {
        // Only trust X-Forwarded-For when behind a reverse proxy.
        // In production, configure server.forward-headers-strategy=framework
        // and use request.getRemoteAddr() which Spring will resolve correctly.
        return request.getRemoteAddr();
    }

    private static class WindowCounter {
        final long windowStart;
        final AtomicInteger count;

        WindowCounter(long windowStart) {
            this.windowStart = windowStart;
            this.count = new AtomicInteger(0);
        }
    }

    private record RateLimitRule(String pathPrefix, String method, int maxRequests) {}
}
