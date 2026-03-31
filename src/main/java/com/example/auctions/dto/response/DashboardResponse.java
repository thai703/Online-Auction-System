package com.example.auctions.dto.response;

import java.math.BigDecimal;

public record DashboardResponse(
        UserResponse user,
        String role,
        // Seller stats
        Long activeAuctionsCount,
        Long successfulAuctionsCount,
        BigDecimal totalSales,
        // Buyer stats
        Long activeAuctionsParticipating,
        Long auctionsAsHighestBidder,
        Long totalAuctionsWon,
        BigDecimal totalMoneySpent,
        Long watchedAuctionsCount
) {
    public static DashboardResponse forSeller(UserResponse user,
                                               long activeAuctions,
                                               long successfulAuctions,
                                               BigDecimal totalSales) {
        return new DashboardResponse(user, "SELLER",
                activeAuctions, successfulAuctions, totalSales,
                null, null, null, null, null);
    }

    public static DashboardResponse forBuyer(UserResponse user,
                                              long activeAuctionsParticipating,
                                              long auctionsAsHighestBidder,
                                              long totalAuctionsWon,
                                              BigDecimal totalMoneySpent,
                                              long watchedAuctionsCount) {
        return new DashboardResponse(user, "BUYER",
                null, null, null,
                activeAuctionsParticipating, auctionsAsHighestBidder,
                totalAuctionsWon, totalMoneySpent, watchedAuctionsCount);
    }
}
