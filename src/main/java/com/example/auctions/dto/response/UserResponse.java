package com.example.auctions.dto.response;

import com.example.auctions.model.User;

import java.math.BigDecimal;

public record UserResponse(
        Long id,
        String fullName,
        String email,
        String phoneNumber,
        String role,
        BigDecimal balance,
        boolean enabled
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getPhoneNumber(),
                user.getRole() != null ? user.getRole().name() : null,
                user.getBalance(),
                user.isEnabled()
        );
    }
}
