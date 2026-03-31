package com.example.auctions.service;

import com.example.auctions.dto.ChangePasswordRequest;
import com.example.auctions.dto.GoogleOAuthPendingProfile;
import com.example.auctions.dto.OAuthOnboardingForm;
import com.example.auctions.dto.ProfileUpdateRequest;
import com.example.auctions.dto.RegisterRequest;
import com.example.auctions.model.User;
import com.example.auctions.model.UserRole;
import com.example.auctions.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class UserService implements UserDetailsService {
    private static final Logger logger = LoggerFactory.getLogger(UserService.class);
    private static final Pattern STRONG_PASSWORD_PATTERN = Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    @Value("${auction.registration.verification.enabled}")
    private boolean verificationEnabled;

    @Value("${auction.server.url}")
    private String serverUrl;

    @Autowired
    public UserService(UserRepository userRepository, 
                       PasswordEncoder passwordEncoder,
                       EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));
    }

    @Transactional
    public User registerUser(RegisterRequest req) {
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new RuntimeException("Email already registered");
        }

        // Server-side role whitelist - never trust client
        if (req.getRole() != UserRole.BUYER && req.getRole() != UserRole.SELLER) {
            throw new RuntimeException("Invalid role");
        }

        validateStrongPassword(req.getPassword());

        User user = new User();
        user.setFullName(req.getFullName().trim());
        user.setEmail(req.getEmail().trim().toLowerCase());
        user.setPhoneNumber(req.getPhoneNumber().trim());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setRole(req.getRole());
        user.setBalance(BigDecimal.ZERO);

        if (verificationEnabled) {
            user.setEnabled(false);
            String token = String.format("%05d", new java.security.SecureRandom().nextInt(100000));
            user.setVerificationToken(token);
            user.setVerificationTokenExpiry(java.time.LocalDateTime.now().plusMinutes(10));
            user.setVerificationAttempts(0);

            User savedUser = userRepository.save(user);
            emailService.sendVerificationEmail(savedUser, token);
            return savedUser;
        } else {
            user.setEnabled(true);
            return userRepository.save(user);
        }
    }

    public java.util.Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public java.util.Optional<User> findByGoogleId(String googleId) {
        return userRepository.findByGoogleId(googleId);
    }

    public boolean isProfileComplete(User user) {
        return user != null
                && user.getRole() != null
                && user.getFullName() != null
                && !user.getFullName().isBlank()
                && user.getPhoneNumber() != null
                && !user.getPhoneNumber().isBlank();
    }

    public boolean isBlockedFromGoogleLogin(User user) {
        return isAdminDisabled(user);
    }

    public boolean requiresEmailVerification(User user) {
        return user != null && !user.isEnabled() && user.getVerificationToken() != null;
    }

    public boolean isAdminDisabled(User user) {
        return user != null && !user.isEnabled() && user.getVerificationToken() == null;
    }

    @Transactional
    public User linkGoogleAccount(User user, String googleId, String fallbackFullName) {
        if (user == null) {
            throw new RuntimeException("User not found");
        }

        if (googleId != null && !googleId.isBlank()) {
            user.setGoogleId(googleId);
        }

        if ((user.getFullName() == null || user.getFullName().isBlank())
                && fallbackFullName != null && !fallbackFullName.isBlank()) {
            user.setFullName(fallbackFullName.trim());
        }

        if (!user.isEnabled() && user.getVerificationToken() != null) {
            user.setEnabled(true);
            user.setVerificationToken(null);
        }

        return userRepository.save(user);
    }

    @Transactional
    public User completeGoogleOnboarding(GoogleOAuthPendingProfile pendingProfile, OAuthOnboardingForm onboardingForm) {
        User user = userRepository.findByGoogleId(pendingProfile.googleId())
                .or(() -> userRepository.findByEmail(pendingProfile.email()))
                .orElseGet(User::new);

        user.setEmail(pendingProfile.email());
        user.setGoogleId(pendingProfile.googleId());
        user.setFullName(onboardingForm.getFullName().trim());
        user.setPhoneNumber(onboardingForm.getPhoneNumber().trim());
        user.setRole(onboardingForm.getRole());
        user.setEnabled(true);
        user.setVerificationToken(null);

        if (user.getPassword() == null || user.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        }

        if (user.getBalance() == null) {
            user.setBalance(BigDecimal.ZERO);
        }

        return userRepository.save(user);
    }

    @Transactional
    public void resendVerificationCode(String email) {
        User user = userRepository.findByEmail(email).orElse(null);

        // Generic response to prevent email enumeration
        if (user == null || user.isEnabled()) {
            logger.info("Resend requested for email: {} (not found or already verified)", email);
            return;
        }

        if (isAdminDisabled(user)) {
            logger.warn("Resend verification blocked for admin-disabled account: {}", email);
            throw new RuntimeException("This account has been disabled by an administrator.");
        }

        if (user.getVerificationToken() == null) {
            logger.info("Resend requested for email: {} without an active verification flow", email);
            return;
        }

        // Cooldown: 1 minute between resends (token expires in 10min, block if > 9min remaining)
        if (user.getVerificationTokenExpiry() != null
                && user.getVerificationTokenExpiry().isAfter(java.time.LocalDateTime.now().plusMinutes(9))) {
            throw new RuntimeException("Please wait 1 minute before requesting a new code.");
        }

        String token = String.format("%05d", new java.security.SecureRandom().nextInt(100000));
        user.setVerificationToken(token);
        user.setVerificationTokenExpiry(java.time.LocalDateTime.now().plusMinutes(10));
        user.setVerificationAttempts(0);
        userRepository.save(user);

        emailService.sendVerificationEmail(user, token);
        logger.info("Resent verification code to: {}", email);
    }

    @Transactional
    public boolean verifyUser(String email, String token) {
        logger.info("Verifying user with email: {}", email);

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return false;
        }

        if (isAdminDisabled(user)) {
            logger.warn("Verification blocked for admin-disabled account: {}", email);
            throw new RuntimeException("This account has been disabled by an administrator.");
        }

        if (user.isEnabled() || user.getVerificationToken() == null) {
            return false;
        }

        // Check attempt limit (max 5 attempts)
        if (user.getVerificationAttempts() >= 5) {
            logger.warn("Too many verification attempts for: {}", email);
            throw new RuntimeException("Too many attempts. Please request a new code.");
        }

        // Check expiry
        if (user.getVerificationTokenExpiry() != null
                && user.getVerificationTokenExpiry().isBefore(java.time.LocalDateTime.now())) {
            logger.warn("Expired verification code for: {}", email);
            return false;
        }

        // Increment attempts
        user.setVerificationAttempts(user.getVerificationAttempts() + 1);

        if (!token.equals(user.getVerificationToken())) {
            userRepository.save(user);
            logger.warn("Invalid verification code for: {} (attempt {})", email, user.getVerificationAttempts());
            return false;
        }

        user.setEnabled(true);
        user.setVerificationToken(null);
        user.setVerificationTokenExpiry(null);
        user.setVerificationAttempts(0);
        userRepository.save(user);
        logger.info("User {} verified successfully", user.getEmail());
        return true;
    }

    @Transactional
    public boolean generatePasswordResetToken(String email) {
        return userRepository.findByEmail(email).map(user -> {
            // Cooldown: 1 minute between reset requests (token expires in 1h, block if > 59min remaining)
            if (user.getResetPasswordTokenExpiry() != null
                    && user.getResetPasswordTokenExpiry().isAfter(java.time.LocalDateTime.now().plusMinutes(59))) {
                logger.info("Password reset cooldown active for: {}", email);
                throw new RuntimeException("Please wait 1 minute before requesting another reset link.");
            }

            String token = UUID.randomUUID().toString();
            user.setResetPasswordToken(token);
            user.setResetPasswordTokenExpiry(java.time.LocalDateTime.now().plusHours(1));
            userRepository.save(user);
            String resetUrl = serverUrl + "/reset-password?token=" + token;
            emailService.sendPasswordResetEmail(user, resetUrl);
            logger.info("Password reset token generated for: {}", email);
            return true;
        }).orElseGet(() -> {
            logger.warn("Password reset requested for non-existent email: {}", email);
            return false; // Return false silently to not expose whether email exists
        });
    }

    @Transactional
    public boolean resetPassword(String token, String newPassword) {
        validateStrongPassword(newPassword);

        return userRepository.findByResetPasswordToken(token).map(user -> {
            if (user.getResetPasswordTokenExpiry() == null ||
                    user.getResetPasswordTokenExpiry().isBefore(java.time.LocalDateTime.now())) {
                logger.warn("Expired password reset token used for user: {}", user.getEmail());
                return false;
            }
            user.setPassword(passwordEncoder.encode(newPassword));
            user.setResetPasswordToken(null);
            user.setResetPasswordTokenExpiry(null);
            userRepository.save(user);
            logger.info("Password successfully reset for user: {}", user.getEmail());
            return true;
        }).orElseGet(() -> {
            logger.warn("Invalid password reset token used: {}", token);
            return false;
        });
    }

    public boolean isValidResetToken(String token) {
        return userRepository.findByResetPasswordToken(token)
                .map(user -> user.getResetPasswordTokenExpiry() != null &&
                        user.getResetPasswordTokenExpiry().isAfter(java.time.LocalDateTime.now()))
                .orElse(false);
    }

    @Transactional
    public User updateProfile(Long currentUserId, ProfileUpdateRequest form) {
        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setFullName(form.getFullName().trim());
        user.setPhoneNumber(form.getPhoneNumber().trim());
        return userRepository.save(user);
    }

    @Transactional
    public void changePassword(Long currentUserId, String currentPassword, String newPassword) {
        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new RuntimeException("Current password is incorrect");
        }
        validateStrongPassword(newPassword);
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    public User getCurrentUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    public void validateStrongPassword(String rawPassword) {
        if (rawPassword == null || !STRONG_PASSWORD_PATTERN.matcher(rawPassword).matches()) {
            throw new RuntimeException(
                    "Password must be at least 8 characters and include uppercase, lowercase, number, and special character.");
        }
    }

    @Transactional
    public User toggleUserEnabled(Long userId, Long currentAdminId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getId().equals(currentAdminId)) {
            throw new RuntimeException("You cannot disable your own admin account.");
        }

        // Protect active admin accounts from being disabled by other admins.
        if (user.getRole() == UserRole.ADMIN && user.isEnabled()) {
            throw new RuntimeException("You cannot disable another admin account.");
        }

        user.setEnabled(!user.isEnabled());
        return userRepository.save(user);
    }
}
