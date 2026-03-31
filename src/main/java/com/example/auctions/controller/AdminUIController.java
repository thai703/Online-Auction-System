package com.example.auctions.controller;

import com.example.auctions.model.Auction;
import com.example.auctions.model.AuctionStatus;
import com.example.auctions.model.Transaction;
import com.example.auctions.model.TransactionStatus;
import com.example.auctions.model.User;
import com.example.auctions.model.UserRole;
import com.example.auctions.model.WalletTransaction;
import com.example.auctions.model.WalletTransactionType;
import com.example.auctions.service.AdminService;
import com.example.auctions.service.AuctionService;
import com.example.auctions.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
@RequestMapping("/admin")
public class AdminUIController {
    private static final int DEFAULT_PAGE_SIZE = 10;

    private final AdminService adminService;
    private final UserService userService;
    private final AuctionService auctionService;

    @Autowired
    public AdminUIController(AdminService adminService,
                             UserService userService,
                             AuctionService auctionService) {
        this.adminService = adminService;
        this.userService = userService;
        this.auctionService = auctionService;
    }

    @GetMapping
    public String showDashboard(@AuthenticationPrincipal User user, Model model) {
        model.addAttribute("user", user);
        model.addAttribute("stats", adminService.getDashboardStats());
        model.addAttribute("recentUsers", adminService.getRecentUsers());
        model.addAttribute("recentAuctions", adminService.getRecentAuctions());
        return "admin/dashboard";
    }

    @GetMapping("/users")
    public String showUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "all") String enabled,
            @RequestParam(defaultValue = "1") int page,
            @AuthenticationPrincipal User currentAdmin,
            Model model) {
        int currentPage = Math.max(1, page);
        Page<User> userPage = adminService.getUsers(
                keyword,
                parseRole(role),
                parseEnabled(enabled),
                currentPage - 1,
                DEFAULT_PAGE_SIZE
        );

        model.addAttribute("users", userPage.getContent());
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", Math.max(1, userPage.getTotalPages()));
        model.addAttribute("currentKeyword", keyword == null ? "" : keyword);
        model.addAttribute("currentRole", normalizeFilter(role, "all"));
        model.addAttribute("currentEnabled", normalizeFilter(enabled, "all"));
        model.addAttribute("currentAdminId", resolveCurrentAdminId(currentAdmin));
        model.addAttribute("userRoles", UserRole.values());
        return "admin/users";
    }

    @PostMapping("/users/{id}/toggle-status")
    public String toggleUserStatus(
            @PathVariable Long id,
            @AuthenticationPrincipal User currentAdmin,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "all") String enabled,
            RedirectAttributes redirectAttributes) {
        try {
            User updatedUser = userService.toggleUserEnabled(id, currentAdmin.getId());
            String statusLabel = updatedUser.isEnabled() ? "enabled" : "disabled";
            redirectAttributes.addFlashAttribute("success",
                    "User " + updatedUser.getEmail() + " was " + statusLabel + " successfully.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }

        return "redirect:" + buildUsersRedirect(page, keyword, role, enabled);
    }

    @GetMapping("/users/{id}")
    public String showUserDetail(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "all") String enabled,
            Model model) {
        AdminService.UserDetailView detail = adminService.getUserDetail(id);
        model.addAttribute("detail", detail);
        model.addAttribute("backUrl", buildUsersRedirect(page, keyword, role, enabled));
        model.addAttribute("currentPage", Math.max(1, page));
        model.addAttribute("currentKeyword", keyword == null ? "" : keyword);
        model.addAttribute("currentRole", normalizeFilter(role, "all"));
        model.addAttribute("currentEnabled", normalizeFilter(enabled, "all"));
        return "admin/user-detail";
    }

    @GetMapping("/auctions")
    public String showAuctions(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "1") int page,
            Model model) {
        int currentPage = Math.max(1, page);
        Page<Auction> auctionPage = adminService.getAuctions(
                keyword,
                parseAuctionStatus(status),
                currentPage - 1,
                DEFAULT_PAGE_SIZE
        );

        model.addAttribute("auctions", auctionPage.getContent());
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", Math.max(1, auctionPage.getTotalPages()));
        model.addAttribute("currentKeyword", keyword == null ? "" : keyword);
        model.addAttribute("currentStatus", normalizeFilter(status, "all"));
        model.addAttribute("auctionStatuses", AuctionStatus.values());
        return "admin/auctions";
    }

    @GetMapping("/auctions/{id}")
    public String showAuctionDetail(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "all") String status,
            Model model) {
        AdminService.AuctionDetailView detail = adminService.getAuctionDetail(id);
        model.addAttribute("detail", detail);
        model.addAttribute("backUrl", buildAuctionsRedirect(page, keyword, status));
        model.addAttribute("currentPage", Math.max(1, page));
        model.addAttribute("currentKeyword", keyword == null ? "" : keyword);
        model.addAttribute("currentStatus", normalizeFilter(status, "all"));
        return "admin/auction-detail";
    }

    @PostMapping("/auctions/{id}/cancel")
    public String cancelAuction(
            @PathVariable Long id,
            @AuthenticationPrincipal User currentAdmin,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "all") String status,
            RedirectAttributes redirectAttributes) {
        try {
            Auction updatedAuction = auctionService.cancelAuctionByAdmin(id, currentAdmin);
            redirectAttributes.addFlashAttribute("success",
                    "Auction \"" + updatedAuction.getProductName() + "\" was cancelled.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }

        return "redirect:" + buildAuctionsRedirect(page, keyword, status);
    }

    @GetMapping("/transactions")
    public String showTransactions(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) Long auctionId,
            @RequestParam(required = false) Long transactionId,
            @RequestParam(defaultValue = "1") int page,
            Model model) {
        int currentPage = Math.max(1, page);
        Page<Transaction> transactionPage = adminService.getTransactions(
                keyword,
                parseTransactionStatus(status),
                auctionId,
                transactionId,
                currentPage - 1,
                DEFAULT_PAGE_SIZE
        );

        model.addAttribute("stats", adminService.getTransactionStats());
        model.addAttribute("transactions", transactionPage.getContent());
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", Math.max(1, transactionPage.getTotalPages()));
        model.addAttribute("currentKeyword", keyword == null ? "" : keyword);
        model.addAttribute("currentStatus", normalizeFilter(status, "all"));
        model.addAttribute("currentAuctionId", auctionId);
        model.addAttribute("currentTransactionId", transactionId);
        model.addAttribute("transactionStatuses", TransactionStatus.values());
        return "admin/transactions";
    }

    private Long resolveCurrentAdminId(User currentAdmin) {
        if (currentAdmin != null) {
            return currentAdmin.getId();
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof User user) {
            return user.getId();
        }
        return null;
    }

    @GetMapping("/wallet-transactions")
    public String showWalletAudit(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "all") String type,
            @RequestParam(required = false) Long transactionId,
            @RequestParam(required = false) Long auctionId,
            @RequestParam(defaultValue = "1") int page,
            Model model) {
        int currentPage = Math.max(1, page);
        Page<WalletTransaction> walletPage = adminService.getWalletTransactions(
                keyword,
                parseWalletTransactionType(type),
                transactionId,
                auctionId,
                currentPage - 1,
                DEFAULT_PAGE_SIZE
        );

        model.addAttribute("stats", adminService.getWalletAuditStats());
        model.addAttribute("walletTransactions", walletPage.getContent());
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", Math.max(1, walletPage.getTotalPages()));
        model.addAttribute("currentKeyword", keyword == null ? "" : keyword);
        model.addAttribute("currentType", normalizeFilter(type, "all"));
        model.addAttribute("currentTransactionId", transactionId);
        model.addAttribute("currentAuctionId", auctionId);
        model.addAttribute("walletTransactionTypes", WalletTransactionType.values());
        return "admin/wallet-audit";
    }

    private UserRole parseRole(String role) {
        if (role == null || role.isBlank() || "all".equalsIgnoreCase(role)) {
            return null;
        }
        return UserRole.valueOf(role.toUpperCase());
    }

    private Boolean parseEnabled(String enabled) {
        if (enabled == null || enabled.isBlank() || "all".equalsIgnoreCase(enabled)) {
            return null;
        }
        if ("enabled".equalsIgnoreCase(enabled)) {
            return true;
        }
        if ("disabled".equalsIgnoreCase(enabled)) {
            return false;
        }
        return null;
    }

    private AuctionStatus parseAuctionStatus(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) {
            return null;
        }
        return AuctionStatus.valueOf(status.toUpperCase());
    }

    private TransactionStatus parseTransactionStatus(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) {
            return null;
        }
        return TransactionStatus.valueOf(status.toUpperCase());
    }

    private WalletTransactionType parseWalletTransactionType(String type) {
        if (type == null || type.isBlank() || "all".equalsIgnoreCase(type)) {
            return null;
        }
        return WalletTransactionType.valueOf(type.toUpperCase());
    }

    private String normalizeFilter(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.toLowerCase();
    }

    private String buildUsersRedirect(int page, String keyword, String role, String enabled) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/admin/users")
                .queryParam("page", Math.max(1, page));
        if (keyword != null && !keyword.isBlank()) {
            builder.queryParam("keyword", keyword);
        }
        if (role != null && !role.isBlank() && !"all".equalsIgnoreCase(role)) {
            builder.queryParam("role", role.toLowerCase());
        }
        if (enabled != null && !enabled.isBlank() && !"all".equalsIgnoreCase(enabled)) {
            builder.queryParam("enabled", enabled.toLowerCase());
        }
        return builder.build().toUriString();
    }

    private String buildAuctionsRedirect(int page, String keyword, String status) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/admin/auctions")
                .queryParam("page", Math.max(1, page));
        if (keyword != null && !keyword.isBlank()) {
            builder.queryParam("keyword", keyword);
        }
        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            builder.queryParam("status", status.toLowerCase());
        }
        return builder.build().toUriString();
    }
}
