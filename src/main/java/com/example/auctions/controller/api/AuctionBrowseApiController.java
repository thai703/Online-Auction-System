package com.example.auctions.controller.api;

import com.example.auctions.dto.request.UnlockAuctionRequest;
import com.example.auctions.dto.response.ApiResponse;
import com.example.auctions.dto.response.AuctionDetailResponse;
import com.example.auctions.dto.response.AuctionSummaryResponse;
import com.example.auctions.dto.response.PageResponse;
import com.example.auctions.model.Auction;
import com.example.auctions.model.Bid;
import com.example.auctions.model.User;
import com.example.auctions.service.AuctionService;
import com.example.auctions.service.BidService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auctions")
public class AuctionBrowseApiController {

    private final AuctionService auctionService;
    private final BidService bidService;

    public AuctionBrowseApiController(AuctionService auctionService, BidService bidService) {
        this.auctionService = auctionService;
        this.bidService = bidService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AuctionSummaryResponse>>> browse(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String visibility,
            @RequestParam(required = false) String sort) {
        int clampedSize = Math.min(size, 50);
        Page<Auction> auctions = auctionService.searchActiveAuctions(page, clampedSize, keyword, visibility, sort);
        PageResponse<AuctionSummaryResponse> response = PageResponse.from(auctions, AuctionSummaryResponse::from);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AuctionDetailResponse>> detail(
            @PathVariable Long id,
            @AuthenticationPrincipal User currentUser) {
        Auction auction = auctionService.getAuctionById(id);
        boolean accessRequired = auctionService.isPrivateAuction(auction)
                && !auctionService.canAccessPrivateAuctionStateless(auction, currentUser);
        boolean isOwner = currentUser != null && auction.getSeller() != null
                && auction.getSeller().getId().equals(currentUser.getId());
        int attemptsRemaining = currentUser != null
                ? auctionService.getRemainingUnlockAttempts(id, currentUser.getId())
                : 0;

        java.util.List<Bid> bids = accessRequired ? java.util.Collections.emptyList() : bidService.getAuctionBids(auction);
        AuctionDetailResponse detail = AuctionDetailResponse.from(auction, bids, accessRequired, isOwner, attemptsRemaining);
        return ResponseEntity.ok(ApiResponse.ok(detail));
    }

    @PostMapping("/{id}/unlock")
    public ResponseEntity<ApiResponse<Void>> unlock(
            @PathVariable Long id,
            @Valid @RequestBody UnlockAuctionRequest req,
            @AuthenticationPrincipal User currentUser) {
        Auction auction = auctionService.getAuctionById(id);
        boolean success = auctionService.unlockPrivateAuctionStateless(auction, req.accessCode(), currentUser.getId());
        if (success) {
            return ResponseEntity.ok(ApiResponse.ok("Auction unlocked successfully."));
        }
        int remaining = auctionService.getRemainingUnlockAttempts(id, currentUser.getId());
        return ResponseEntity.badRequest().body(ApiResponse.error(
                "Invalid access code. " + remaining + " attempt(s) remaining."));
    }
}
