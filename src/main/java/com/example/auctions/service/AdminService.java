package com.example.auctions.service;

import com.example.auctions.model.Auction;
import com.example.auctions.model.AuctionStatus;
import com.example.auctions.model.Bid;
import com.example.auctions.model.Transaction;
import com.example.auctions.model.TransactionStatus;
import com.example.auctions.model.User;
import com.example.auctions.model.UserRole;
import com.example.auctions.model.WalletTransaction;
import com.example.auctions.model.WalletTransactionType;
import com.example.auctions.repository.AuctionRepository;
import com.example.auctions.repository.BidRepository;
import com.example.auctions.repository.TransactionRepository;
import com.example.auctions.repository.UserRepository;
import com.example.auctions.repository.WalletTransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AdminService {
    private static final int DETAIL_ACTIVITY_LIMIT = 5;

    private final UserRepository userRepository;
    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final TransactionRepository transactionRepository;
    private final WalletTransactionRepository walletTransactionRepository;

    @Autowired
    public AdminService(UserRepository userRepository,
                        AuctionRepository auctionRepository,
                        BidRepository bidRepository,
                        TransactionRepository transactionRepository,
                        WalletTransactionRepository walletTransactionRepository) {
        this.userRepository = userRepository;
        this.auctionRepository = auctionRepository;
        this.bidRepository = bidRepository;
        this.transactionRepository = transactionRepository;
        this.walletTransactionRepository = walletTransactionRepository;
    }

    public DashboardStats getDashboardStats() {
        return new DashboardStats(
                userRepository.count(),
                userRepository.countByEnabledTrue(),
                userRepository.countByEnabledFalse(),
                auctionRepository.countByStatus(AuctionStatus.ACTIVE),
                auctionRepository.countByStatus(AuctionStatus.CANCELLED),
                transactionRepository.count()
        );
    }

    public List<User> getRecentUsers() {
        return userRepository.findTop6ByOrderByIdDesc();
    }

    public List<Auction> getRecentAuctions() {
        return auctionRepository.findTop6ByOrderByCreatedAtDesc();
    }

    public Page<User> getUsers(String keyword, UserRole role, Boolean enabled, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        return userRepository.searchForAdmin(normalizeKeyword(keyword), role, enabled, pageRequest);
    }

    public UserDetailView getUserDetail(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        PageRequest recentAuctionPage = PageRequest.of(0, DETAIL_ACTIVITY_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt"));
        PageRequest recentPage = PageRequest.of(0, DETAIL_ACTIVITY_LIMIT);

        long auctionsCreated = auctionRepository.countBySellerId(userId);
        long activeAuctions = auctionRepository.countBySellerAndStatus(user, AuctionStatus.ACTIVE);
        long endedAuctions = auctionRepository.countBySellerAndStatus(user, AuctionStatus.ENDED);
        long successfulAuctions = auctionRepository.countBySellerAndStatusAndWinnerIsNotNull(user, AuctionStatus.ENDED);
        long purchases = transactionRepository.countByBuyer(user);
        long sales = transactionRepository.countBySeller(user);
        long activeBidAuctions = bidRepository.countDistinctAuctionsByBidderAndAuctionEndTimeAfter(user, LocalDateTime.now());
        long wonAuctions = bidRepository.countAuctionsWonByUser(user);

        BigDecimal totalSpent = transactionRepository.sumTotalSpentByBuyer(user);
        BigDecimal totalRevenue = transactionRepository.sumTotalSalesBySeller(user);

        List<Auction> recentSellerAuctions = auctionRepository.findBySeller(user, recentAuctionPage).getContent();
        List<Transaction> recentPurchases = transactionRepository.findByBuyerOrderByTransactionDateDesc(user, recentPage).getContent();
        List<Transaction> recentSales = transactionRepository.findBySellerOrderByTransactionDateDesc(user, recentPage).getContent();
        List<WalletTransaction> recentWalletTransactions = walletTransactionRepository.findByUserOrderByCreatedAtDesc(user, recentPage).getContent();
        List<Bid> recentBids = bidRepository.findByBidderIdWithAuctionPaged(user.getId(), recentPage).getContent();

        return new UserDetailView(
                user,
                auctionsCreated,
                activeAuctions,
                endedAuctions,
                successfulAuctions,
                purchases,
                sales,
                activeBidAuctions,
                wonAuctions,
                totalSpent,
                totalRevenue,
                recentSellerAuctions,
                recentPurchases,
                recentSales,
                recentWalletTransactions,
                recentBids
        );
    }

    public Page<Auction> getAuctions(String keyword, AuctionStatus status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return auctionRepository.searchForAdmin(normalizeKeyword(keyword), status, pageRequest);
    }

    public AuctionDetailView getAuctionDetail(Long auctionId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new RuntimeException("Auction not found"));
        List<Bid> bids = bidRepository.findByAuctionWithBidderOrderByBidTimeDesc(auction);
        long uniqueBidders = bids.stream()
                .map(bid -> bid.getBidder().getId())
                .distinct()
                .count();
        BigDecimal highestBidAmount = bids.stream()
                .map(Bid::getAmount)
                .max(BigDecimal::compareTo)
                .orElse(auction.getStartingPrice());

        return new AuctionDetailView(
                auction,
                bids,
                bids.size(),
                uniqueBidders,
                highestBidAmount,
                auction.getStatus() == AuctionStatus.DRAFT,
                auction.getStatus() == AuctionStatus.CANCELLED
        );
    }

    public Page<Transaction> getTransactions(String keyword,
                                             TransactionStatus status,
                                             Long auctionId,
                                             Long transactionId,
                                             int page,
                                             int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "transactionDate"));
        return transactionRepository.searchForAdmin(
                normalizeKeyword(keyword),
                status,
                auctionId,
                transactionId,
                pageRequest
        );
    }

    public TransactionStats getTransactionStats() {
        return new TransactionStats(
                transactionRepository.count(),
                transactionRepository.countByStatus(TransactionStatus.PENDING),
                transactionRepository.countByStatus(TransactionStatus.COMPLETED),
                transactionRepository.countByStatus(TransactionStatus.CANCELLED),
                transactionRepository.countByStatus(TransactionStatus.FAILED),
                transactionRepository.sumPriceByStatus(TransactionStatus.COMPLETED)
        );
    }

    public Page<WalletTransaction> getWalletTransactions(String keyword,
                                                         WalletTransactionType type,
                                                         Long transactionId,
                                                         Long auctionId,
                                                         int page,
                                                         int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return walletTransactionRepository.searchForAdmin(
                normalizeKeyword(keyword),
                type,
                transactionId,
                auctionId,
                pageRequest
        );
    }

    public WalletAuditStats getWalletAuditStats() {
        return new WalletAuditStats(
                walletTransactionRepository.count(),
                walletTransactionRepository.countByType(WalletTransactionType.TOP_UP),
                walletTransactionRepository.countByType(WalletTransactionType.PAYMENT_OUT),
                walletTransactionRepository.countByType(WalletTransactionType.SALE_IN),
                walletTransactionRepository.sumAmountByType(WalletTransactionType.TOP_UP),
                walletTransactionRepository.sumAmountByType(WalletTransactionType.PAYMENT_OUT),
                walletTransactionRepository.sumAmountByType(WalletTransactionType.SALE_IN)
        );
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim();
    }

    public record DashboardStats(
            long totalUsers,
            long enabledUsers,
            long disabledUsers,
            long activeAuctions,
            long cancelledAuctions,
            long totalTransactions
    ) {
    }

    public record AuctionDetailView(
            Auction auction,
            List<Bid> bids,
            int totalBids,
            long uniqueBidders,
            BigDecimal highestBidAmount,
            boolean draftAuction,
            boolean cancelledAuction
    ) {
    }

    public record TransactionStats(
            long totalTransactions,
            long pendingTransactions,
            long completedTransactions,
            long cancelledTransactions,
            long failedTransactions,
            BigDecimal completedRevenue
    ) {
    }

    public record WalletAuditStats(
            long totalEntries,
            long topUpEntries,
            long paymentOutEntries,
            long saleInEntries,
            BigDecimal topUpVolume,
            BigDecimal paymentOutVolume,
            BigDecimal saleInVolume
    ) {
    }

    public record UserDetailView(
            User user,
            long auctionsCreated,
            long activeAuctions,
            long endedAuctions,
            long successfulAuctions,
            long purchases,
            long sales,
            long activeBidAuctions,
            long wonAuctions,
            BigDecimal totalSpent,
            BigDecimal totalRevenue,
            List<Auction> recentSellerAuctions,
            List<Transaction> recentPurchases,
            List<Transaction> recentSales,
            List<WalletTransaction> recentWalletTransactions,
            List<Bid> recentBids
    ) {
    }
}
