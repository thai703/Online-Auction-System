package com.example.auctions.dto.response;

import com.example.auctions.model.WalletTransaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record WalletTransactionResponse(
        Long id,
        String type,
        BigDecimal amount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        String description,
        LocalDateTime createdAt,
        Long relatedTransactionId,
        Long relatedAuctionId
) {
    public static WalletTransactionResponse from(WalletTransaction wt) {
        return new WalletTransactionResponse(
                wt.getId(),
                wt.getType() != null ? wt.getType().name() : null,
                wt.getAmount(),
                wt.getBalanceBefore(),
                wt.getBalanceAfter(),
                wt.getDescription(),
                wt.getCreatedAt(),
                wt.getRelatedTransactionId(),
                wt.getRelatedAuctionId()
        );
    }
}
