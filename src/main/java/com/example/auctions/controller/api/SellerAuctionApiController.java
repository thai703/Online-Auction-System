package com.example.auctions.controller.api;

import com.example.auctions.dto.request.CreateAuctionRequest;
import com.example.auctions.dto.request.UpdateAuctionRequest;
import com.example.auctions.dto.response.ApiResponse;
import com.example.auctions.dto.response.AuctionSummaryResponse;
import com.example.auctions.dto.response.PageResponse;
import com.example.auctions.model.Auction;
import com.example.auctions.model.AuctionStatus;
import com.example.auctions.model.AuctionVisibility;
import com.example.auctions.dto.response.BidResponse;
import com.example.auctions.model.Bid;
import com.example.auctions.model.User;
import com.example.auctions.service.AuctionService;
import com.example.auctions.service.BidService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/seller/auctions")
public class SellerAuctionApiController {

    private final AuctionService auctionService;
    private final BidService bidService;

    public SellerAuctionApiController(AuctionService auctionService, BidService bidService) {
        this.auctionService = auctionService;
        this.bidService = bidService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AuctionSummaryResponse>>> list(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        int clampedSize = Math.min(size, 50);
        AuctionStatus auctionStatus = parseStatus(status);
        Page<Auction> auctions = auctionStatus != null
                ? auctionService.getAuctionsBySellerAndStatus(currentUser, auctionStatus, page, clampedSize)
                : auctionService.getAuctionsBySeller(currentUser, page, clampedSize);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(auctions, AuctionSummaryResponse::from)));
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<AuctionSummaryResponse>> create(
            @Valid @ModelAttribute CreateAuctionRequest req,
            @AuthenticationPrincipal User currentUser) throws IOException {
        Auction auction = mapToAuction(req, currentUser);
        Auction saved = auctionService.createAuction(auction, req.getAction());
        return ResponseEntity.ok(ApiResponse.ok("Auction created.", AuctionSummaryResponse.from(saved)));
    }

    @PutMapping(value = "/{id}", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<AuctionSummaryResponse>> update(
            @PathVariable Long id,
            @Valid @ModelAttribute UpdateAuctionRequest req,
            @AuthenticationPrincipal User currentUser) throws IOException {
        Auction existing = auctionService.getAuctionById(id);
        if (!existing.getSeller().getId().equals(currentUser.getId())) {
            return ResponseEntity.status(403).body(ApiResponse.error("You do not own this auction."));
        }
        Auction auction = mapUpdateToAuction(req, id, currentUser);
        Auction saved = auctionService.updateAuction(auction, req.getAction());
        if (saved == null) {
            return ResponseEntity.ok(ApiResponse.<AuctionSummaryResponse>ok("Auction deleted.", null));
        }
        return ResponseEntity.ok(ApiResponse.ok("Auction updated.", AuctionSummaryResponse.from(saved)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal User currentUser) {
        Auction auction = auctionService.getAuctionById(id);
        if (!auction.getSeller().getId().equals(currentUser.getId())) {
            return ResponseEntity.status(403).body(ApiResponse.error("You do not own this auction."));
        }
        auctionService.deleteAuction(auction);
        return ResponseEntity.ok(ApiResponse.ok("Auction deleted."));
    }

    @GetMapping("/{id}/bids")
    public ResponseEntity<ApiResponse<java.util.List<BidResponse>>> getAuctionBids(
            @PathVariable Long id,
            @AuthenticationPrincipal User currentUser) {
        Auction auction = auctionService.getAuctionById(id);
        if (!auction.getSeller().getId().equals(currentUser.getId())) {
            return ResponseEntity.status(403).body(ApiResponse.<java.util.List<BidResponse>>error("You do not own this auction."));
        }
        java.util.List<Bid> bids = bidService.getAuctionBids(auction);
        java.util.List<BidResponse> bidResponses = bids.stream().map(BidResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.ok(bidResponses));
    }

    private AuctionStatus parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return AuctionStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Auction mapToAuction(CreateAuctionRequest req, User seller) {
        Auction auction = new Auction();
        auction.setProductName(req.getProductName());
        auction.setDescription(req.getDescription());
        auction.setStartingPrice(req.getStartingPrice());
        auction.setEndTime(req.getEndTime());
        auction.setVisibility(AuctionVisibility.valueOf(req.getVisibility().toUpperCase()));
        auction.setAccessCode(req.getAccessCode());
        auction.setSeller(seller);
        auction.setImageFile(req.getImageFile());
        return auction;
    }

    private Auction mapUpdateToAuction(UpdateAuctionRequest req, Long id, User seller) {
        Auction auction = new Auction();
        auction.setId(id);
        auction.setProductName(req.getProductName());
        auction.setDescription(req.getDescription());
        auction.setStartingPrice(req.getStartingPrice());
        auction.setEndTime(req.getEndTime());
        auction.setVisibility(AuctionVisibility.valueOf(req.getVisibility().toUpperCase()));
        auction.setAccessCode(req.getAccessCode());
        auction.setSeller(seller);
        auction.setImageFile(req.getImageFile());
        return auction;
    }
}
