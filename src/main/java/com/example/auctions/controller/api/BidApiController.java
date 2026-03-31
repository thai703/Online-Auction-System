package com.example.auctions.controller.api;

import com.example.auctions.dto.UserBidGroupDTO;
import com.example.auctions.dto.request.PlaceBidRequest;
import com.example.auctions.dto.response.ApiResponse;
import com.example.auctions.dto.response.AuctionSummaryResponse;
import com.example.auctions.dto.response.BidResponse;
import com.example.auctions.dto.response.PageResponse;
import com.example.auctions.dto.response.UserBidGroupResponse;
import com.example.auctions.model.Bid;
import com.example.auctions.model.User;
import com.example.auctions.service.BidService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bids")
public class BidApiController {

    private final BidService bidService;

    public BidApiController(BidService bidService) {
        this.bidService = bidService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BidResponse>> placeBid(
            @Valid @RequestBody PlaceBidRequest req,
            @AuthenticationPrincipal User currentUser) {
        Bid bid = bidService.placeBid(req.auctionId(), currentUser, req.amount());
        return ResponseEntity.ok(ApiResponse.ok("Bid placed successfully.", BidResponse.from(bid)));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<PageResponse<UserBidGroupResponse>>> myBids(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int clampedSize = Math.min(size, 50);
        Page<UserBidGroupDTO> bids = bidService.getUserBidsPaged(currentUser.getId(), page, clampedSize);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(bids, UserBidGroupResponse::from)));
    }

    @GetMapping("/won")
    public ResponseEntity<ApiResponse<PageResponse<AuctionSummaryResponse>>> wonAuctions(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int clampedSize = Math.min(size, 50);
        var wonPage = bidService.getWonAuctionsWithPagination(currentUser.getId(), page, clampedSize);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(wonPage, AuctionSummaryResponse::from)));
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<BidResponse>>> activeBids(
            @AuthenticationPrincipal User currentUser) {
        List<BidResponse> bids = bidService.getActiveBids(currentUser.getId())
                .stream().map(BidResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.ok(bids));
    }
}
