package com.example.auctions.controller;

import com.example.auctions.model.User;
import com.example.auctions.model.UserRole;
import com.example.auctions.service.AuctionService;
import com.example.auctions.service.TransactionService;
import com.example.auctions.service.BidService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {
    
    private final AuctionService auctionService;
    private final TransactionService transactionService;
    private final BidService bidService;
    
    @Autowired
    public DashboardController(AuctionService auctionService, TransactionService transactionService, BidService bidService) {
        this.auctionService = auctionService;
        this.transactionService = transactionService;
        this.bidService = bidService;
    }
    
    @GetMapping("/dashboard")
    public String showDashboard(@AuthenticationPrincipal User user, Model model) {
        if (user.getRole() == UserRole.ADMIN) {
            return "redirect:/admin";
        }

        // Add user to model
        model.addAttribute("user", user);
        
        if (user.getRole() == UserRole.SELLER) {
            // Calculate statistics for seller
            long activeAuctionsCount = auctionService.countActiveAuctionsBySeller(user);
            long successfulBidsCount = auctionService.countSuccessfulAuctionsBySeller(user);
            var totalSales = transactionService.getTotalSalesBySeller(user);
            
            // Add seller statistics to model
            model.addAttribute("activeAuctionsCount", activeAuctionsCount);
            model.addAttribute("successfulBidsCount", successfulBidsCount);
            model.addAttribute("totalSales", totalSales);
        }

        if (user.getRole() == UserRole.BUYER) {
            // Calculate statistics for buyer
            long activeAuctionsParticipating = bidService.countActiveAuctionsParticipating(user);
            long auctionsAsHighestBidder = bidService.countAuctionsAsHighestBidder(user);
            long totalAuctionsWon = bidService.countTotalAuctionsWon(user);
            var totalMoneySpent = transactionService.getTotalSpentByBuyer(user);
            long watchedAuctionsCount = auctionService.countWatchedAuctions(user);

            // Add buyer statistics to model
            model.addAttribute("activeAuctionsParticipating", activeAuctionsParticipating);
            model.addAttribute("auctionsAsHighestBidder", auctionsAsHighestBidder);
            model.addAttribute("totalAuctionsWon", totalAuctionsWon);
            model.addAttribute("totalMoneySpent", totalMoneySpent);
            model.addAttribute("watchedAuctionsCount", watchedAuctionsCount);
        }
        
        return "dashboard";
    }
} 
