package com.example.auctions.dto;

import com.example.auctions.model.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class OAuthOnboardingForm {

    @NotBlank(message = "Full name is required")
    @Size(max = 255, message = "Full name must be 255 characters or fewer")
    private String fullName;

    @NotBlank(message = "Phone number is required")
    @Size(max = 30, message = "Phone number must be 30 characters or fewer")
    private String phoneNumber;

    @NotNull(message = "Please choose a role")
    private UserRole role;
}
