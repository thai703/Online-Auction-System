package com.example.auctions.config;

import com.example.auctions.model.User;
import com.example.auctions.model.UserRole;
import com.example.auctions.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

@Configuration
public class AdminBootstrapConfig {
    private static final Logger logger = LoggerFactory.getLogger(AdminBootstrapConfig.class);

    @Bean
    public CommandLineRunner bootstrapAdminAccount(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            DataSource dataSource,
            JdbcTemplate jdbcTemplate,
            @Value("${auction.admin.bootstrap.enabled:false}") boolean bootstrapEnabled,
            @Value("${auction.admin.bootstrap.email:}") String adminEmail,
            @Value("${auction.admin.bootstrap.password:}") String adminPassword,
            @Value("${auction.admin.bootstrap.full-name:System Administrator}") String adminFullName,
            @Value("${auction.admin.bootstrap.phone:0000000000}") String adminPhone) {
        return args -> {
            if (!bootstrapEnabled || adminEmail.isBlank() || adminPassword.isBlank()) {
                logger.info("Admin bootstrap is disabled or incomplete. Skipping bootstrap.");
                return;
            }

            ensureRoleColumnSupportsAdmin(dataSource, jdbcTemplate);

            userRepository.findByEmail(adminEmail).ifPresentOrElse(existingAdmin -> {
                boolean changed = false;

                if (existingAdmin.getRole() != UserRole.ADMIN) {
                    existingAdmin.setRole(UserRole.ADMIN);
                    changed = true;
                }

                if (!existingAdmin.isEnabled()) {
                    existingAdmin.setEnabled(true);
                    changed = true;
                }

                if (changed) {
                    userRepository.save(existingAdmin);
                    logger.info("Updated existing account {} to ADMIN.", adminEmail);
                } else {
                    logger.info("Admin account {} already exists.", adminEmail);
                }
            }, () -> {
                User admin = new User();
                admin.setFullName(adminFullName);
                admin.setEmail(adminEmail);
                admin.setPassword(passwordEncoder.encode(adminPassword));
                admin.setPhoneNumber(adminPhone);
                admin.setRole(UserRole.ADMIN);
                admin.setEnabled(true);
                userRepository.save(admin);
                logger.info("Bootstrapped default admin account {}.", adminEmail);
            });
        };
    }

    private void ensureRoleColumnSupportsAdmin(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String databaseName = metaData.getDatabaseProductName();

            if (databaseName == null) {
                return;
            }

            String normalizedDatabaseName = databaseName.toLowerCase();
            if (normalizedDatabaseName.contains("mysql") || normalizedDatabaseName.contains("mariadb")) {
                jdbcTemplate.execute("ALTER TABLE users MODIFY COLUMN role VARCHAR(255) NOT NULL");
                logger.info("Ensured users.role column supports ADMIN values on {}.", databaseName);
            }
        } catch (Exception ex) {
            logger.warn("Could not normalize users.role column before admin bootstrap: {}", ex.getMessage());
        }
    }
}
