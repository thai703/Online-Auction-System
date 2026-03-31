package com.example.auctions.repository;

import com.example.auctions.model.Transaction;
import com.example.auctions.model.TransactionStatus;
import com.example.auctions.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t LEFT JOIN FETCH t.buyer LEFT JOIN FETCH t.seller WHERE t.id = :id")
    Optional<Transaction> findByIdForUpdate(@Param("id") Long id);
    Page<Transaction> findByBuyerOrderByTransactionDateDesc(User buyer, Pageable pageable);
    Page<Transaction> findBySellerOrderByTransactionDateDesc(User seller, Pageable pageable);
    long countByBuyer(User buyer);
    long countBySeller(User seller);

    Optional<Transaction> findByAuctionId(Long auctionId);

    @Query("SELECT t FROM Transaction t LEFT JOIN FETCH t.seller LEFT JOIN FETCH t.buyer WHERE t.id = :id")
    Optional<Transaction> findByIdWithUsers(@Param("id") Long id);

    @Query("""
            SELECT COALESCE(SUM(t.price), 0)
            FROM Transaction t
            WHERE t.buyer = :buyer
              AND t.status = com.example.auctions.model.TransactionStatus.COMPLETED
            """)
    BigDecimal sumTotalSpentByBuyer(@Param("buyer") User buyer);

    @Query("""
            SELECT COALESCE(SUM(t.price), 0)
            FROM Transaction t
            WHERE t.seller = :seller
              AND t.status = com.example.auctions.model.TransactionStatus.COMPLETED
            """)
    BigDecimal sumTotalSalesBySeller(@Param("seller") User seller);

    @Query("""
            SELECT COALESCE(SUM(t.price), 0)
            FROM Transaction t
            WHERE t.seller = :seller
              AND t.status = com.example.auctions.model.TransactionStatus.COMPLETED
              AND t.transactionDate BETWEEN :startDate AND :endDate
            """)
    BigDecimal sumTotalSalesInPeriod(
        @Param("seller") User seller,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate);

    @Query("""
            SELECT COALESCE(AVG(t.price), 0)
            FROM Transaction t
            WHERE t.seller = :seller
              AND t.status = com.example.auctions.model.TransactionStatus.COMPLETED
              AND t.transactionDate BETWEEN :startDate AND :endDate
            """)
    BigDecimal calculateAveragePriceInPeriod(
        @Param("seller") User seller,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate);

    long countByStatus(TransactionStatus status);

    @Query("SELECT COALESCE(SUM(t.price), 0) FROM Transaction t WHERE t.status = :status")
    BigDecimal sumPriceByStatus(@Param("status") TransactionStatus status);

    @EntityGraph(attributePaths = {"buyer", "seller"})
    @Query(value = """
            SELECT t
            FROM Transaction t
            WHERE (:keyword IS NULL
                OR LOWER(t.itemName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(t.itemDescription) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(t.buyer.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(t.buyer.email) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(t.seller.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(t.seller.email) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:status IS NULL OR t.status = :status)
              AND (:auctionId IS NULL OR t.auctionId = :auctionId)
              AND (:transactionId IS NULL OR t.id = :transactionId)
            """,
            countQuery = """
            SELECT COUNT(t)
            FROM Transaction t
            WHERE (:keyword IS NULL
                OR LOWER(t.itemName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(t.itemDescription) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(t.buyer.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(t.buyer.email) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(t.seller.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(t.seller.email) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:status IS NULL OR t.status = :status)
              AND (:auctionId IS NULL OR t.auctionId = :auctionId)
              AND (:transactionId IS NULL OR t.id = :transactionId)
            """)
    Page<Transaction> searchForAdmin(@Param("keyword") String keyword,
                                     @Param("status") TransactionStatus status,
                                     @Param("auctionId") Long auctionId,
                                     @Param("transactionId") Long transactionId,
                                     Pageable pageable);
}
