package com.example.auctions.controller.api;

import com.example.auctions.dto.LoginRequest;
import com.example.auctions.dto.JwtResponse;
import com.example.auctions.dto.RegisterRequest;
import com.example.auctions.dto.request.ForgotPasswordRequest;
import com.example.auctions.dto.request.ResendVerificationRequest;
import com.example.auctions.dto.request.ResetPasswordRequest;
import com.example.auctions.dto.request.VerifyRequest;
import com.example.auctions.dto.response.ApiResponse;
import com.example.auctions.model.User;
import com.example.auctions.model.UserRole;
import com.example.auctions.security.JwtTokenProvider;
import com.example.auctions.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auth")
public class AuthApiController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserService userService;

    public AuthApiController(AuthenticationManager authenticationManager,
                             JwtTokenProvider jwtTokenProvider,
                             UserService userService) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userService = userService;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<JwtResponse>> login(@Valid @RequestBody LoginRequest req) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword()));

        SecurityContextHolder.getContext().setAuthentication(authentication);
        String jwt = jwtTokenProvider.generateToken(authentication);

        User user = (User) authentication.getPrincipal();
        List<String> roles = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.ok("Login successful",
                new JwtResponse(jwt, user.getEmail(), user.getFullName(), roles)));
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Void>> register(@Valid @RequestBody RegisterRequest req) {
        if (req.getRole() != UserRole.BUYER && req.getRole() != UserRole.SELLER) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid role. Only BUYER or SELLER allowed."));
        }
        try {
            userService.validateStrongPassword(req.getPassword());
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
        try {
            userService.registerUser(req);
            return ResponseEntity.ok(ApiResponse.ok("Registration successful. Please verify your email."));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<Void>> verify(@Valid @RequestBody VerifyRequest req) {
        try {
            boolean verified = userService.verifyUser(req.email(), req.code());
            if (verified) {
                return ResponseEntity.ok(ApiResponse.ok("Account verified successfully. You can now log in."));
            }
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid or expired verification code."));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<ApiResponse<Void>> resendVerification(@Valid @RequestBody ResendVerificationRequest req) {
        try {
            userService.resendVerificationCode(req.email());
            return ResponseEntity.ok(ApiResponse.ok("A new verification code has been sent to your email."));
        } catch (RuntimeException e) {
            // Generic response to prevent email enumeration
            return ResponseEntity.ok(ApiResponse.ok("If an account exists and is unverified, a new code has been sent."));
        }
    }

    @PostMapping("/google")
    public ResponseEntity<ApiResponse<JwtResponse>> googleLogin(@RequestBody Map<String, String> body) {
        String idToken = body.get("idToken");
        if (idToken == null || idToken.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Missing Google ID token."));
        }

        try {
            // Verify the Google ID token via Google's tokeninfo endpoint
            var restTemplate = new org.springframework.web.client.RestTemplate();
            var tokenInfo = restTemplate.getForObject(
                    "https://oauth2.googleapis.com/tokeninfo?id_token=" + idToken, Map.class);

            if (tokenInfo == null || tokenInfo.get("email") == null) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Invalid Google token."));
            }

            String email = tokenInfo.get("email").toString();
            String googleId = tokenInfo.get("sub").toString();
            String displayName = tokenInfo.get("name") != null ? tokenInfo.get("name").toString() : email;

            // Find existing user by googleId or email
            Optional<User> existingUser = userService.findByGoogleId(googleId)
                    .or(() -> userService.findByEmail(email));

            User user;
            if (existingUser.isPresent()) {
                user = existingUser.get();
                if (!user.isEnabled()) {
                    return ResponseEntity.badRequest().body(ApiResponse.error("Account is disabled."));
                }
                // Link Google account if not already linked
                user = userService.linkGoogleAccount(user, googleId, displayName);

                if (!userService.isProfileComplete(user)) {
                    return ResponseEntity.badRequest().body(ApiResponse.error(
                            "Profile incomplete. Please complete onboarding on the web first."));
                }
            } else {
                // No existing account — tell user to register on web first
                return ResponseEntity.badRequest().body(ApiResponse.error(
                        "No account found. Please register on the website first using Google login."));
            }

            // Generate JWT
            String jwt = jwtTokenProvider.generateTokenForUser(user);
            List<String> roles = user.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(ApiResponse.ok("Google login successful",
                    new JwtResponse(jwt, user.getEmail(), user.getFullName(), roles)));

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Google authentication failed: " + e.getMessage()));
        }
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        try {
            userService.generatePasswordResetToken(req.email());
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("wait")) {
                return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
            }
            // Ignore other errors — don't expose email existence
        }
        return ResponseEntity.ok(ApiResponse.ok("If an account exists with that email, a reset link has been sent."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        try {
            userService.validateStrongPassword(req.newPassword());
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
        boolean success = userService.resetPassword(req.token(), req.newPassword());
        if (success) {
            return ResponseEntity.ok(ApiResponse.ok("Password reset successfully. You can now log in."));
        }
        return ResponseEntity.badRequest().body(ApiResponse.error("Invalid or expired reset token."));
    }

}
