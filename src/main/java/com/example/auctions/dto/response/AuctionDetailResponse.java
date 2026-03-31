package com.example.auctions.dto.response;

import com.example.auctions.model.Auction;
import com.example.auctions.model.Bid;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AuctionDetailResponse(
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
        int bidCount,
        List<BidResponse> bids,
        boolean privateAccessRequired,
        boolean isOwner,
        int unlockAttemptsRemaining
) {
    public static AuctionDetailResponse from(Auction auction, List<Bid> bids,
                                              boolean privateAccessRequired,
                                              boolean isOwner,
                                              int unlockAttemptsRemaining) {
        List<BidResponse> bidResponses = bids != null
                ? bids.stream().map(BidResponse::from).toList()
                : List.of();

        return new AuctionDetailResponse(
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
                bidResponses.size(),
                bidResponses,
                privateAccessRequired,
                isOwner,
                unlockAttemptsRemaining
        );
    }
}
