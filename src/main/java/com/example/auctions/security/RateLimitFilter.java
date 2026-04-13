package com.example.auctions.security;

import com.example.auctions.model.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rate limiting with two strategies:
 * - Authenticated users: limit by User ID (fair when sharing WiFi)
 * - Unauthenticated requests: limit by IP (higher limits for shared networks)
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final long WINDOW_MS = 60_000; // 1 minute

    // Normalize dynamic path segments: /auctions/detail/123 → /auctions/detail/{id}
    private static final Pattern NUMERIC_SEGMENT = Pattern.compile("/\\d+");

    // Authenticated: limit per user — strict, fair
    private static final List<RateLimitRule> USER_RULES = List.of(
            new RateLimitRule("/bids/place", "POST", 20),
            new RateLimitRule("/wallet/top-up", "POST", 10),
            new RateLimitRule("/transactions/pay", "POST", 5),
            new RateLimitRule("/transactions/create", "POST", 5),
            new RateLimitRule("/auctions/", "POST", 15),
            new RateLimitRule("/profile", "POST", 10));
    private static final int USER_DEFAULT_MAX = 100;

    // Unauthenticated: limit per IP — higher limits for shared networks
    private static final List<RateLimitRule> IP_RULES = List.of(
            new RateLimitRule("/login", "POST", 100),
            new RateLimitRule("/api/auth/login", "POST", 100),
            new RateLimitRule("/register", "POST", 100),
            new RateLimitRule("/register/verify", "POST", 50),
            new RateLimitRule("/register/resend", "POST", 50),
            new RateLimitRule("/forgot-password", "POST", 50),
            new RateLimitRule("/reset-password", "POST", 50));
    private static final int IP_DEFAULT_MAX = 120;

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // Normalize dynamic segments so /auctions/detail/1 and /auctions/detail/2 share
        // one bucket
        String normalizedPath = normalizePath(path);

        // Determine identity and limits
        String identity;
        int maxRequests;

        Long userId = getAuthenticatedUserId();
        if (userId != null) {
            identity = "user:" + userId;
            maxRequests = getMaxRequests(normalizedPath, method, USER_RULES, USER_DEFAULT_MAX);
        } else {
            identity = "ip:" + getClientIp(request);
            maxRequests = getMaxRequests(normalizedPath, method, IP_RULES, IP_DEFAULT_MAX);
        }

        String key = identity + ":" + method + ":" + normalizedPath;
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
                    identity, method, normalizedPath, count, maxRequests);
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter()
                    .write("{\"success\":false,\"message\":\"Too many requests. Please try again later.\"}");
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

    /** Cleanup expired counters every 5 minutes to prevent memory leak */
    @Scheduled(fixedRate = 300_000)
    public void cleanupExpiredCounters() {
        long now = System.currentTimeMillis();
        int before = counters.size();
        counters.entrySet().removeIf(entry -> now - entry.getValue().windowStart > WINDOW_MS * 2);
        int removed = before - counters.size();
        if (removed > 0) {
            logger.debug("Rate limit cleanup: removed {} expired entries, {} remaining", removed, counters.size());
        }
    }

    private String normalizePath(String path) {
        // Replace numeric path segments: /auctions/detail/123 → /auctions/detail/{id}
        Matcher matcher = NUMERIC_SEGMENT.matcher(path);
        return matcher.replaceAll("/{id}");
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
        // Trust X-Forwarded-For from reverse proxy (Nginx/Cloudflare)
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For: client, proxy1, proxy2 → take first (real client IP)
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
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

    private record RateLimitRule(String pathPrefix, String method, int maxRequests) {
    }
}
