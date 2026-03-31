package com.example.auctions.controller;

import com.example.auctions.dto.ChangePasswordRequest;
import com.example.auctions.dto.ProfileUpdateRequest;
import com.example.auctions.dto.RegisterRequest;
import com.example.auctions.model.User;
import com.example.auctions.model.UserRole;
import com.example.auctions.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller
@RequiredArgsConstructor
public class UserController {
    private static final Logger logger = LoggerFactory.getLogger(UserController.class);
    private final UserService userService;

    @org.springframework.beans.factory.annotation.Value("${auction.registration.verification.enabled}")
    private boolean verificationEnabled;

    @Value("${spring.security.oauth2.client.registration.google.client-id:}")
    private String googleClientId;

    @Value("${spring.security.oauth2.client.registration.google.client-secret:}")
    private String googleClientSecret;


    @GetMapping("/register")
    public String showRegistrationForm(Model model) {
        model.addAttribute("registerRequest", new RegisterRequest());
        return "register";
    }

    @PostMapping("/register")
    public String registerUser(@Valid @ModelAttribute("registerRequest") RegisterRequest registerRequest, BindingResult result) {
        if (result.hasErrors()) {
            return "register";
        }

        // Server-side whitelist: only BUYER and SELLER allowed
        if (registerRequest.getRole() != UserRole.BUYER && registerRequest.getRole() != UserRole.SELLER) {
            result.rejectValue("role", "error.registerRequest", "Invalid role. Only BUYER or SELLER allowed.");
            return "register";
        }

        try {
            userService.validateStrongPassword(registerRequest.getPassword());
        } catch (RuntimeException e) {
            result.rejectValue("password", "error.registerRequest", e.getMessage());
            return "register";
        }

        try {
            userService.registerUser(registerRequest);
            if (verificationEnabled) {
                logger.info("Registration successful, redirecting to verify page for email: {}", registerRequest.getEmail());
                return "redirect:/register/verify?email=" + java.net.URLEncoder.encode(registerRequest.getEmail(), java.nio.charset.StandardCharsets.UTF_8);
            }
            return "redirect:/login?registered";
        } catch (RuntimeException e) {
            logger.error("Registration error: {}", e.getMessage(), e);
            if (verificationEnabled && "Email already registered".equals(e.getMessage())) {
                var existingUser = userService.findByEmail(registerRequest.getEmail()).orElse(null);
                if (existingUser != null && !existingUser.isEnabled() && existingUser.getVerificationToken() != null) {
                    logger.info("Existing unverified account found, redirecting to verify page for email: {}", registerRequest.getEmail());
                    return "redirect:/register/verify?email=" + java.net.URLEncoder.encode(registerRequest.getEmail(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            result.rejectValue("email", "error.registerRequest", e.getMessage());
            return "register";
        }
    }

    @GetMapping("/register/verify")
    public String showVerifyForm(@RequestParam("email") String email, Model model) {
        User user = userService.findByEmail(email).orElse(null);
        if (userService.isAdminDisabled(user)) {
            return "redirect:/login?disabled=true";
        }
        model.addAttribute("email", email);
        return "verify-code";
    }

    @PostMapping("/register/verify")
    public String verifyCode(@RequestParam("email") String email, @RequestParam("code") String code, Model model) {
        try {
            boolean verified = userService.verifyUser(email, code);
            if (verified) {
                return "redirect:/login?verified";
            } else {
                model.addAttribute("email", email);
                model.addAttribute("verifyError", "Invalid or expired verification code.");
                return "verify-code";
            }
        } catch (RuntimeException e) {
            model.addAttribute("email", email);
            model.addAttribute("verifyError", e.getMessage());
            return "verify-code";
        }
    }

    @PostMapping("/register/resend")
    public String resendCode(@RequestParam("email") String email, Model model) {
        try {
            userService.resendVerificationCode(email);
            model.addAttribute("email", email);
            model.addAttribute("resent", "A new 5-digit verification code has been sent to your email.");
            return "verify-code";
        } catch (RuntimeException e) {
            model.addAttribute("email", email);
            model.addAttribute("verifyError", e.getMessage());
            return "verify-code";
        }
    }

    @GetMapping("/forgot-password")
    public String showForgotPasswordForm() {
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    public String processForgotPassword(@RequestParam("email") String email, Model model) {
        try {
            userService.generatePasswordResetToken(email);
            model.addAttribute("forgotSuccess", "If an account exists with that email, a password reset link has been sent. Please check your inbox (and spam folder).");
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("wait")) {
                model.addAttribute("forgotError", e.getMessage());
            } else {
                logger.error("Error generating password reset token for email: {}", email, e);
                model.addAttribute("forgotSuccess", "If an account exists with that email, a password reset link has been sent. Please check your inbox (and spam folder).");
            }
        }
        return "forgot-password";
    }

    @GetMapping("/reset-password")
    public String showResetPasswordForm(@RequestParam("token") String token, Model model) {
        // Validate token before showing the form
        boolean valid = userService.isValidResetToken(token);
        if (valid) {
            model.addAttribute("token", token);
        } else {
            model.addAttribute("resetError", "This password reset link is invalid or has expired.");
        }
        return "reset-password";
    }

    @PostMapping("/reset-password")
    public String processResetPassword(@RequestParam("token") String token,
                                       @RequestParam("password") String password,
                                       Model model) {
        try {
            userService.validateStrongPassword(password);
        } catch (RuntimeException e) {
            model.addAttribute("token", token);
            model.addAttribute("resetError", e.getMessage());
            return "reset-password";
        }

        boolean success = userService.resetPassword(token, password);
        if (success) {
            return "redirect:/login?passwordReset";
        } else {
            model.addAttribute("token", token);
            model.addAttribute("resetError", "This password reset link is invalid or has expired.");
            return "reset-password";
        }
    }

    @GetMapping("/login")
    public String showLoginForm(Authentication authentication, Model model) {
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof User) {
            return "redirect:/dashboard";
        }
        model.addAttribute("googleLoginEnabled", isGoogleLoginEnabled());
        return "login";
    }

    @GetMapping("/profile")
    public String showProfile(@AuthenticationPrincipal User currentUser, Model model) {
        User user = userService.getCurrentUser(currentUser.getEmail());
        model.addAttribute("user", user);
        ProfileUpdateRequest profileForm = new ProfileUpdateRequest();
        profileForm.setFullName(user.getFullName());
        profileForm.setPhoneNumber(user.getPhoneNumber());
        model.addAttribute("profileForm", profileForm);
        model.addAttribute("changePasswordForm", new ChangePasswordRequest());
        return "profile";
    }

    @PostMapping("/profile")
    public String updateProfile(@AuthenticationPrincipal User currentUser,
                                @Valid @ModelAttribute("profileForm") ProfileUpdateRequest form,
                                BindingResult result,
                                Model model) {
        if (result.hasErrors()) {
            User user = userService.getCurrentUser(currentUser.getEmail());
            model.addAttribute("user", user);
            model.addAttribute("changePasswordForm", new ChangePasswordRequest());
            return "profile";
        }

        userService.updateProfile(currentUser.getId(), form);
        return "redirect:/profile?updated";
    }

    @PostMapping("/profile/password")
    public String changePassword(@AuthenticationPrincipal User currentUser,
                                 @Valid @ModelAttribute("changePasswordForm") ChangePasswordRequest form,
                                 BindingResult result,
                                 Model model) {
        if (result.hasErrors()) {
            User user = userService.getCurrentUser(currentUser.getEmail());
            model.addAttribute("user", user);
            model.addAttribute("profileForm", new ProfileUpdateRequest());
            return "profile";
        }

        try {
            userService.changePassword(currentUser.getId(), form.getCurrentPassword(), form.getNewPassword());
            return "redirect:/profile?passwordChanged";
        } catch (RuntimeException e) {
            User user = userService.getCurrentUser(currentUser.getEmail());
            model.addAttribute("user", user);
            model.addAttribute("profileForm", new ProfileUpdateRequest());
            model.addAttribute("passwordError", e.getMessage());
            return "profile";
        }
    }

    private boolean isGoogleLoginEnabled() {
        return googleClientId != null && !googleClientId.isBlank()
                && googleClientSecret != null && !googleClientSecret.isBlank();
    }
}
