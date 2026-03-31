package com.example.auctions.service;

import com.example.auctions.model.Auction;
import com.example.auctions.model.Transaction;
import com.example.auctions.model.User;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.UnsupportedEncodingException;

@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    
    @Value("${spring.mail.username}")
    private String fromEmail;

    @Autowired
    public EmailService(JavaMailSender mailSender, TemplateEngine templateEngine) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
    }

    private String getFromEmail() {
        return fromEmail != null ? fromEmail.trim() : null;
    }

    @Async
    public void sendAuctionWonEmail(Auction auction) throws MessagingException {
        // Disabled per user request
        logger.info("sendAuctionWonEmail called but disabled. Auction ID: {}", auction.getId());
    }

    @Async
    public void sendInvoiceEmail(Transaction transaction, Auction auction) {
        // Disabled per user request
        logger.info("sendInvoiceEmail called but disabled. Transaction ID: {}", transaction.getId());
    }

    @Async
    public void sendPaymentNotificationToSeller(Transaction transaction, Auction auction) {
        // Disabled per user request
        logger.info("sendPaymentNotificationToSeller called but disabled. Transaction ID: {}", transaction.getId());
    }

    @Async
    public void sendVerificationEmail(User user, String verificationCode) {
        try {
            logger.info("Starting email verification process for: {}", user.getEmail());
            String trimmedFrom = getFromEmail();
            
            Context context = new Context();
            context.setVariable("user", user);
            context.setVariable("verificationCode", verificationCode);

            String emailContent = templateEngine.process("email/verification", context);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setTo(user.getEmail());
            try {
                helper.setFrom(new InternetAddress(trimmedFrom, "Auctions System"));
            } catch (UnsupportedEncodingException e) {
                helper.setFrom(trimmedFrom);
            }
            helper.setSubject("Please verify your account - Auctions System");
            helper.setText(emailContent, true);

            mailSender.send(message);
            logger.info("SUCCESS: Verification email sent to: {}", user.getEmail());
            
        } catch (Exception e) {
            logger.error("ERROR: Failed to send verification email to: {}", user.getEmail(), e);
        }
    }

    @Async
    public void sendPasswordResetEmail(User user, String resetUrl) {
        try {
            logger.info("Sending password reset email to: {}", user.getEmail());
            String trimmedFrom = getFromEmail();
            // Extract values before async thread starts (avoid Hibernate lazy loading issues)
            String fullName = user.getFullName();
            String toEmail = user.getEmail();

            Context context = new Context();
            context.setVariable("fullName", fullName);
            context.setVariable("resetUrl", resetUrl);

            String emailContent = templateEngine.process("email/reset-password", context);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setTo(toEmail);
            try {
                helper.setFrom(new InternetAddress(trimmedFrom, "Auctions System"));
            } catch (UnsupportedEncodingException e) {
                helper.setFrom(trimmedFrom);
            }
            helper.setSubject("Reset your password - Auctions System");
            helper.setText(emailContent, true);

            mailSender.send(message);
            logger.info("SUCCESS: Password reset email sent to: {}", toEmail);

        } catch (Exception e) {
            logger.error("ERROR: Failed to send password reset email to: {}", user.getEmail(), e);
        }
    }
} 