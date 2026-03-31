package com.example.auctions.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "wallet_transactions", indexes = {
    @Index(name = "idx_wallet_tx_user_created", columnList = "user_id, created_at")
})
public class WalletTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WalletTransactionType type;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_before", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(nullable = false)
    private String description;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "related_transaction_id")
    private Long relatedTransactionId;

    @Column(name = "related_auction_id")
    private Long relatedAuctionId;

    @Column(name = "user_name_snapshot")
    private String userNameSnapshot;

    @Column(name = "user_email_snapshot")
    private String userEmailSnapshot;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
