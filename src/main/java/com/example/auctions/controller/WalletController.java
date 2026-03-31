package com.example.auctions.controller;

import com.example.auctions.model.User;
import com.example.auctions.model.WalletTransaction;
import com.example.auctions.service.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
@RequestMapping("/wallet")
public class WalletController {

    private static final Logger logger = LoggerFactory.getLogger(WalletController.class);

    private final WalletService walletService;

    @Autowired
    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping
    public String walletPage(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {
        BigDecimal balance = walletService.getBalance(user);
        int pageIndex = Math.max(0, page - 1);
        int clampedSize = Math.min(Math.max(size, 1), 50);
        Page<WalletTransaction> historyPage = walletService.getWalletHistory(user, pageIndex, clampedSize);

        model.addAttribute("balance", balance);
        model.addAttribute("walletTransactions", historyPage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", historyPage.getTotalPages());

        return "wallet/index";
    }

    @PostMapping("/top-up")
    public String topUp(
            @AuthenticationPrincipal User user,
            @RequestParam BigDecimal amount,
            RedirectAttributes redirectAttributes) {
        try {
            walletService.topUp(user, amount);
            redirectAttributes.addFlashAttribute("walletSuccess", "Top-up successful! +" + amount + " VNĐ");
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("walletError", e.getMessage());
        }
        return "redirect:/wallet";
    }
}
