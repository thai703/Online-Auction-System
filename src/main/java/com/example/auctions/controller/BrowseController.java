package com.example.auctions.controller;

import com.example.auctions.model.Auction;
import com.example.auctions.model.User;
import com.example.auctions.service.AuctionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/auctions/browse")
public class BrowseController {
    private final AuctionService auctionService;

    @Autowired
    public BrowseController(AuctionService auctionService) {
        this.auctionService = auctionService;
    }

    @GetMapping
    public String browseAuctions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "all") String visibility,
            @RequestParam(defaultValue = "endingSoon") String sort,
            @AuthenticationPrincipal User user,
            Model model) {
        final int PAGE_SIZE = 12;
        int pageIndex = Math.max(0, page - 1);
        Page<Auction> auctions = auctionService.searchActiveAuctions(pageIndex, PAGE_SIZE, keyword, visibility, sort);
        
        model.addAttribute("auctions", auctions.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", auctions.getTotalPages());
        model.addAttribute("keyword", keyword);
        model.addAttribute("visibility", visibility);
        model.addAttribute("sort", sort);
        model.addAttribute("totalElements", auctions.getTotalElements());
        
        return "auctions/browse";
    }
} 
