package com.example.auctions.controller;

import com.example.auctions.model.User;
import com.example.auctions.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.math.BigDecimal;

/**
 * Injects fresh wallet balance into every page's model.
 * Solves the problem where Spring Security caches the User principal
 * in session, so balance changes (top-up, payment) are not reflected
 * until re-login.
 */
@ControllerAdvice
public class GlobalModelAdvice {

    private final UserRepository userRepository;

    @Autowired
    public GlobalModelAdvice(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @ModelAttribute("walletBalance")
    public BigDecimal walletBalance() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof User) {
            User sessionUser = (User) auth.getPrincipal();
            // Lightweight query: only fetch balance column, not full entity
            return userRepository.findBalanceById(sessionUser.getId())
                    .orElse(BigDecimal.ZERO);
        }
        return BigDecimal.ZERO;
    }
}
