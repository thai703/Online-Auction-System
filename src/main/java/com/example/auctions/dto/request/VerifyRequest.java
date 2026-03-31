package com.example.auctions.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record VerifyRequest(
        @Email @NotBlank String email,
        @NotBlank String code
) {
}
