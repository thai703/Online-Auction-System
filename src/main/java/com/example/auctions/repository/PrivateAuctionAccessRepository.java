package com.example.auctions.repository;

import com.example.auctions.model.PrivateAuctionAccess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PrivateAuctionAccessRepository extends JpaRepository<PrivateAuctionAccess, Long> {
    boolean existsByUserIdAndAuctionId(Long userId, Long auctionId);
    Optional<PrivateAuctionAccess> findByUserIdAndAuctionId(Long userId, Long auctionId);
}
