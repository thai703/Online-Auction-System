package com.example.auctions.controller.api;

import com.example.auctions.dto.ChangePasswordRequest;
import com.example.auctions.dto.ProfileUpdateRequest;
import com.example.auctions.dto.response.ApiResponse;
import com.example.auctions.dto.response.UserResponse;
import com.example.auctions.model.User;
import com.example.auctions.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profile")
public class ProfileApiController {

    private final UserService userService;

    public ProfileApiController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<UserResponse>> getProfile(@AuthenticationPrincipal User currentUser) {
        User user = userService.getCurrentUser(currentUser.getEmail());
        return ResponseEntity.ok(ApiResponse.ok(UserResponse.from(user)));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody ProfileUpdateRequest form) {
        User updated = userService.updateProfile(currentUser.getId(), form);
        return ResponseEntity.ok(ApiResponse.ok("Profile updated successfully.", UserResponse.from(updated)));
    }

    @PutMapping("/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody ChangePasswordRequest form) {
        try {
            userService.validateStrongPassword(form.getNewPassword());
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
        userService.changePassword(currentUser.getId(), form.getCurrentPassword(), form.getNewPassword());
        return ResponseEntity.ok(ApiResponse.ok("Password changed successfully."));
    }
}
