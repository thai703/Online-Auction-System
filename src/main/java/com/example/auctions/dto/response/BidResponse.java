package com.example.auctions.dto.response;

import com.example.auctions.model.Bid;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BidResponse(
        Long id,
        BigDecimal amount,
        LocalDateTime bidTime,
        Long bidderId,
        String bidderName,
        Long auctionId,
        String productName,
        String auctionStatus
) {
    public static BidResponse from(Bid bid) {
        return new BidResponse(
                bid.getId(),
                bid.getAmount(),
                bid.getBidTime(),
                bid.getBidder() != null ? bid.getBidder().getId() : null,
                bid.getBidderNameSnapshot() != null ? bid.getBidderNameSnapshot()
                        : (bid.getBidder() != null ? bid.getBidder().getFullName() : null),
                bid.getAuction() != null ? bid.getAuction().getId() : null,
                bid.getProductNameSnapshot() != null ? bid.getProductNameSnapshot()
                        : (bid.getAuction() != null ? bid.getAuction().getProductName() : null),
                bid.getAuction() != null && bid.getAuction().getStatus() != null
                        ? bid.getAuction().getStatus().name() : null
        );
    }
}
