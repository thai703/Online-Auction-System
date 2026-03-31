package com.example.auctions.service;

import com.example.auctions.model.*;
import com.example.auctions.repository.TransactionRepository;
import com.example.auctions.repository.UserRepository;
import com.example.auctions.repository.WalletTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class WalletService {
    private static final Logger logger = LoggerFactory.getLogger(WalletService.class);

    private final UserRepository userRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final TransactionRepository transactionRepository;
    private final EmailService emailService;
    private final AuctionService auctionService;

    @Autowired
    public WalletService(UserRepository userRepository,
            WalletTransactionRepository walletTransactionRepository,
            TransactionRepository transactionRepository,
            EmailService emailService,
            AuctionService auctionService) {
        this.userRepository = userRepository;
        this.walletTransactionRepository = walletTransactionRepository;
        this.transactionRepository = transactionRepository;
        this.emailService = emailService;
        this.auctionService = auctionService;
    }

    private static final BigDecimal MAX_SINGLE_TOPUP = new BigDecimal("50000000"); // 50M VND per transaction
    private static final BigDecimal MAX_BALANCE = new BigDecimal("500000000"); // 500M VND max balance

    @Transactional
    public void topUp(User user, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Top-up amount must be greater than 0");
        }

        // Enforce single top-up limit
        if (amount.compareTo(MAX_SINGLE_TOPUP) > 0) {
            throw new RuntimeException("Maximum top-up amount is 50,000,000 VND per transaction");
        }

        // Lock user row to prevent race condition on balance
        User freshUser = userRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        BigDecimal balanceBefore = freshUser.getBalance();
        BigDecimal balanceAfter = balanceBefore.add(amount);

        // Enforce max balance cap
        if (balanceAfter.compareTo(MAX_BALANCE) > 0) {
            throw new RuntimeException("Top-up would exceed maximum wallet balance of 500,000,000 VND");
        }

        freshUser.setBalance(balanceAfter);
        userRepository.save(freshUser);

        // Create wallet transaction record
        WalletTransaction wt = new WalletTransaction();
        wt.setUser(freshUser);
        wt.setType(WalletTransactionType.TOP_UP);
        wt.setAmount(amount);
        wt.setBalanceBefore(balanceBefore);
        wt.setBalanceAfter(balanceAfter);
        wt.setDescription("Wallet top-up");
        // Snapshot user info at transaction time
        wt.setUserNameSnapshot(freshUser.getFullName());
        wt.setUserEmailSnapshot(freshUser.getEmail());
        walletTransactionRepository.save(wt);

        logger.info("User {} topped up {} VNĐ. Balance: {} -> {}",
                freshUser.getEmail(), amount, balanceBefore, balanceAfter);
    }

    @Transactional
    public boolean payForAuction(Long transactionId, Long currentUserId) {
        // Lock transaction row to prevent double-pay
        // IMPORTANT: load ONLY here (not in controller) to avoid OSIV L1 cache returning stale status
        Transaction transaction = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));

        // Authorization check under lock
        if (!transaction.getBuyer().getId().equals(currentUserId)) {
            throw new RuntimeException("You are not authorized to pay for this transaction");
        }

        // Idempotent: if already completed, return true
        if (transaction.getStatus() == TransactionStatus.COMPLETED) {
            return true;
        }

        // Lock buyer and seller rows to prevent balance race conditions
        User buyer = userRepository.findByIdForUpdate(transaction.getBuyer().getId())
                .orElseThrow(() -> new RuntimeException("Buyer not found"));
        User seller = userRepository.findByIdForUpdate(transaction.getSeller().getId())
                .orElseThrow(() -> new RuntimeException("Seller not found"));

        BigDecimal price = transaction.getPrice();

        // Check buyer balance
        if (buyer.getBalance().compareTo(price) < 0) {
            logger.warn("User {} has insufficient balance ({}) for payment of {}",
                    buyer.getEmail(), buyer.getBalance(), price);
            return false;
        }

        // Deduct from buyer
        BigDecimal buyerBalanceBefore = buyer.getBalance();
        BigDecimal buyerBalanceAfter = buyerBalanceBefore.subtract(price);
        buyer.setBalance(buyerBalanceAfter);
        userRepository.save(buyer);

        // Credit to seller
        BigDecimal sellerBalanceBefore = seller.getBalance();
        BigDecimal sellerBalanceAfter = sellerBalanceBefore.add(price);
        seller.setBalance(sellerBalanceAfter);
        userRepository.save(seller);

        // Update transaction status
        transaction.setStatus(TransactionStatus.COMPLETED);
        transactionRepository.save(transaction);

        // Create wallet transaction for buyer (PAYMENT_OUT)
        WalletTransaction buyerWt = new WalletTransaction();
        buyerWt.setUser(buyer);
        buyerWt.setType(WalletTransactionType.PAYMENT_OUT);
        buyerWt.setAmount(price);
        buyerWt.setBalanceBefore(buyerBalanceBefore);
        buyerWt.setBalanceAfter(buyerBalanceAfter);
        buyerWt.setDescription("Auction payment: " + transaction.getItemName());
        buyerWt.setRelatedTransactionId(transaction.getId());
        buyerWt.setRelatedAuctionId(transaction.getAuctionId());
        buyerWt.setUserNameSnapshot(buyer.getFullName());
        buyerWt.setUserEmailSnapshot(buyer.getEmail());
        walletTransactionRepository.save(buyerWt);

        // Create wallet transaction for seller (SALE_IN)
        WalletTransaction sellerWt = new WalletTransaction();
        sellerWt.setUser(seller);
        sellerWt.setType(WalletTransactionType.SALE_IN);
        sellerWt.setAmount(price);
        sellerWt.setBalanceBefore(sellerBalanceBefore);
        sellerWt.setBalanceAfter(sellerBalanceAfter);
        sellerWt.setDescription("Auction proceeds: " + transaction.getItemName());
        sellerWt.setRelatedTransactionId(transaction.getId());
        sellerWt.setRelatedAuctionId(transaction.getAuctionId());
        sellerWt.setUserNameSnapshot(seller.getFullName());
        sellerWt.setUserEmailSnapshot(seller.getEmail());
        walletTransactionRepository.save(sellerWt);

        logger.info("Payment successful: Buyer {} paid {} VNĐ to Seller {} for transaction {}",
                buyer.getEmail(), price, seller.getEmail(), transaction.getId());

        // Send emails
        try {
            Auction auction = auctionService.getAuctionById(transaction.getAuctionId());
            emailService.sendInvoiceEmail(transaction, auction);
            emailService.sendPaymentNotificationToSeller(transaction, auction);
            logger.info("Payment emails sent for transaction {}", transaction.getId());
        } catch (Exception e) {
            logger.error("Failed to send emails for transaction {}: {}", transaction.getId(), e.getMessage());
        }

        return true;
    }

    public Page<WalletTransaction> getWalletHistory(User user, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return walletTransactionRepository.findByUserOrderByCreatedAtDesc(user, pageable);
    }

    public BigDecimal getBalance(User user) {
        User freshUser = userRepository.findById(user.getId())
                .orElseThrow(() -> new RuntimeException("User not found"));
        return freshUser.getBalance();
    }
}
