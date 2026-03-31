package com.example.auctions.service;

import com.example.auctions.model.Auction;
import com.example.auctions.model.AuctionAccessCode;
import com.example.auctions.model.AuctionStatus;
import com.example.auctions.model.AuctionVisibility;
import com.example.auctions.model.Bid;
import com.example.auctions.model.PrivateAuctionAccess;
import com.example.auctions.model.User;
import com.example.auctions.model.UserRole;
import com.example.auctions.repository.AuctionAccessCodeRepository;
import com.example.auctions.repository.AuctionRepository;
import com.example.auctions.repository.BidRepository;
import com.example.auctions.repository.PrivateAuctionAccessRepository;
import com.example.auctions.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.annotation.JsonProperty;

@Service
public class AuctionService {

    private static final Logger logger = LoggerFactory.getLogger(AuctionService.class);

    // User-based unlock attempt tracking (survives session changes, fair per-user)
    private static final int MAX_UNLOCK_ATTEMPTS_PER_USER = 10;
    private static final long UNLOCK_WINDOW_MS = 15 * 60 * 1000; // 15 minutes
    private final ConcurrentHashMap<String, UnlockAttemptCounter> unlockAttemptsByUser = new ConcurrentHashMap<>();

    private static class UnlockAttemptCounter {
        final long windowStart;
        final AtomicInteger count;
        UnlockAttemptCounter(long windowStart) {
            this.windowStart = windowStart;
            this.count = new AtomicInteger(0);
        }
    }

    @Value("${auction.images.upload.path}")
    private String uploadPath;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private AuctionAccessCodeRepository auctionAccessCodeRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private EmailService emailService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private PrivateAuctionAccessRepository privateAuctionAccessRepository;

    @Autowired
    private UserRepository userRepository;

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(Paths.get(uploadPath));
        } catch (IOException e) {
            throw new RuntimeException("Could not create upload directory!", e);
        }
    }

    public List<Auction> getAuctionsBySeller(User seller) {
        return auctionRepository.findBySeller(seller, Pageable.unpaged()).getContent();
    }

    public List<Auction> getAuctionsBySellerAndStatus(User seller, AuctionStatus status) {
        return auctionRepository.findBySellerAndStatusOrderByEndTimeDesc(seller, status);
    }

    public Auction getAuctionById(Long id) {
        return auctionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Auction not found"));
    }

    public Page<Auction> getActiveAuctions(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        return auctionRepository.findByStatus(AuctionStatus.ACTIVE, pageRequest);
    }

    public Page<Auction> searchActiveAuctions(int page,
                                              int size,
                                              String keyword,
                                              String visibility,
                                              String sort) {
        Pageable pageable = PageRequest.of(page, size, resolveBrowseSort(sort));
        String normalizedKeyword = normalizeBrowseKeyword(keyword);
        AuctionVisibility resolvedVisibility = resolveBrowseVisibility(visibility);

        // Allow browsing both public and private auctions
        // Private auction details are hidden in the UI until unlocked
        // visibility=null means "all" (no filter)

        return auctionRepository.searchActiveAuctions(
                AuctionStatus.ACTIVE,
                LocalDateTime.now(),
                normalizedKeyword,
                resolvedVisibility,
                pageable);
    }

    @Transactional
    public Auction createAuction(Auction auction, String action) throws IOException {
        validatePrivateAuctionAccessCode(auction);

        // Handle image upload
        if (auction.getImageFile() != null && !auction.getImageFile().isEmpty()) {
            String imageName = saveImage(auction.getImageFile());
            auction.setImage(imageName);
        }

        // Set status based on action
        if ("publish".equals(action)) {
            auction.setStatus(AuctionStatus.ACTIVE);
            // Snapshot seller info at publish time
            if (auction.getSeller() != null) {
                auction.setSellerNameSnapshot(auction.getSeller().getFullName());
                auction.setSellerEmailSnapshot(auction.getSeller().getEmail());
            }
        } else {
            auction.setStatus(AuctionStatus.DRAFT);
        }

        Auction savedAuction = auctionRepository.save(auction);
        syncAccessCode(savedAuction, auction.getAccessCode());
        return savedAuction;
    }

    @Transactional
    public Auction updateAuction(Auction auction, String action) throws IOException {
        Auction existingAuction = auctionRepository.findByIdForUpdate(auction.getId())
                .orElseThrow(() -> new RuntimeException("Auction not found"));
        String currentAccessCode = getAccessCodeForAuction(existingAuction.getId());

        // Only allow updates if auction is in DRAFT status
        if (existingAuction.getStatus() != AuctionStatus.DRAFT) {
            throw new RuntimeException("Only DRAFT auctions can be edited");
        }

        // Handle new image if provided
        if (auction.getImageFile() != null && !auction.getImageFile().isEmpty()) {
            // Delete old image if exists
            if (existingAuction.getImage() != null) {
                deleteImage(existingAuction.getImage());
            }
            // Save new image
            String imageName = saveImage(auction.getImageFile());
            auction.setImage(imageName);
        } else {
            auction.setImage(existingAuction.getImage());
        }

        // Handle status changes
        if ("publish".equals(action) && existingAuction.getStatus() == AuctionStatus.DRAFT) {
            existingAuction.setStatus(AuctionStatus.ACTIVE);
            // Snapshot seller info at publish time
            existingAuction.setSellerNameSnapshot(existingAuction.getSeller().getFullName());
            existingAuction.setSellerEmailSnapshot(existingAuction.getSeller().getEmail());
        } else if ("delete".equals(action) && existingAuction.getStatus() == AuctionStatus.DRAFT) {
            deleteAuction(existingAuction);
            return null;
        } else {
            existingAuction.setStatus(existingAuction.getStatus());
        }

        String resolvedAccessCode = resolveAccessCodeForUpdate(auction, existingAuction, currentAccessCode);

        existingAuction.setProductName(auction.getProductName());
        existingAuction.setDescription(auction.getDescription());
        existingAuction.setStartingPrice(auction.getStartingPrice());
        existingAuction.setEndTime(auction.getEndTime());
        existingAuction.setImage(auction.getImage());
        existingAuction.setVisibility(auction.getVisibility());
        existingAuction.setAccessCode(auction.getAccessCode());

        Auction updatedAuction = auctionRepository.save(existingAuction);
        syncAccessCode(updatedAuction, resolvedAccessCode);
        return updatedAuction;
    }

    @Transactional
    public void deleteAuction(Auction auction) {
        if (auction.getStatus() != AuctionStatus.DRAFT) {
            throw new RuntimeException("Can only delete draft auctions");
        }

        // Delete image file if exists
        if (auction.getImage() != null) {
            deleteImage(auction.getImage());
        }

        // Delete auction from database
        auctionAccessCodeRepository.deleteByAuctionId(auction.getId());
        auctionRepository.delete(auction);
    }

    public boolean isPrivateAuction(Auction auction) {
        return auction != null && auction.getVisibility() == AuctionVisibility.PRIVATE;
    }

    public boolean canAccessPrivateAuction(Auction auction, User user, HttpSession session) {
        if (!isPrivateAuction(auction)) {
            return true;
        }

        if (user != null && (user.getRole() == UserRole.ADMIN || auction.getSeller().getId().equals(user.getId()))) {
            return true;
        }

        return session != null && Boolean.TRUE.equals(session.getAttribute(buildPrivateAccessSessionKey(auction.getId())));
    }

    private static final int MAX_UNLOCK_ATTEMPTS_SESSION = 5;

    public boolean unlockPrivateAuction(Auction auction, String submittedCode, HttpSession session, Long userId) {
        if (!isPrivateAuction(auction)) {
            return true;
        }

        String normalizedSubmittedCode = normalizeAccessCode(submittedCode);
        if (normalizedSubmittedCode == null || session == null || userId == null) {
            return false;
        }

        // User-based rate limit (survives session/incognito changes, fair per-user)
        String userKey = "user:" + userId + ":unlock:" + auction.getId();
        UnlockAttemptCounter counter = unlockAttemptsByUser.compute(userKey, (k, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null || now - existing.windowStart > UNLOCK_WINDOW_MS) {
                return new UnlockAttemptCounter(now);
            }
            return existing;
        });
        if (counter.count.get() >= MAX_UNLOCK_ATTEMPTS_PER_USER) {
            logger.warn("User {} blocked from unlock attempts on auction {} (limit reached)", userId, auction.getId());
            return false;
        }

        boolean matched = auctionAccessCodeRepository.findByAuctionIdAndEnabledTrue(auction.getId())
                .map(AuctionAccessCode::getAccessCode)
                .map(this::normalizeAccessCode)
                .filter(normalizedSubmittedCode::equalsIgnoreCase)
                .isPresent();

        if (matched) {
            session.setAttribute(buildPrivateAccessSessionKey(auction.getId()), Boolean.TRUE);
            return true;
        } else {
            counter.count.incrementAndGet();
            return false;
        }
    }

    public int getRemainingUnlockAttempts(Long auctionId, Long userId) {
        String userKey = "user:" + userId + ":unlock:" + auctionId;
        UnlockAttemptCounter counter = unlockAttemptsByUser.get(userKey);
        if (counter == null || System.currentTimeMillis() - counter.windowStart > UNLOCK_WINDOW_MS) {
            return MAX_UNLOCK_ATTEMPTS_PER_USER;
        }
        return Math.max(0, MAX_UNLOCK_ATTEMPTS_PER_USER - counter.count.get());
    }

    // --- Stateless (JWT/API) variants ---

    public boolean canAccessPrivateAuctionStateless(Auction auction, User user) {
        if (!isPrivateAuction(auction)) {
            return true;
        }
        if (user != null && (user.getRole() == UserRole.ADMIN || auction.getSeller().getId().equals(user.getId()))) {
            return true;
        }
        if (user == null) {
            return false;
        }
        return privateAuctionAccessRepository.existsByUserIdAndAuctionId(user.getId(), auction.getId());
    }

    @Transactional
    public boolean unlockPrivateAuctionStateless(Auction auction, String submittedCode, Long userId) {
        if (!isPrivateAuction(auction)) {
            return true;
        }

        String normalizedSubmittedCode = normalizeAccessCode(submittedCode);
        if (normalizedSubmittedCode == null || userId == null) {
            return false;
        }

        // User-based rate limit (reuse same ConcurrentHashMap as session-based)
        String userKey = "user:" + userId + ":unlock:" + auction.getId();
        UnlockAttemptCounter counter = unlockAttemptsByUser.compute(userKey, (k, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null || now - existing.windowStart > UNLOCK_WINDOW_MS) {
                return new UnlockAttemptCounter(now);
            }
            return existing;
        });
        if (counter.count.get() >= MAX_UNLOCK_ATTEMPTS_PER_USER) {
            logger.warn("User {} blocked from stateless unlock on auction {} (limit reached)", userId, auction.getId());
            return false;
        }

        boolean matched = auctionAccessCodeRepository.findByAuctionIdAndEnabledTrue(auction.getId())
                .map(AuctionAccessCode::getAccessCode)
                .map(this::normalizeAccessCode)
                .filter(normalizedSubmittedCode::equalsIgnoreCase)
                .isPresent();

        if (matched) {
            // Check if already unlocked
            if (!privateAuctionAccessRepository.existsByUserIdAndAuctionId(userId, auction.getId())) {
                User user = userRepository.findById(userId).orElseThrow(() -> new RuntimeException("User not found"));
                PrivateAuctionAccess access = new PrivateAuctionAccess();
                access.setUser(user);
                access.setAuction(auction);
                privateAuctionAccessRepository.save(access);
            }
            return true;
        } else {
            counter.count.incrementAndGet();
            return false;
        }
    }

    public String getAccessCodeForAuction(Long auctionId) {
        return auctionAccessCodeRepository.findByAuctionIdAndEnabledTrue(auctionId)
                .map(AuctionAccessCode::getAccessCode)
                .orElse("");
    }

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp");

    private String saveImage(MultipartFile image) throws IOException {
        // Extract and validate extension
        String originalFilename = image.getOriginalFilename();
        String extension = ".jpg"; // default
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        }
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IOException("File type not allowed. Accepted: " + ALLOWED_EXTENSIONS);
        }

        // Validate magic bytes - first few bytes should match image format
        byte[] header = new byte[12];
        try (var is = image.getInputStream()) {
            int read = is.read(header);
            if (read < 4 || !isImageMagicBytes(header)) {
                throw new IOException("File does not appear to be a valid image");
            }
        }

        String filename = UUID.randomUUID().toString() + extension;

        // Normalize and verify path stays within upload directory
        Path uploadRoot = Paths.get(uploadPath).toAbsolutePath().normalize();
        Path destinationPath = uploadRoot.resolve(filename).normalize();
        if (!destinationPath.startsWith(uploadRoot)) {
            throw new IOException("Invalid file path");
        }

        Files.createDirectories(destinationPath.getParent());
        Files.copy(image.getInputStream(), destinationPath, StandardCopyOption.REPLACE_EXISTING);

        return filename;
    }

    private boolean isImageMagicBytes(byte[] header) {
        // JPEG: FF D8 FF
        if (header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 && header[2] == (byte) 0xFF) return true;
        // PNG: 89 50 4E 47
        if (header[0] == (byte) 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47) return true;
        // GIF: 47 49 46 38
        if (header[0] == 0x47 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x38) return true;
        // BMP: 42 4D
        if (header[0] == 0x42 && header[1] == 0x4D) return true;
        // WebP: RIFF....WEBP
        if (header[0] == 0x52 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x46
                && header.length >= 12 && header[8] == 0x57 && header[9] == 0x45 && header[10] == 0x42 && header[11] == 0x50) return true;
        return false;
    }

    private void deleteImage(String imageName) {
        try {
            Path imagePath = Paths.get(uploadPath).resolve(imageName);
            Files.deleteIfExists(imagePath);
        } catch (IOException e) {
            // Log error but don't throw exception
            e.printStackTrace();
        }
    }

    @Scheduled(fixedRate = 5000) // Run every 5 seconds
    @Transactional
    public void updateExpiredAuctions() {
        LocalDateTime now = LocalDateTime.now();
        List<Auction> activeAuctions = auctionRepository.findByStatusAndEndTimeBefore(AuctionStatus.ACTIVE, now);

        for (Auction auction : activeAuctions) {
            try {
                endAuction(auction);
            } catch (Exception e) {
                logger.error("Error ending auction {}: {}", auction.getId(), e.getMessage(), e);
            }
        }
    }

    @Transactional
    public void endAuction(Auction auction) {
        logger.info("Starting to end auction ID: {}", auction.getId());
        
        // Find the highest bid
        Optional<Bid> highestBid = bidRepository.findTopByAuctionOrderByAmountDesc(auction);
        logger.debug("Highest bid found: {}", highestBid.isPresent() ? highestBid.get().getAmount() : "No bids");
        
        // Update auction status and winner
        auction.setStatus(AuctionStatus.ENDED);
        final Auction finalAuction = auction;
        highestBid.ifPresent(bid -> {
            finalAuction.setWinner(bid.getBidder());
            // Snapshot winner info at auction end time
            finalAuction.setWinnerNameSnapshot(bid.getBidder().getFullName());
            finalAuction.setWinnerEmailSnapshot(bid.getBidder().getEmail());
            logger.info("Setting winner for auction {}: {}", finalAuction.getId(), bid.getBidder().getEmail());
        });
        
        // Save the auction
        auction = auctionRepository.save(finalAuction);
        logger.debug("Auction saved with status ENDED");

        // Auto-create PENDING transaction for the winner to pay
        if (auction.getWinner() != null) {
            try {
                transactionService.findOrCreateTransaction(auction);
                logger.info("Auto-created PENDING transaction for auction {}", auction.getId());
            } catch (Exception e) {
                logger.error("Failed to create transaction for auction {}: {}", auction.getId(), e.getMessage(), e);
            }
        }
        
        // Send email notification to winner if exists
        if (auction.getWinner() != null) {
            try {
                logger.info("Attempting to send winner notification email");
                emailService.sendAuctionWonEmail(auction);
                logger.info("Winner notification email sent successfully");
            } catch (Exception e) {
                logger.error("Failed to send auction won email: " + e.getMessage(), e);
            }
        } else {
            logger.info("No winner to notify for auction ID: {}", auction.getId());
        }
        
        AuctionEndNotificationMessage privateMessage = new AuctionEndNotificationMessage(
            auction.getId(),
            auction.getProductName(),
            highestBid.map(Bid::getAmount).orElse(auction.getStartingPrice()),
            highestBid.map(bid -> bid.getBidder().getFullName()).orElse(null),
            highestBid.map(bid -> bid.getBidder().getId()).orElse(null),
            auction.getSeller().getId()
        );

        Set<String> recipients = new LinkedHashSet<>();
        recipients.add(auction.getSeller().getEmail());
        bidRepository.findByAuctionWithBidderOrderByBidTimeDesc(auction).stream()
                .map(bid -> bid.getBidder().getEmail())
                .forEach(recipients::add);

        for (String recipient : recipients) {
            messagingTemplate.convertAndSendToUser(recipient, "/queue/auction-end", privateMessage);
        }

        messagingTemplate.convertAndSend(
                "/topic/auction/" + auction.getId(),
                new AuctionPublicStateMessage(auction.getId(), AuctionStatus.ENDED.name()));
        logger.debug("WebSocket auction-end messages sent to {} participant(s)", recipients.size());
        
        logger.info("Auction ID: {} ended successfully", auction.getId());
    }

    public Page<Auction> getAuctionsBySeller(User seller, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        return auctionRepository.findBySeller(seller, pageRequest);
    }

    public Page<Auction> getAuctionsBySellerAndStatus(User seller, AuctionStatus status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        if (status == null) {
            return auctionRepository.findBySeller(seller, pageRequest);
        }
        return auctionRepository.findBySellerAndStatus(seller, status, pageRequest);
    }

    public List<Auction> getEndedAuctionsBySeller(User seller) {
        return auctionRepository.findBySellerAndStatusOrderByEndTimeDesc(seller, AuctionStatus.ENDED);
    }

    public List<Auction> getAuctionsByWinner(User winner) {
        return auctionRepository.findByWinner(winner);
    }

    // Seller Statistics Methods
    public long countActiveAuctionsBySeller(User seller) {
        return auctionRepository.countBySellerAndStatus(seller, AuctionStatus.ACTIVE);
    }

    public long countSuccessfulAuctionsBySeller(User seller) {
        return auctionRepository.countBySellerAndStatus(seller, AuctionStatus.ENDED);
    }

    public long countWatchedAuctions(User user) {
        return auctionRepository.countWatchedAuctionsByUser(user);
    }

    @Transactional
    public Auction cancelAuctionByAdmin(Long auctionId, User adminUser) {
        Auction auction = getAuctionById(auctionId);

        if (auction.getStatus() != AuctionStatus.ACTIVE) {
            throw new RuntimeException("Only active auctions can be cancelled by admin.");
        }

        auction.setStatus(AuctionStatus.CANCELLED);
        auction.setCancelledAt(LocalDateTime.now());
        auction.setCancelledBy(adminUser);
        auction.setCancelReason("Cancelled by admin moderation.");
        return auctionRepository.save(auction);
    }

    private void validatePrivateAuctionAccessCode(Auction auction) {
        if (auction.getVisibility() == AuctionVisibility.PRIVATE && normalizeAccessCode(auction.getAccessCode()) == null) {
            throw new RuntimeException("Private auctions require an access code before they can be saved.");
        }
    }

    private String resolveAccessCodeForUpdate(Auction updatedAuction, Auction existingAuction, String currentAccessCode) {
        if (updatedAuction.getVisibility() != AuctionVisibility.PRIVATE) {
            return null;
        }

        String submittedAccessCode = normalizeAccessCode(updatedAuction.getAccessCode());
        if (submittedAccessCode != null) {
            return submittedAccessCode;
        }

        String existingAccessCode = normalizeAccessCode(currentAccessCode);
        if (existingAuction.getVisibility() == AuctionVisibility.PRIVATE && existingAccessCode != null) {
            return existingAccessCode;
        }

        throw new RuntimeException("Private auctions require an access code before they can be saved.");
    }

    private void syncAccessCode(Auction auction, String accessCodeValue) {
        if (auction == null || auction.getId() == null) {
            return;
        }

        if (auction.getVisibility() != AuctionVisibility.PRIVATE) {
            auctionAccessCodeRepository.deleteByAuctionId(auction.getId());
            return;
        }

        String normalizedCode = normalizeAccessCode(accessCodeValue);
        AuctionAccessCode accessCode = auctionAccessCodeRepository.findByAuctionId(auction.getId())
                .orElseGet(AuctionAccessCode::new);
        accessCode.setAuction(auction);
        accessCode.setAccessCode(normalizedCode);
        accessCode.setEnabled(true);
        auctionAccessCodeRepository.save(accessCode);
    }

    private String normalizeAccessCode(String accessCode) {
        if (accessCode == null) {
            return null;
        }

        String trimmedCode = accessCode.trim();
        return trimmedCode.isEmpty() ? null : trimmedCode;
    }

    private String buildPrivateAccessSessionKey(Long auctionId) {
        return "auction-private-access:" + auctionId;
    }

    private String normalizeBrowseKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmedKeyword = keyword.trim();
        return trimmedKeyword.isEmpty() ? null : trimmedKeyword;
    }

    private AuctionVisibility resolveBrowseVisibility(String visibility) {
        if (visibility == null || visibility.isBlank() || "all".equalsIgnoreCase(visibility)) {
            return null;
        }

        try {
            return AuctionVisibility.valueOf(visibility.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Sort resolveBrowseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.ASC, "endTime");
        }

        return switch (sort) {
            case "newest" -> Sort.by(Sort.Direction.DESC, "createdAt");
            case "priceLow" -> Sort.by(Sort.Direction.ASC, "currentPrice");
            case "priceHigh" -> Sort.by(Sort.Direction.DESC, "currentPrice");
            case "nameAsc" -> Sort.by(Sort.Direction.ASC, "productName");
            default -> Sort.by(Sort.Direction.ASC, "endTime");
        };
    }

    private static class AuctionPublicStateMessage {
        private final Long auctionId;
        private final String status;

        private AuctionPublicStateMessage(Long auctionId, String status) {
            this.auctionId = auctionId;
            this.status = status;
        }

        @JsonProperty("auctionId")
        public Long getAuctionId() {
            return auctionId;
        }

        @JsonProperty("eventType")
        public String getEventType() {
            return "AUCTION_ENDED";
        }

        @JsonProperty("status")
        public String getStatus() {
            return status;
        }
    }

    // Inner class for targeted auction-end notifications
    private static class AuctionEndNotificationMessage {
        private final Long auctionId;
        private final String productName;
        private final java.math.BigDecimal finalPrice;
        private final String winnerName;
        private final Long winnerId;
        private final Long sellerId;
        private final LocalDateTime endTime;

        public AuctionEndNotificationMessage(Long auctionId, String productName, java.math.BigDecimal finalPrice, String winnerName, Long winnerId, Long sellerId) {
            this.auctionId = auctionId;
            this.productName = productName;
            this.finalPrice = finalPrice;
            this.winnerName = winnerName;
            this.winnerId = winnerId;
            this.sellerId = sellerId;
            this.endTime = LocalDateTime.now();
        }

        @JsonProperty("auctionId")
        public Long getAuctionId() {
            return auctionId;
        }

        @JsonProperty("productName")
        public String getProductName() {
            return productName;
        }

        @JsonProperty("finalPrice")
        public java.math.BigDecimal getFinalPrice() {
            return finalPrice;
        }

        @JsonProperty("winnerName")
        public String getWinnerName() {
            return winnerName;
        }

        @JsonProperty("winnerId")
        public Long getWinnerId() {
            return winnerId;
        }

        @JsonProperty("sellerId")
        public Long getSellerId() {
            return sellerId;
        }

        @JsonProperty("endTime")
        public LocalDateTime getEndTime() {
            return endTime;
        }

        @JsonProperty("eventType")
        public String getEventType() {
            return "AUCTION_ENDED_NOTIFICATION";
        }
    }
}
