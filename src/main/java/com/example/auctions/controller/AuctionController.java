package com.example.auctions.controller;

import com.example.auctions.model.Auction;
import com.example.auctions.model.AuctionStatus;
import com.example.auctions.model.AuctionVisibility;
import com.example.auctions.model.TransactionStatus;
import com.example.auctions.model.User;
import com.example.auctions.model.UserRole;
import com.example.auctions.service.AuctionService;
import jakarta.servlet.http.HttpSession;
import com.example.auctions.repository.TransactionRepository;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Controller
@RequestMapping("/auctions")
public class AuctionController {

    private static final Set<String> ALLOWED_CONTENT_TYPES = new HashSet<>(Arrays.asList(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "image/bmp"));

    @Value("${spring.servlet.multipart.max-file-size}")
    private String maxFileSize;

    @Autowired
    private AuctionService auctionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @GetMapping
    public String listAuctions(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            Model model) {
        if (user.getRole() == UserRole.ADMIN) {
            return "redirect:/admin/auctions";
        }

        // Buyers should not access seller's "My Auctions" page
        if (user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BUYER"))) {
            return "redirect:/auctions/browse";
        }
        final int PAGE_SIZE = 9;
        int pageIndex = Math.max(0, page - 1);
        Page<Auction> auctionPage;

        if (status != null) {
            switch (status.toLowerCase()) {
                case "draft":
                    auctionPage = auctionService.getAuctionsBySellerAndStatus(user, AuctionStatus.DRAFT, pageIndex,
                            PAGE_SIZE);
                    break;
                case "active":
                    auctionPage = auctionService.getAuctionsBySellerAndStatus(user, AuctionStatus.ACTIVE, pageIndex,
                            PAGE_SIZE);
                    break;
                case "ended":
                    auctionPage = auctionService.getAuctionsBySellerAndStatus(user, AuctionStatus.ENDED, pageIndex,
                            PAGE_SIZE);
                    break;
                case "cancelled":
                    auctionPage = auctionService.getAuctionsBySellerAndStatus(user, AuctionStatus.CANCELLED, pageIndex,
                            PAGE_SIZE);
                    break;
                default:
                    auctionPage = auctionService.getAuctionsBySeller(user, pageIndex, PAGE_SIZE);
                    break;
            }
        } else {
            auctionPage = auctionService.getAuctionsBySeller(user, pageIndex, PAGE_SIZE);
        }

        model.addAttribute("auctions", auctionPage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", auctionPage.getTotalPages());
        model.addAttribute("currentStatus", status);
        return "auctions/my-auctions";
    }

    @GetMapping("/create")
    public String showCreateForm(Model model) {
        populateAuctionForm(model, new Auction());
        return "auctions/create";
    }

    @PostMapping("/create")
    public String createAuction(
            @AuthenticationPrincipal User user,
            @Valid @ModelAttribute Auction auction,
            BindingResult result,
            @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
            @RequestParam("action") String action,
            RedirectAttributes redirectAttributes,
            Model model) {

        validatePublishEndTime(action, auction, result);
        validatePrivateAccessCode(auction, result);

        // Validate image presence
        if (imageFile == null || imageFile.isEmpty()) {
            result.rejectValue("imageFile", "error.image", "Image is required");
            populateAuctionForm(model, auction);
            return "auctions/create";
        }

        // Validate image type
        String contentType = imageFile.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            result.rejectValue("imageFile", "error.image",
                    "Only JPG, PNG, GIF, WebP and BMP images are allowed");
            populateAuctionForm(model, auction);
            return "auctions/create";
        }

        // Validate file size (Spring will handle this automatically based on
        // application.properties,
        // but we can add a custom message)
        if (imageFile.getSize() > 20 * 1024 * 1024) { // 20MB in bytes
            result.rejectValue("imageFile", "error.image",
                    "Image size must be less than " + maxFileSize);
            populateAuctionForm(model, auction);
            return "auctions/create";
        }

        if (result.hasErrors()) {
            populateAuctionForm(model, auction);
            return "auctions/create";
        }

        try {
            auction.setSeller(user);
            auction.setImageFile(imageFile);
            auctionService.createAuction(auction, action);
            redirectAttributes.addFlashAttribute("success",
                    "publish".equals(action) ? "Auction published successfully" : "Auction saved as draft");
            return "redirect:/auctions";
        } catch (IOException e) {
            result.rejectValue("imageFile", "error.image", "Failed to upload image. Please try again.");
            populateAuctionForm(model, auction);
            return "auctions/create";
        } catch (Exception e) {
            model.addAttribute("error", e.getMessage());
            populateAuctionForm(model, auction);
            return "auctions/create";
        }
    }

    @GetMapping("/edit/{id}")
    public String showEditForm(@PathVariable Long id, @AuthenticationPrincipal User user, Model model,
            RedirectAttributes redirectAttributes) {
        Auction auction = auctionService.getAuctionById(id);

        // Check if user owns this auction
        if (!auction.getSeller().getId().equals(user.getId())) {
            throw new RuntimeException("You don't have permission to edit this auction");
        }

        // Only DRAFT auctions can be edited
        if (auction.getStatus() != AuctionStatus.DRAFT) {
            redirectAttributes.addFlashAttribute("error", "Only DRAFT auctions can be edited");
            return "redirect:/auctions/my";
        }

        populateAuctionForm(model, auction);
        return "auctions/edit";
    }

    @PostMapping("/edit/{id}")
    public String updateAuction(
            @PathVariable Long id,
            @AuthenticationPrincipal User user,
            @Valid @ModelAttribute Auction auction,
            BindingResult result,
            @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
            @RequestParam("action") String action,
            RedirectAttributes redirectAttributes,
            Model model) {

        System.out.println("Received update request for auction ID: " + id);
        System.out.println("Action: " + action);

        // Get existing auction first
        Auction existingAuction = auctionService.getAuctionById(id);

        // Check if user owns this auction
        if (!existingAuction.getSeller().getId().equals(user.getId())) {
            throw new RuntimeException("You don't have permission to edit this auction");
        }

        // Only DRAFT auctions can be edited/published/deleted
        if (existingAuction.getStatus() != AuctionStatus.DRAFT) {
            redirectAttributes.addFlashAttribute("error", "Only DRAFT auctions can be edited");
            return "redirect:/auctions/my";
        }

        try {
            // Handle different actions first
            if ("delete".equals(action)) {
                auctionService.deleteAuction(existingAuction);
                redirectAttributes.addFlashAttribute("success", "Auction deleted successfully");
                return "redirect:/auctions";
            }

            // Set existing values that shouldn't change
            auction.setId(id);
            auction.setVersion(existingAuction.getVersion());
            auction.setSeller(existingAuction.getSeller());
            auction.setCreatedAt(existingAuction.getCreatedAt());
            validatePublishEndTime(action, auction, result);

            // Handle validation errors
            if (result.hasErrors()) {
                populateAuctionForm(model, auction);
                return "auctions/edit";
            }

            // Handle image
            if (imageFile != null && !imageFile.isEmpty()) {
                // Validate image type
                String contentType = imageFile.getContentType();
                if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
                    result.rejectValue("imageFile", "error.image",
                            "Only JPG, PNG, GIF, WebP and BMP images are allowed");
                    populateAuctionForm(model, auction);
                    return "auctions/edit";
                }
                auction.setImageFile(imageFile);
            } else {
                auction.setImage(existingAuction.getImage());
            }

            // Handle publish action
            if ("publish".equals(action)) {
                if (existingAuction.getStatus() != AuctionStatus.DRAFT) {
                    model.addAttribute("error", "Only draft auctions can be published");
                    populateAuctionForm(model, auction);
                    return "auctions/edit";
                }
                auction.setStatus(AuctionStatus.ACTIVE);
            } else {
                // For regular updates, keep the existing status
                auction.setStatus(existingAuction.getStatus());
            }

            // Update the auction
            auctionService.updateAuction(auction, action);

            String successMessage = "publish".equals(action) ? "Auction published successfully"
                    : "Auction updated successfully";
            redirectAttributes.addFlashAttribute("success", successMessage);
            return "redirect:/auctions";

        } catch (IOException e) {
            result.rejectValue("imageFile", "error.image", "Failed to upload image. Please try again.");
            populateAuctionForm(model, auction);
            return "auctions/edit";
        } catch (Exception e) {
            model.addAttribute("error", e.getMessage());
            populateAuctionForm(model, auction);
            return "auctions/edit";
        }
    }

    @PostMapping("/delete/{id}")
    public String deleteAuction(
            @PathVariable Long id,
            @AuthenticationPrincipal User user,
            RedirectAttributes redirectAttributes) {

        try {
            Auction auction = auctionService.getAuctionById(id);

            // Check if user owns this auction
            if (!auction.getSeller().getId().equals(user.getId())) {
                throw new RuntimeException("You don't have permission to delete this auction");
            }

            auctionService.deleteAuction(auction);
            redirectAttributes.addFlashAttribute("success", "Auction deleted successfully");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }

        return "redirect:/auctions";
    }

    @GetMapping("/{id}")
    public String viewAuction(
            @PathVariable Long id,
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            HttpSession session,
            Model model) {
        Auction auction = auctionService.getAuctionById(id);
        boolean isOwner = user != null && auction.getSeller().getId().equals(user.getId());
        boolean hasPrivateAccess = auctionService.canAccessPrivateAuction(auction, user, session);
        boolean privateAccessRequired = auctionService.isPrivateAuction(auction) && !hasPrivateAccess;

        // Check if transaction is already paid
        boolean isPaid = transactionRepository.findByAuctionId(id)
                .map(t -> TransactionStatus.COMPLETED.equals(t.getStatus()))
                .orElse(false);

        model.addAttribute("auction", auction);
        model.addAttribute("isOwner", isOwner);
        model.addAttribute("isPaid", isPaid);
        model.addAttribute("hasPrivateAccess", hasPrivateAccess);
        model.addAttribute("privateAccessRequired", privateAccessRequired);
        model.addAttribute("adminBackUrl", buildAdminBackUrl(user, source, page, keyword, status));

        // Pass remaining unlock attempts so UI can disable form when blocked
        if (privateAccessRequired && user != null) {
            model.addAttribute("unlockAttemptsRemaining",
                    auctionService.getRemainingUnlockAttempts(id, user.getId()));
        }

        return "auctions/details";
    }

    @PostMapping("/{id}/unlock")
    public String unlockPrivateAuction(
            @PathVariable Long id,
            @AuthenticationPrincipal User user,
            @RequestParam("accessCode") String accessCode,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        Auction auction = auctionService.getAuctionById(id);

        if (!auctionService.isPrivateAuction(auction) || user.getRole() == UserRole.ADMIN
                || auction.getSeller().getId().equals(user.getId())) {
            return "redirect:/auctions/" + id;
        }

        if (auctionService.unlockPrivateAuction(auction, accessCode, session, user.getId())) {
            redirectAttributes.addFlashAttribute("success", "Private access unlocked. You can now join this auction.");
        } else {
            int remaining = auctionService.getRemainingUnlockAttempts(id, user.getId());
            if (remaining <= 0) {
                redirectAttributes.addFlashAttribute("error",
                        "Too many failed attempts. Please try again in 15 minutes.");
            } else {
                redirectAttributes.addFlashAttribute("error",
                        "Invalid access code. " + remaining + " attempt(s) remaining.");
            }
        }

        return "redirect:/auctions/" + id;
    }

    private void populateAuctionForm(Model model, Auction auction) {
        model.addAttribute("auction", auction);
        model.addAttribute("maxFileSize", maxFileSize);
        model.addAttribute("auctionVisibilities", AuctionVisibility.values());
        model.addAttribute("draftEndTimeExpired",
                auction.getStatus() == AuctionStatus.DRAFT
                        && auction.getEndTime() != null
                        && !auction.getEndTime().isAfter(LocalDateTime.now()));
    }

    private void validatePrivateAccessCode(Auction auction, BindingResult result) {
        if (auction.getVisibility() == AuctionVisibility.PRIVATE
                && (auction.getAccessCode() == null || auction.getAccessCode().trim().isEmpty())) {
            result.rejectValue("accessCode", "error.accessCode", "Private auctions require an access code.");
        }
    }

    private void validatePublishEndTime(String action, Auction auction, BindingResult result) {
        if (!"publish".equalsIgnoreCase(action)) {
            return;
        }

        if (auction.getEndTime() == null || !auction.getEndTime().isAfter(LocalDateTime.now())) {
            result.rejectValue("endTime", "error.endTime",
                    "Set a future end time before publishing this auction.");
        }
    }

    private String buildAdminBackUrl(User user, String source, Integer page, String keyword, String status) {
        if (user == null || user.getRole() != UserRole.ADMIN) {
            return null;
        }

        if (source != null && !source.equalsIgnoreCase("admin")) {
            return "/admin/auctions";
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/admin/auctions");
        if (page != null && page > 0) {
            builder.queryParam("page", page);
        }
        if (keyword != null && !keyword.isBlank()) {
            builder.queryParam("keyword", keyword);
        }
        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            builder.queryParam("status", status.toLowerCase());
        }

        return builder.build().toUriString();
    }

}
