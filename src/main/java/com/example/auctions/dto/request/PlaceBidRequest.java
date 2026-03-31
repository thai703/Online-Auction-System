package com.example.auctions.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PlaceBidRequest(
        @NotNull Long auctionId,
        @NotNull @DecimalMin("0") BigDecimal amount
) {
}
