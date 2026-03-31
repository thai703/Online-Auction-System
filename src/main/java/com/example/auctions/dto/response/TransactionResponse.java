package com.example.auctions.dto.response;

import com.example.auctions.model.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionResponse(
        Long id,
        String itemName,
        String itemDescription,
        BigDecimal price,
        String status,
        LocalDateTime transactionDate,
        Long auctionId,
        Long buyerId,
        String buyerName,
        String buyerEmail,
        Long sellerId,
        String sellerName,
        String sellerEmail
) {
    public static TransactionResponse from(Transaction tx) {
        String buyerName = tx.getBuyerNameSnapshot() != null ? tx.getBuyerNameSnapshot()
                : (tx.getBuyer() != null ? tx.getBuyer().getFullName() : null);
        String buyerEmail = tx.getBuyerEmailSnapshot() != null ? tx.getBuyerEmailSnapshot()
                : (tx.getBuyer() != null ? tx.getBuyer().getEmail() : null);
        String sellerName = tx.getSellerNameSnapshot() != null ? tx.getSellerNameSnapshot()
                : (tx.getSeller() != null ? tx.getSeller().getFullName() : null);
        String sellerEmail = tx.getSellerEmailSnapshot() != null ? tx.getSellerEmailSnapshot()
                : (tx.getSeller() != null ? tx.getSeller().getEmail() : null);

        return new TransactionResponse(
                tx.getId(),
                tx.getItemName(),
                tx.getItemDescription(),
                tx.getPrice(),
                tx.getStatus() != null ? tx.getStatus().name() : null,
                tx.getTransactionDate(),
                tx.getAuctionId(),
                tx.getBuyer() != null ? tx.getBuyer().getId() : null,
                buyerName,
                buyerEmail,
                tx.getSeller() != null ? tx.getSeller().getId() : null,
                sellerName,
                sellerEmail
        );
    }
}
