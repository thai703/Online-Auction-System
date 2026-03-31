package com.example.auctions.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DataMigrationConfig {
    private static final Logger logger = LoggerFactory.getLogger(DataMigrationConfig.class);

    @Bean
    public CommandLineRunner migrateExistingUsers(JdbcTemplate jdbcTemplate) {
        return args -> {
            logger.info("Checking for users with null enabled flag to migrate...");
            
            // Only backfill missing values so manual admin disable actions are preserved.
            int updated = jdbcTemplate.update(
                "UPDATE users SET enabled = 1 WHERE enabled IS NULL"
            );
            
            if (updated > 0) {
                logger.info("Successfully backfilled enabled flag for {} existing accounts.", updated);
            } else {
                logger.info("No accounts needed migration.");
            }
        };
    }
}
