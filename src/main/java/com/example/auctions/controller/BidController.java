package com.example.auctions.controller;

import com.example.auctions.dto.UserBidGroupDTO;
import com.example.auctions.model.Auction;
import com.example.auctions.model.AuctionStatus;
import com.example.auctions.model.Bid;
import com.example.auctions.model.User;
import com.example.auctions.service.AuctionService;
import com.example.auctions.service.BidService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/bids")
public class BidController {
    private final BidService bidService;
    private final AuctionService auctionService;

    @Autowired
    public BidController(BidService bidService, AuctionService auctionService) {
        this.bidService = bidService;
        this.auctionService = auctionService;
    }

    @GetMapping("/auction/{auctionId}")
    public String viewAuctionBids(@PathVariable Long auctionId,
                                  @AuthenticationPrincipal User user,
                                  HttpSession session,
                                  RedirectAttributes redirectAttributes,
                                  Model model) {
        Auction auction = auctionService.getAuctionById(auctionId);
        
        // Check if auction is active
        if (auction.getStatus() != AuctionStatus.ACTIVE) {
            return "redirect:/auctions/" + auctionId;
        }
        
        // Check if user is not the seller
        if (auction.getSeller().getId().equals(user.getId())) {
            return "redirect:/auctions/" + auctionId;
        }

        if (!auctionService.canAccessPrivateAuction(auction, user, session)) {
            redirectAttributes.addFlashAttribute("error", "Enter the private auction code before placing bids.");
            return "redirect:/auctions/" + auctionId;
        }
        
        List<Bid> bids = bidService.getAuctionBids(auction);
        model.addAttribute("auction", auction);
        model.addAttribute("bids", bids);
        return "bids/auction-bids";
    }

    @PostMapping("/place")
    @ResponseBody
    public ResponseEntity<?> placeBid(
            @AuthenticationPrincipal User user,
            @RequestParam Long auctionId,
            @RequestParam BigDecimal amount,
            HttpSession session) {
        try {
            // All validation happens inside the service under pessimistic lock
            // Do NOT pre-load auction here — OSIV L1 cache causes @Version conflicts
            Bid bid = bidService.placeBid(auctionId, user, amount, session);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Bid placed successfully",
                "newAmount", bid.getAmount()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", e.getMessage()
            ));
        }
    }

    @MessageMapping("/bid")
    @SendTo("/topic/bids")
    public Bid bid(Bid bid) {
        return bid;
    }

    @GetMapping("/my-bids")
    public String viewMyBids(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {
        int pageIndex = Math.max(0, page - 1);
        int clampedSize = Math.min(Math.max(size, 1), 50);
        Page<UserBidGroupDTO> groupedBidsPage = bidService.getUserBidsPaged(user.getId(), pageIndex, clampedSize);
        model.addAttribute("groupedBids", groupedBidsPage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", groupedBidsPage.getTotalPages());
        return "bids/my-bids";
    }

    @GetMapping("/won")
    public String viewWonAuctions(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {
        int pageIndex = Math.max(0, page - 1);
        int clampedSize = Math.min(Math.max(size, 1), 50);
        Page<Auction> wonAuctionsPage = bidService.getWonAuctionsWithPagination(user.getId(), pageIndex, clampedSize);
        model.addAttribute("auctions", wonAuctionsPage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", wonAuctionsPage.getTotalPages());
        return "bids/won-auctions";
    }

    @GetMapping("/active")
    public String viewActiveBids(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {
        int pageIndex = Math.max(0, page - 1);
        int clampedSize = Math.min(Math.max(size, 1), 50);
        Page<UserBidGroupDTO> activeBidsPage = bidService.getActiveBidsGroupedPaged(user.getId(), pageIndex, clampedSize);
        model.addAttribute("groupedBids", activeBidsPage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", activeBidsPage.getTotalPages());
        return "bids/active-bids";
    }
} 
