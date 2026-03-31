package com.example.auctions.security;

import com.example.auctions.model.Auction;
import com.example.auctions.model.AuctionVisibility;
import com.example.auctions.model.User;
import com.example.auctions.model.UserRole;
import com.example.auctions.repository.AuctionRepository;
import com.example.auctions.repository.PrivateAuctionAccessRepository;
import com.example.auctions.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketAuthInterceptor.class);
    private static final Pattern AUCTION_TOPIC_PATTERN = Pattern.compile("^/topic/auction/(\\d+)$");

    private final AuctionRepository auctionRepository;
    private final PrivateAuctionAccessRepository privateAuctionAccessRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserService userService;

    public WebSocketAuthInterceptor(AuctionRepository auctionRepository,
                                    PrivateAuctionAccessRepository privateAuctionAccessRepository,
                                    JwtTokenProvider jwtTokenProvider,
                                    UserService userService) {
        this.auctionRepository = auctionRepository;
        this.privateAuctionAccessRepository = privateAuctionAccessRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userService = userService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (accessor.getCommand() == StompCommand.CONNECT) {
            authenticateFromJwtHeader(accessor);
            return message;
        }

        if (accessor.getCommand() != StompCommand.SUBSCRIBE) {
            return message;
        }

        String destination = accessor.getDestination();
        if (destination == null) {
            return message;
        }

        Matcher matcher = AUCTION_TOPIC_PATTERN.matcher(destination);
        if (!matcher.matches()) {
            return message;
        }

        Long auctionId;
        try {
            auctionId = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null; // block malformed auction ID
        }

        Auction auction = auctionRepository.findById(auctionId).orElse(null);
        if (auction == null) {
            return null; // block subscription to non-existent auction
        }

        // Public auctions: allow all authenticated users
        if (auction.getVisibility() != AuctionVisibility.PRIVATE) {
            return message;
        }

        // Private auction: check user access
        User user = extractUser(accessor);
        if (user == null) {
            logger.warn("Unauthenticated WebSocket subscription attempt to private auction {}", auctionId);
            return null;
        }

        // Admin and seller always have access
        if (user.getRole() == UserRole.ADMIN || auction.getSeller().getId().equals(user.getId())) {
            return message;
        }

        // Check session attribute for unlock status
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null) {
            String sessionKey = "auction-private-access:" + auctionId;
            if (Boolean.TRUE.equals(sessionAttributes.get(sessionKey))) {
                return message;
            }
        }

        if (privateAuctionAccessRepository.existsByUserIdAndAuctionId(user.getId(), auctionId)) {
            return message;
        }

        logger.warn("User {} denied WebSocket subscription to private auction {} (not unlocked)",
                user.getId(), auctionId);
        return null; // block subscription
    }

    private void authenticateFromJwtHeader(StompHeaderAccessor accessor) {
        if (accessor.getUser() != null) {
            return;
        }

        String bearerHeader = resolveAuthorizationHeader(accessor);
        if (!StringUtils.hasText(bearerHeader) || !bearerHeader.startsWith("Bearer ")) {
            return;
        }

        String token = bearerHeader.substring(7).trim();
        if (!StringUtils.hasText(token) || !jwtTokenProvider.validateToken(token)) {
            logger.warn("Rejected WebSocket CONNECT with invalid JWT");
            return;
        }

        String username = jwtTokenProvider.getUsernameFromToken(token);
        UserDetails userDetails = userService.loadUserByUsername(username);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        accessor.setUser(authentication);
    }

    private String resolveAuthorizationHeader(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (!StringUtils.hasText(header)) {
            header = accessor.getFirstNativeHeader("authorization");
        }
        return header;
    }

    private User extractUser(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof UsernamePasswordAuthenticationToken auth) {
            if (auth.getPrincipal() instanceof User user) {
                return user;
            }
        }
        return null;
    }
}
