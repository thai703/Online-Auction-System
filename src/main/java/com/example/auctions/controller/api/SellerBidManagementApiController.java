package com.example.auctions.controller.api;

import com.example.auctions.dto.response.ApiResponse;
import com.example.auctions.dto.response.AuctionSummaryResponse;
import com.example.auctions.dto.response.BidResponse;
import com.example.auctions.model.Auction;
import com.example.auctions.model.AuctionStatus;
import com.example.auctions.model.Bid;
import com.example.auctions.model.User;
import com.example.auctions.service.AuctionService;
import com.example.auctions.service.BidService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/seller/bids")
public class SellerBidManagementApiController {

    private final AuctionService auctionService;
    private final BidService bidService;

    public SellerBidManagementApiController(AuctionService auctionService, BidService bidService) {
        this.auctionService = auctionService;
        this.bidService = bidService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> sellerBids(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(required = false) String status) {
        AuctionStatus auctionStatus = parseStatus(status);
        List<Auction> auctions = auctionStatus != null
                ? auctionService.getAuctionsBySellerAndStatus(currentUser, auctionStatus)
                : auctionService.getAuctionsBySeller(currentUser);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Auction auction : auctions) {
            List<Bid> bids = new ArrayList<>(bidService.getAuctionBids(auction));
            bids.sort(Comparator.comparing(Bid::getAmount, Comparator.reverseOrder())
                    .thenComparing(Bid::getBidTime));
            Map<String, Object> entry = Map.of(
                    "auction", AuctionSummaryResponse.from(auction),
                    "bids", bids.stream().map(BidResponse::from).toList(),
                    "uniqueBidderCount", bids.stream().map(b -> b.getBidder().getId()).distinct().count()
            );
            result.add(entry);
        }
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    private AuctionStatus parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return AuctionStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
