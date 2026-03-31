package com.example.auctions.dto.response;

import com.example.auctions.dto.UserBidGroupDTO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record UserBidGroupResponse(
        Long auctionId,
        String productName,
        String auctionStatus,
        BigDecimal highestBid,
        LocalDateTime latestBidTime,
        List<BidResponse> bidHistory
) {
    public static UserBidGroupResponse from(UserBidGroupDTO dto) {
        return new UserBidGroupResponse(
                dto.getAuction().getId(),
                dto.getAuction().getProductName(),
                dto.getAuction().getStatus().name(),
                dto.getHighestBid(),
                dto.getLatestBidTime(),
                dto.getAllUserBids().stream().map(BidResponse::from).toList()
        );
    }
}
