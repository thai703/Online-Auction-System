package com.example.auctions.repository;

import com.example.auctions.model.User;
import com.example.auctions.model.WalletTransaction;
import com.example.auctions.model.WalletTransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {
    Page<WalletTransaction> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    long countByType(WalletTransactionType type);

    @Query("SELECT COALESCE(SUM(w.amount), 0) FROM WalletTransaction w WHERE w.type = :type")
    BigDecimal sumAmountByType(@Param("type") WalletTransactionType type);

    @EntityGraph(attributePaths = {"user"})
    @Query(value = """
            SELECT w
            FROM WalletTransaction w
            WHERE (:keyword IS NULL
                OR LOWER(w.description) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(w.user.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(w.user.email) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:type IS NULL OR w.type = :type)
              AND (:transactionId IS NULL OR w.relatedTransactionId = :transactionId)
              AND (:auctionId IS NULL OR w.relatedAuctionId = :auctionId)
            """,
            countQuery = """
            SELECT COUNT(w)
            FROM WalletTransaction w
            WHERE (:keyword IS NULL
                OR LOWER(w.description) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(w.user.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(w.user.email) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:type IS NULL OR w.type = :type)
              AND (:transactionId IS NULL OR w.relatedTransactionId = :transactionId)
              AND (:auctionId IS NULL OR w.relatedAuctionId = :auctionId)
            """)
    Page<WalletTransaction> searchForAdmin(@Param("keyword") String keyword,
                                           @Param("type") WalletTransactionType type,
                                           @Param("transactionId") Long transactionId,
                                           @Param("auctionId") Long auctionId,
                                           Pageable pageable);
}
