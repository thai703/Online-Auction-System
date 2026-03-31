package com.example.auctions.service;

import com.example.auctions.model.Transaction;
import com.example.auctions.model.TransactionStatus;
import com.example.auctions.model.User;
import com.example.auctions.model.Auction;
import com.example.auctions.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class TransactionService {
    private static final Logger logger = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactionRepository;

    @Autowired
    public TransactionService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public List<Transaction> getPurchaseHistory(User buyer, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("transactionDate").descending());
        return transactionRepository.findByBuyerOrderByTransactionDateDesc(buyer, pageable).getContent();
    }

    public List<Transaction> getSaleHistory(User seller, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("transactionDate").descending());
        return transactionRepository.findBySellerOrderByTransactionDateDesc(seller, pageable).getContent();
    }

    public long countPurchaseHistory(User buyer) {
        return transactionRepository.countByBuyer(buyer);
    }

    public long countSaleHistory(User seller) {
        return transactionRepository.countBySeller(seller);
    }

    public List<Transaction> getPurchaseHistory(User buyer) {
        return transactionRepository.findByBuyerOrderByTransactionDateDesc(buyer, Pageable.unpaged()).getContent();
    }

    public List<Transaction> getSaleHistory(User seller) {
        return transactionRepository.findBySellerOrderByTransactionDateDesc(seller, Pageable.unpaged()).getContent();
    }

    public BigDecimal getTotalSpentByBuyer(User buyer) {
        return transactionRepository.sumTotalSpentByBuyer(buyer);
    }

    public BigDecimal getTotalSalesBySeller(User seller) {
        return transactionRepository.sumTotalSalesBySeller(seller);
    }

    public Transaction findById(Long id) {
        return transactionRepository.findByIdWithUsers(id)
                .orElseThrow(() -> new RuntimeException("Transaction not found with id: " + id));
    }

    /**
     * Tìm transaction hiện có hoặc tạo mới cho auction.
     * Tránh tạo transaction trùng cho cùng một auction.
     */
    @Transactional
    public Transaction findOrCreateTransaction(Auction auction) {
        // Check nếu đã có transaction cho auction này
        Optional<Transaction> existing = transactionRepository.findByAuctionId(auction.getId());
        if (existing.isPresent()) {
            logger.info("Found existing transaction {} for auction {}", existing.get().getId(), auction.getId());
            return existing.get();
        }

        logger.info("Creating new transaction for auction: {}", auction.getId());

        Transaction transaction = new Transaction();
        transaction.setBuyer(auction.getWinner());
        transaction.setSeller(auction.getSeller());
        transaction.setItemName(auction.getProductName());
        transaction.setItemDescription(auction.getDescription());
        transaction.setPrice(auction.getCurrentPrice());
        transaction.setTransactionDate(LocalDateTime.now());
        transaction.setStatus(TransactionStatus.PENDING);
        transaction.setAuctionId(auction.getId());
        // Snapshot buyer/seller info at transaction creation time
        transaction.setBuyerNameSnapshot(auction.getWinner().getFullName());
        transaction.setBuyerEmailSnapshot(auction.getWinner().getEmail());
        transaction.setSellerNameSnapshot(auction.getSeller().getFullName());
        transaction.setSellerEmailSnapshot(auction.getSeller().getEmail());

        transaction = transactionRepository.save(transaction);
        logger.info("Transaction created with ID: {}", transaction.getId());

        return transaction;
    }

    public Transaction saveTransaction(Transaction transaction) {
        return transactionRepository.save(transaction);
    }
}