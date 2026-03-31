package com.example.auctions.dto;

import java.io.Serializable;

public record GoogleOAuthPendingProfile(
        String email,
        String fullName,
        String googleId
) implements Serializable {
    public static final String SESSION_ATTRIBUTE = "google-oauth-pending-profile";
}
