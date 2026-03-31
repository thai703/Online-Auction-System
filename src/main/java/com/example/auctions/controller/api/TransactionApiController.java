package com.example.auctions.controller.api;

import com.example.auctions.dto.response.ApiResponse;
import com.example.auctions.dto.response.TransactionResponse;
import com.example.auctions.model.Auction;
import com.example.auctions.model.Transaction;
import com.example.auctions.model.User;
import com.example.auctions.service.AuctionService;
import com.example.auctions.service.TransactionService;
import com.example.auctions.service.WalletService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
public class TransactionApiController {

    private final TransactionService transactionService;
    private final AuctionService auctionService;
    private final WalletService walletService;

    public TransactionApiController(TransactionService transactionService,
                                    AuctionService auctionService,
                                    WalletService walletService) {
        this.transactionService = transactionService;
        this.auctionService = auctionService;
        this.walletService = walletService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TransactionResponse>>> history(
            @AuthenticationPrincipal User currentUser) {
        List<TransactionResponse> purchases = transactionService.getPurchaseHistory(currentUser)
                .stream().map(TransactionResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.ok(purchases));
    }

    @PostMapping("/{auctionId}")
    public ResponseEntity<ApiResponse<TransactionResponse>> create(
            @PathVariable Long auctionId,
            @AuthenticationPrincipal User currentUser) {
        Auction auction = auctionService.getAuctionById(auctionId);
        if (auction.getWinner() == null || !auction.getWinner().getId().equals(currentUser.getId())) {
            return ResponseEntity.badRequest().body(ApiResponse.error("You are not the winner of this auction."));
        }
        Transaction tx = transactionService.findOrCreateTransaction(auction);
        return ResponseEntity.ok(ApiResponse.ok("Transaction ready.", TransactionResponse.from(tx)));
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<ApiResponse<Void>> pay(
            @PathVariable Long id,
            @AuthenticationPrincipal User currentUser) {
        boolean success = walletService.payForAuction(id, currentUser.getId());
        if (success) {
            return ResponseEntity.ok(ApiResponse.ok("Payment completed successfully."));
        }
        return ResponseEntity.badRequest().body(ApiResponse.error("Insufficient wallet balance to complete payment."));
    }
}
