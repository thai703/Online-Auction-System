package com.example.auctions.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UnlockAuctionRequest(
        @NotBlank String accessCode
) {
}
