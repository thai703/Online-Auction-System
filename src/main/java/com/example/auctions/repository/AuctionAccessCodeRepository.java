package com.example.auctions.repository;

import com.example.auctions.model.AuctionAccessCode;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AuctionAccessCodeRepository extends JpaRepository<AuctionAccessCode, Long> {
    Optional<AuctionAccessCode> findByAuctionIdAndEnabledTrue(Long auctionId);

    Optional<AuctionAccessCode> findByAuctionId(Long auctionId);

    @Transactional
    void deleteByAuctionId(Long auctionId);
}
