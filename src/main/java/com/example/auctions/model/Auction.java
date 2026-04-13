package com.example.auctions.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "auctions", indexes = {
    @Index(name = "idx_auctions_status_end_time", columnList = "status, end_time"),
    @Index(name = "idx_auctions_seller_status", columnList = "seller_id, status, end_time")
})
public class Auction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(columnDefinition = "bigint default 0")
    private Long version = 0L;

    @NotBlank(message = "Product name is required")
    @Size(min = 3, max = 100, message = "Product name must be between 3 and 100 characters")
    @Column(name = "product_name")
    private String productName;

    @NotBlank(message = "Description is required")
    @Size(min = 10, max = 1000, message = "Description must be between 10 and 1000 characters")
    @Column(columnDefinition = "TEXT")
    private String description;

    @NotNull(message = "Starting price is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Starting price must be greater than 0")
    @Column(name = "starting_price", precision = 19, scale = 0)
    private BigDecimal startingPrice;

    @Column(name = "current_price", precision = 19, scale = 0)
    private BigDecimal currentPrice;

    @NotNull(message = "End time is required")
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "image")
    private String image;

    @Transient
    private MultipartFile imageFile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User seller;

    @OneToMany(mappedBy = "auction", cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<Bid> bids = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "winner_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User winner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User cancelledBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private AuctionStatus status = AuctionStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false)
    private AuctionVisibility visibility = AuctionVisibility.PUBLIC;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "seller_name_snapshot")
    private String sellerNameSnapshot;

    @Column(name = "seller_email_snapshot")
    private String sellerEmailSnapshot;

    @Column(name = "winner_name_snapshot")
    private String winnerNameSnapshot;

    @Column(name = "winner_email_snapshot")
    private String winnerEmailSnapshot;

    @Transient
    private long uniqueBidders;

    @Transient
    private String accessCode;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        currentPrice = startingPrice;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Custom validation for end time - only enforce future date when publishing (ACTIVE)
    @AssertTrue(message = "End time must be in the future for active auctions")
    private boolean isEndTimeValid() {
        if (status == AuctionStatus.ACTIVE) {
            return endTime != null && endTime.isAfter(LocalDateTime.now());
        }
        // DRAFT auctions: only require endTime is not null (already handled by @NotNull)
        return true;
    }

    public void addBid(Bid bid) {
        bids.add(bid);
        bid.setAuction(this);
        currentPrice = bid.getAmount();
    }

    public void removeBid(Bid bid) {
        bids.remove(bid);
        bid.setAuction(null);
    }

    public long getUniqueBidders() {
        return uniqueBidders;
    }

    public void setUniqueBidders(long uniqueBidders) {
        this.uniqueBidders = uniqueBidders;
    }

    public AuctionVisibility getVisibility() {
        return visibility == null ? AuctionVisibility.PUBLIC : visibility;
    }
}
