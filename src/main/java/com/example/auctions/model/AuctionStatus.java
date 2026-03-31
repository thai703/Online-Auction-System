package com.example.auctions.model;

public enum AuctionStatus {
    DRAFT,      // Auction is created but not published
    ACTIVE,     // Auction is published and accepting bids
    ENDED,      // Auction has reached its end time
    CANCELLED   // Auction was cancelled by the seller
} 