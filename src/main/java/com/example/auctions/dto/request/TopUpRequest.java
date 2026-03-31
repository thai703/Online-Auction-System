package com.example.auctions.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record TopUpRequest(
        @NotNull @DecimalMin(value = "1000", message = "Minimum top-up is 1,000 VNĐ") BigDecimal amount
) {
}
