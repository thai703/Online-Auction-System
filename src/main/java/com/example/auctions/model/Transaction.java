package com.example.auctions.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "transactions", uniqueConstraints = {
    @UniqueConstraint(columnNames = "auction_id")
})
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(columnDefinition = "bigint default 0")
    private Long version = 0L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    @Column(nullable = false)
    private String itemName;

    @Column(nullable = false)
    private String itemDescription;

    @Column(nullable = false, precision = 19, scale = 0)
    private BigDecimal price;

    @Column(name = "transaction_date", nullable = false)
    private LocalDateTime transactionDate;

    @Column(name = "auction_id", nullable = false)
    private Long auctionId;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private TransactionStatus status;

    @Column(name = "buyer_name_snapshot")
    private String buyerNameSnapshot;

    @Column(name = "buyer_email_snapshot")
    private String buyerEmailSnapshot;

    @Column(name = "seller_name_snapshot")
    private String sellerNameSnapshot;

    @Column(name = "seller_email_snapshot")
    private String sellerEmailSnapshot;

    // Constructor mặc định
    public Transaction() {
        this.transactionDate = LocalDateTime.now();
    }

    public Long getAuctionId() {
        return auctionId;
    }

    public void setAuctionId(Long auctionId) {
        this.auctionId = auctionId;
    }
}