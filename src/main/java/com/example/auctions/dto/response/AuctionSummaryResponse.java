package com.example.auctions.dto.response;

import com.example.auctions.model.Auction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AuctionSummaryResponse(
        Long id,
        String productName,
        String description,
        BigDecimal startingPrice,
        BigDecimal currentPrice,
        LocalDateTime endTime,
        LocalDateTime createdAt,
        String imageUrl,
        String status,
        String visibility,
        Long sellerId,
        String sellerName,
        Long winnerId,
        String winnerName,
        int bidCount
) {
    public static AuctionSummaryResponse from(Auction auction) {
        return new AuctionSummaryResponse(
                auction.getId(),
                auction.getProductName(),
                auction.getDescription(),
                auction.getStartingPrice(),
                auction.getCurrentPrice() != null ? auction.getCurrentPrice() : auction.getStartingPrice(),
                auction.getEndTime(),
                auction.getCreatedAt(),
                auction.getImage() != null ? "/images/auctions/" + auction.getImage() : null,
                auction.getStatus() != null ? auction.getStatus().name() : null,
                auction.getVisibility() != null ? auction.getVisibility().name() : null,
                auction.getSeller() != null ? auction.getSeller().getId() : null,
                auction.getSellerNameSnapshot() != null ? auction.getSellerNameSnapshot()
                        : (auction.getSeller() != null ? auction.getSeller().getFullName() : null),
                auction.getWinner() != null ? auction.getWinner().getId() : null,
                auction.getWinnerNameSnapshot() != null ? auction.getWinnerNameSnapshot()
                        : (auction.getWinner() != null ? auction.getWinner().getFullName() : null),
                auction.getBids() != null ? auction.getBids().size() : 0
        );
    }
}
