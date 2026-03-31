package com.example.auctions.controller.api;

import com.example.auctions.dto.request.TopUpRequest;
import com.example.auctions.dto.response.ApiResponse;
import com.example.auctions.dto.response.WalletResponse;
import com.example.auctions.dto.response.WalletTransactionResponse;
import com.example.auctions.dto.response.PageResponse;
import com.example.auctions.model.User;
import com.example.auctions.model.WalletTransaction;
import com.example.auctions.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wallet")
public class WalletApiController {

    private final WalletService walletService;

    public WalletApiController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<WalletResponse>> getWallet(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int clampedSize = Math.min(size, 50);
        Page<WalletTransaction> history = walletService.getWalletHistory(currentUser, page, clampedSize);
        PageResponse<WalletTransactionResponse> historyPage = PageResponse.from(history, WalletTransactionResponse::from);
        WalletResponse wallet = new WalletResponse(walletService.getBalance(currentUser), historyPage);
        return ResponseEntity.ok(ApiResponse.ok(wallet));
    }

    @PostMapping("/top-up")
    public ResponseEntity<ApiResponse<Void>> topUp(
            @Valid @RequestBody TopUpRequest req,
            @AuthenticationPrincipal User currentUser) {
        walletService.topUp(currentUser, req.amount());
        return ResponseEntity.ok(ApiResponse.ok("Top-up successful."));
    }
}
