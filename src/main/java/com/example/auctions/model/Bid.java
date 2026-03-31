package com.example.auctions.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "bids", indexes = {
    @Index(name = "idx_bids_auction_amount_time", columnList = "auction_id, amount, bid_time"),
    @Index(name = "idx_bids_bidder_time", columnList = "bidder_id, bid_time")
})
public class Bid {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bidder_id", nullable = false)
    private User bidder;

    @NotNull(message = "Bid amount is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Bid amount must be greater than 0")
    @Column(name = "amount", precision = 19, scale = 0)
    private BigDecimal amount;

    @Column(name = "bid_time")
    private LocalDateTime bidTime = LocalDateTime.now();

    @Column(name = "product_name_snapshot")
    private String productNameSnapshot;

    @Column(name = "seller_name_snapshot")
    private String sellerNameSnapshot;

    @Column(name = "bidder_name_snapshot")
    private String bidderNameSnapshot;

    @Column(name = "bidder_email_snapshot")
    private String bidderEmailSnapshot;

    @PrePersist
    protected void onCreate() {
        bidTime = LocalDateTime.now();
    }
} 