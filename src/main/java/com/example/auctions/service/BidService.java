package com.example.auctions.service;

import com.example.auctions.dto.UserBidGroupDTO;
import com.example.auctions.model.Auction;
import com.example.auctions.model.AuctionStatus;
import com.example.auctions.model.Bid;
import com.example.auctions.model.User;
import com.example.auctions.repository.AuctionRepository;
import com.example.auctions.repository.BidRepository;
import com.example.auctions.repository.UserRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class BidService {
    private final BidRepository bidRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final AuctionRepository auctionRepository;
    private final AuctionService auctionService;
    private final UserRepository userRepository;

    @Autowired
    public BidService(BidRepository bidRepository, SimpMessagingTemplate messagingTemplate,
            AuctionRepository auctionRepository, AuctionService auctionService,
            UserRepository userRepository) {
        this.bidRepository = bidRepository;
        this.messagingTemplate = messagingTemplate;
        this.auctionRepository = auctionRepository;
        this.auctionService = auctionService;
        this.userRepository = userRepository;
    }

    @Transactional
    public Bid placeBid(Long auctionId, User bidder, BigDecimal amount, HttpSession session) {
        // Lock auction row to prevent race condition
        // IMPORTANT: load ONLY here (not in controller) to avoid OSIV L1 cache returning stale @Version
        Auction auction = auctionRepository.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new RuntimeException("Auction not found"));

        // Check auction is still active AND not past end time
        if (auction.getStatus() != AuctionStatus.ACTIVE) {
            throw new RuntimeException("This auction is no longer active");
        }
        if (auction.getEndTime() == null || !auction.getEndTime().isAfter(LocalDateTime.now())) {
            throw new RuntimeException("This auction has already ended");
        }

        // Check bidder is not the seller
        if (auction.getSeller().getId().equals(bidder.getId())) {
            throw new RuntimeException("You cannot bid on your own auction");
        }

        // Check private auction access
        if (!auctionService.canAccessPrivateAuction(auction, bidder, session)) {
            throw new RuntimeException("This private auction requires a valid access code before you can bid.");
        }

        // Fetch fresh balance from DB
        BigDecimal freshBalance = userRepository.findById(bidder.getId())
                .map(User::getBalance)
                .orElse(BigDecimal.ZERO);
        if (freshBalance.compareTo(amount) < 0) {
            throw new RuntimeException("Insufficient wallet balance to place bid. Please top-up. Current balance: "
                    + freshBalance + " VNĐ");
        }

        // Check if there are any existing bids
        boolean isFirstBid = bidRepository.findTopByAuctionOrderByAmountDesc(auction).isEmpty();
        BigDecimal minAllowed;

        if (isFirstBid) {
            // First bid can be exactly the starting price
            minAllowed = auction.getStartingPrice();
        } else {
            // Enforce minimum increment for subsequent bids
            BigDecimal minIncrement = new BigDecimal("1000");
            minAllowed = auction.getCurrentPrice().add(minIncrement);
        }

        if (amount.compareTo(minAllowed) < 0) {
            throw new RuntimeException("Bid must be at least " + minAllowed + " VNĐ (current price + 1,000 VNĐ minimum increment)");
        }

        // Reject fractional VND
        if (amount.scale() > 0 && amount.stripTrailingZeros().scale() > 0) {
            throw new RuntimeException("Bid amount must be whole VNĐ");
        }

        // Create and save new bid
        Bid bid = new Bid();
        bid.setAuction(auction);
        bid.setBidder(bidder);
        bid.setAmount(amount);
        bid.setBidTime(LocalDateTime.now());
        bid.setProductNameSnapshot(auction.getProductName());
        bid.setSellerNameSnapshot(auction.getSeller().getFullName());
        bid.setBidderNameSnapshot(bidder.getFullName());
        bid.setBidderEmailSnapshot(bidder.getEmail());

        Bid savedBid = bidRepository.save(bid);

        // Update auction's current price (on locked row)
        auction.setCurrentPrice(amount);
        auctionRepository.save(auction);

        // Notify AFTER transaction commits so sync fetches always see the new bid
        final Long notifyAuctionId = auction.getId();
        final String notifyBidderName = bidder.getFullName();
        final Long notifyBidderId = bidder.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notifyNewBid(notifyAuctionId, amount, notifyBidderName, notifyBidderId);
            }
        });

        return savedBid;
    }

    /** Stateless overload for JWT/API — no HttpSession, checks DB-based private access */
    @Transactional
    public Bid placeBid(Long auctionId, User bidder, BigDecimal amount) {
        Auction auction = auctionRepository.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new RuntimeException("Auction not found"));

        if (auction.getStatus() != AuctionStatus.ACTIVE) {
            throw new RuntimeException("This auction is no longer active");
        }
        if (auction.getEndTime() == null || !auction.getEndTime().isAfter(LocalDateTime.now())) {
            throw new RuntimeException("This auction has already ended");
        }
        if (auction.getSeller().getId().equals(bidder.getId())) {
            throw new RuntimeException("You cannot bid on your own auction");
        }
        if (!auctionService.canAccessPrivateAuctionStateless(auction, bidder)) {
            throw new RuntimeException("This private auction requires a valid access code before you can bid.");
        }

        BigDecimal freshBalance = userRepository.findById(bidder.getId())
                .map(User::getBalance)
                .orElse(BigDecimal.ZERO);
        if (freshBalance.compareTo(amount) < 0) {
            throw new RuntimeException("Insufficient wallet balance. Current balance: " + freshBalance + " VNĐ");
        }

        boolean isFirstBid = bidRepository.findTopByAuctionOrderByAmountDesc(auction).isEmpty();
        BigDecimal minAllowed;
        if (isFirstBid) {
            minAllowed = auction.getStartingPrice();
        } else {
            minAllowed = auction.getCurrentPrice().add(new BigDecimal("1000"));
        }
        if (amount.compareTo(minAllowed) < 0) {
            throw new RuntimeException("Bid must be at least " + minAllowed + " VNĐ");
        }
        if (amount.scale() > 0 && amount.stripTrailingZeros().scale() > 0) {
            throw new RuntimeException("Bid amount must be whole VNĐ");
        }

        Bid bid = new Bid();
        bid.setAuction(auction);
        bid.setBidder(bidder);
        bid.setAmount(amount);
        bid.setBidTime(LocalDateTime.now());
        bid.setProductNameSnapshot(auction.getProductName());
        bid.setSellerNameSnapshot(auction.getSeller().getFullName());
        bid.setBidderNameSnapshot(bidder.getFullName());
        bid.setBidderEmailSnapshot(bidder.getEmail());

        Bid savedBid = bidRepository.save(bid);
        auction.setCurrentPrice(amount);
        auctionRepository.save(auction);

        final Long notifyAuctionId = auction.getId();
        final String notifyBidderName = bidder.getFullName();
        final Long notifyBidderId = bidder.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notifyNewBid(notifyAuctionId, amount, notifyBidderName, notifyBidderId);
            }
        });
        return savedBid;
    }

    public List<Bid> getAuctionBids(Auction auction) {
        return bidRepository.findByAuctionOrderByAmountDesc(auction);
    }

    public List<Bid> getUserBids(Long userId) {
        return bidRepository.findByBidderIdOrderByBidTimeDesc(userId);
    }

    public Page<UserBidGroupDTO> getUserBidsPaged(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Auction> auctionsPage = bidRepository.findDistinctAuctionsByBidderId(userId, pageable);

        return auctionsPage.map(auction -> {
            List<Bid> userBids = bidRepository.findAllByBidderIdAndAuctionId(userId, auction.getId());
            BigDecimal highestBid = userBids.stream()
                    .map(Bid::getAmount)
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            LocalDateTime latestBidTime = userBids.isEmpty() ? LocalDateTime.now() : userBids.get(0).getBidTime();
            return new UserBidGroupDTO(auction, highestBid, userBids, latestBidTime);
        });
    }

    public List<Bid> getActiveBids(Long userId) {
        return bidRepository.findByBidderIdAndAuctionStatusOrderByBidTimeDesc(userId, AuctionStatus.ACTIVE);
    }

    public Page<Bid> getActiveBidsPaged(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("bidTime").descending());
        return bidRepository.findActiveBidsPaged(userId, AuctionStatus.ACTIVE, pageable);
    }

    public Optional<Bid> getHighestBid(Auction auction) {
        return bidRepository.findTopByAuctionOrderByAmountDesc(auction);
    }

    public List<Auction> getWonAuctions(Long userId) {
        return bidRepository.findWonAuctionsByBidderId(userId);
    }

    public Page<Auction> getWonAuctionsWithPagination(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("endTime").descending());
        return bidRepository.findWonAuctionsByBidderIdPaged(userId, pageable);
    }

    private String maskName(String name) {
        if (name == null || name.length() < 2) return name;
        return name.substring(0, 1) + "***" + name.substring(name.length() - 1);
    }

    private void notifyNewBid(Long auctionId, BigDecimal amount, String bidderName, Long bidderId) {
        String finalName = bidderName;
        // Check if auction is private to mask name
        Optional<Auction> auctionOpt = auctionRepository.findById(auctionId);
        if (auctionOpt.isPresent() && auctionOpt.get().getVisibility() == com.example.auctions.model.AuctionVisibility.PRIVATE) {
            finalName = maskName(bidderName);
        }

        String destination = "/topic/auction/" + auctionId;
        BidMessage message = new BidMessage(amount, finalName, bidderId);
        messagingTemplate.convertAndSend(destination, message);
    }

    // Inner class for WebSocket messages
    private static class BidMessage {
        private final BigDecimal amount;
        private final String bidderName;
        private final Long bidderId;
        private final LocalDateTime timestamp;

        public BidMessage(BigDecimal amount, String bidderName, Long bidderId) {
            this.amount = amount;
            this.bidderName = bidderName;
            this.bidderId = bidderId;
            this.timestamp = LocalDateTime.now();
        }

        @JsonProperty("amount")
        public BigDecimal getAmount() {
            return amount;
        }

        @JsonProperty("bidderName")
        public String getBidderName() {
            return bidderName;
        }

        @JsonProperty("bidderId")
        public Long getBidderId() {
            return bidderId;
        }

        @JsonProperty("timestamp")
        public LocalDateTime getTimestamp() {
            return timestamp;
        }

        @JsonProperty("eventType")
        public String getEventType() {
            return "NEW_BID";
        }
    }

    public long countActiveAuctionsParticipating(User user) {
        return bidRepository.countDistinctAuctionsByBidderAndAuctionEndTimeAfter(user, java.time.LocalDateTime.now());
    }

    public long countAuctionsAsHighestBidder(User user) {
        return bidRepository.countAuctionsWhereUserIsHighestBidder(user);
    }

    public long countTotalAuctionsWon(User user) {
        return bidRepository.countAuctionsWonByUser(user);
    }

    public Page<UserBidGroupDTO> getActiveBidsGroupedPaged(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Auction> auctionsPage = bidRepository.findDistinctAuctionsByBidderIdAndStatus(userId, AuctionStatus.ACTIVE, pageable);

        return auctionsPage.map(auction -> {
            List<Bid> userBids = bidRepository.findAllByBidderIdAndAuctionId(userId, auction.getId());
            BigDecimal highestBid = userBids.stream()
                    .map(Bid::getAmount)
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            LocalDateTime latestBidTime = userBids.isEmpty() ? LocalDateTime.now() : userBids.get(0).getBidTime();
            return new UserBidGroupDTO(auction, highestBid, userBids, latestBidTime);
        });
    }
}
