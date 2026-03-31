package com.example.auctions.controller;

import com.example.auctions.model.Auction;
import com.example.auctions.model.AuctionStatus;
import com.example.auctions.model.Bid;
import com.example.auctions.model.User;
import com.example.auctions.service.AuctionService;
import org.springframework.data.domain.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Controller
@RequestMapping("/auctions/management")
public class AuctionManagementController {
    private static final int PAGE_SIZE = 4;

    private final AuctionService auctionService;
    
    @Autowired
    public AuctionManagementController(AuctionService auctionService) {
        this.auctionService = auctionService;
    }
    
    @GetMapping("/bids")
    public String viewAuctionBids(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false, defaultValue = "active") String status,
            @RequestParam(required = false, defaultValue = "1") int page,
            Model model) {

        int currentPage = Math.max(1, page);
        Page<Auction> auctionPage;

        if ("ended".equalsIgnoreCase(status)) {
            auctionPage = auctionService.getAuctionsBySellerAndStatus(user, AuctionStatus.ENDED, currentPage - 1,
                    PAGE_SIZE);
        } else if ("all".equalsIgnoreCase(status)) {
            auctionPage = auctionService.getAuctionsBySellerAndStatus(user, null, currentPage - 1, PAGE_SIZE);
        } else {
            auctionPage = auctionService.getAuctionsBySellerAndStatus(user, AuctionStatus.ACTIVE, currentPage - 1,
                    PAGE_SIZE);
        }

        List<Auction> auctions = new ArrayList<>(auctionPage.getContent());

        // Calculate unique bidders for each auction
        auctions.forEach(auction -> {
            List<Bid> sortedBids = new ArrayList<>(auction.getBids());
            sortedBids.sort(
                    Comparator.comparing(Bid::getAmount, Comparator.nullsLast(Comparator.naturalOrder())).reversed()
                            .thenComparing(
                                    Comparator.comparing(Bid::getBidTime, Comparator.nullsLast(Comparator.naturalOrder()))
                                            .reversed()));
            auction.setBids(sortedBids);

            long uniqueBidders = sortedBids.stream()
                .map(bid -> bid.getBidder().getId())
                .distinct()
                .count();
            auction.setUniqueBidders(uniqueBidders);
        });
        
        model.addAttribute("auctions", auctions);
        model.addAttribute("currentStatus", status.toLowerCase());
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", Math.max(1, auctionPage.getTotalPages()));
        return "auctions/auction-bids-management";
    }
}
