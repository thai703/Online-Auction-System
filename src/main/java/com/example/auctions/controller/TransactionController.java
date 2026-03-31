package com.example.auctions.controller;

import com.example.auctions.model.Auction;
import com.example.auctions.model.Transaction;
import com.example.auctions.model.TransactionStatus;
import com.example.auctions.model.User;
import com.example.auctions.service.AuctionService;
import com.example.auctions.service.TransactionService;
import com.example.auctions.service.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/transactions")
public class TransactionController {

    private static final Logger logger = LoggerFactory.getLogger(TransactionController.class);

    private final AuctionService auctionService;
    private final TransactionService transactionService;
    private final WalletService walletService;

    @Autowired
    public TransactionController(
            AuctionService auctionService,
            TransactionService transactionService,
            WalletService walletService) {
        this.auctionService = auctionService;
        this.transactionService = transactionService;
        this.walletService = walletService;
    }

    @GetMapping("/history")
    public String viewTransactionHistory(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {
        int pageIndex = Math.max(0, page - 1);
        int clampedSize = Math.min(Math.max(size, 1), 50);
        List<Transaction> buyerTransactions = transactionService.getPurchaseHistory(user, pageIndex, clampedSize);
        long totalBuyerTransactions = transactionService.countPurchaseHistory(user);

        List<Transaction> sellerTransactions = transactionService.getSaleHistory(user, pageIndex, clampedSize);
        long totalSellerTransactions = transactionService.countSaleHistory(user);

        model.addAttribute("buyerTransactions", buyerTransactions);
        model.addAttribute("sellerTransactions", sellerTransactions);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", Math.max(1,
                (int) Math.ceil(Math.max(totalBuyerTransactions, totalSellerTransactions) / (double) clampedSize)));

        return "transaction/history";
    }

    @PostMapping("/create/{auctionId}")
    public String createTransactionPost(
            @PathVariable Long auctionId,
            @AuthenticationPrincipal User user,
            Model model,
            RedirectAttributes redirectAttributes) {
        return handleTransactionConfirmation(auctionId, user, model, redirectAttributes);
    }

    @GetMapping("/create/{auctionId}")
    public String showTransactionConfirmation(
            @PathVariable Long auctionId,
            @AuthenticationPrincipal User user,
            Model model,
            RedirectAttributes redirectAttributes) {
        return handleTransactionConfirmation(auctionId, user, model, redirectAttributes);
    }

    private String handleTransactionConfirmation(Long auctionId, User user, Model model, RedirectAttributes redirectAttributes) {
        logger.info("Showing transaction confirmation for auction {} by user {}", auctionId, user.getEmail());

        Auction auction = auctionService.getAuctionById(auctionId);

        if (auction == null) {
            redirectAttributes.addFlashAttribute("error", "Auction not found");
            return "redirect:/auctions/browse";
        }

        if (auction.getWinner() == null) {
            redirectAttributes.addFlashAttribute("error", "This auction has no winner yet");
            return "redirect:/auctions/" + auctionId;
        }

        if (!auction.getWinner().getId().equals(user.getId())) {
            redirectAttributes.addFlashAttribute("error", "You are not authorized to make payment for this auction");
            return "redirect:/auctions/" + auctionId;
        }

        // Find or create transaction
        Transaction transaction = transactionService.findOrCreateTransaction(auction);

        // If already completed, redirect to result
        if (transaction.getStatus() == TransactionStatus.COMPLETED) {
            redirectAttributes.addFlashAttribute("message", "This transaction has been successfully paid!");
            redirectAttributes.addFlashAttribute("status", "success");
            return "redirect:/transactions/payment-result";
        }

        model.addAttribute("auction", auction);
        model.addAttribute("transaction", transaction);
        model.addAttribute("walletBalance", walletService.getBalance(user));

        return "transaction/confirm";
    }

    @PostMapping("/pay/{transactionId}")
    public String payWithWallet(
            @PathVariable Long transactionId,
            @AuthenticationPrincipal User user,
            RedirectAttributes redirectAttributes) {
        logger.info("User {} attempting wallet payment for transaction {}", user.getEmail(), transactionId);

        try {
            // All validation & locking happens inside the service
            // Do NOT pre-load transaction here — OSIV L1 cache causes double-pay
            boolean success = walletService.payForAuction(transactionId, user.getId());

            if (success) {
                redirectAttributes.addFlashAttribute("status", "success");
            } else {
                redirectAttributes.addFlashAttribute("message", "Insufficient wallet balance. Please top up.");
                redirectAttributes.addFlashAttribute("status", "error");
            }

            return "redirect:/transactions/payment-result";
        } catch (Exception e) {
            logger.error("Error processing wallet payment for transaction {}: {}", transactionId, e.getMessage(), e);
            redirectAttributes.addFlashAttribute("message", "Payment error: " + e.getMessage());
            redirectAttributes.addFlashAttribute("status", "error");
            return "redirect:/transactions/payment-result";
        }
    }

    @GetMapping("/payment-result")
    public String paymentResult(Model model) {
        // Flash attributes are already in model from redirect
        return "transaction/payment-result";
    }
}