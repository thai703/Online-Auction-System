package com.example.auctions.controller.api;

import com.example.auctions.dto.request.UnlockAuctionRequest;
import com.example.auctions.dto.response.ApiResponse;
import com.example.auctions.dto.response.AuctionDetailResponse;
import com.example.auctions.dto.response.AuctionSummaryResponse;
import com.example.auctions.dto.response.BidResponse;
import com.example.auctions.dto.response.PageResponse;
import com.example.auctions.model.Auction;
import com.example.auctions.model.AuctionStatus;
import com.example.auctions.model.AuctionVisibility;
import com.example.auctions.model.Bid;
import com.example.auctions.model.User;
import com.example.auctions.model.UserRole;
import com.example.auctions.service.AuctionService;
import com.example.auctions.service.BidService;
import jakarta.servlet.http.HttpSession;
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
            @AuthenticationPrincipal User currentUser,
            HttpSession session) {
        Auction auction = auctionService.getAuctionById(id);
        boolean hasAccess = auctionService.canAccessPrivateAuction(auction, currentUser, session)
                || auctionService.canAccessPrivateAuctionStateless(auction, currentUser);
        boolean accessRequired = auctionService.isPrivateAuction(auction) && !hasAccess;
        boolean isOwner = currentUser != null && auction.getSeller() != null
                && auction.getSeller().getId().equals(currentUser.getId());
        int attemptsRemaining = currentUser != null
                ? auctionService.getRemainingUnlockAttempts(id, currentUser.getId())
                : 0;

        java.util.List<BidResponse> bids = accessRequired
                ? java.util.Collections.emptyList()
                : bidService.getAuctionBids(auction).stream()
                        .map(bid -> BidResponse.from(bid, resolveBidderNameForViewer(auction, bid, currentUser)))
                        .toList();
        String resolvedWinnerName = resolveWinnerNameForViewer(auction, currentUser);
        AuctionDetailResponse detail = AuctionDetailResponse.fromBidResponses(
                auction, bids, accessRequired, isOwner, attemptsRemaining, resolvedWinnerName);
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

    private String resolveWinnerNameForViewer(Auction auction, User currentUser) {
        if (auction.getWinner() == null) {
            return null;
        }
        String winnerName = auction.getWinnerNameSnapshot() != null
                ? auction.getWinnerNameSnapshot()
                : auction.getWinner().getFullName();
        if (auction.getVisibility() != AuctionVisibility.PRIVATE) {
            return winnerName;
        }
        // Private auction: only seller, winner, and admin see real winner name
        Long currentUserId = currentUser != null ? currentUser.getId() : null;
        if (currentUser != null && currentUser.getRole() == UserRole.ADMIN) {
            return winnerName;
        }
        if (currentUserId != null && auction.getSeller() != null
                && auction.getSeller().getId().equals(currentUserId)) {
            return winnerName;
        }
        if (currentUserId != null && auction.getWinner().getId().equals(currentUserId)) {
            return winnerName;
        }
        return maskName(winnerName);
    }

    private String resolveBidderNameForViewer(Auction auction, Bid bid, User currentUser) {
        String bidderName = bid.getBidderNameSnapshot() != null ? bid.getBidderNameSnapshot()
                : (bid.getBidder() != null ? bid.getBidder().getFullName() : null);
        if (bidderName == null) {
            return null;
        }

        Long currentUserId = currentUser != null ? currentUser.getId() : null;
        Long bidderId = bid.getBidder() != null ? bid.getBidder().getId() : null;

        if (currentUserId != null && currentUserId.equals(bidderId)) {
            return "You";
        }

        if (auction.getVisibility() != AuctionVisibility.PRIVATE) {
            return bidderName;
        }

        if (currentUser != null && currentUser.getRole() == UserRole.ADMIN) {
            return bidderName;
        }

        boolean sellerCanSeeWinnerIdentity = currentUserId != null
                && auction.getSeller() != null
                && auction.getSeller().getId().equals(currentUserId)
                && auction.getStatus() == AuctionStatus.ENDED
                && auction.getWinner() != null
                && auction.getWinner().getId().equals(bidderId);

        if (sellerCanSeeWinnerIdentity) {
            return bidderName;
        }

        return maskName(bidderName);
    }

    private String maskName(String name) {
        if (name == null || name.length() < 2) {
            return name;
        }
        return name.substring(0, 1) + "***" + name.substring(name.length() - 1);
    }
}
