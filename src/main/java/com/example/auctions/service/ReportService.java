package com.example.auctions.service;

import com.example.auctions.model.User;
import com.example.auctions.repository.AuctionRepository;
import com.example.auctions.repository.TransactionRepository;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class ReportService {

    private final TransactionRepository transactionRepository;
    private final AuctionRepository auctionRepository;

    @Autowired
    public ReportService(TransactionRepository transactionRepository, AuctionRepository auctionRepository) {
        this.transactionRepository = transactionRepository;
        this.auctionRepository = auctionRepository;
    }

    public Report generateReport(User user, LocalDateTime startDate, LocalDateTime endDate) {
        Report report = new Report();

        report.setTotalRevenue(transactionRepository.sumTotalSalesInPeriod(user, startDate, endDate));
        report.setTotalSoldItems(auctionRepository.countSuccessfulAuctionsInPeriod(user, startDate, endDate));
        report.setTotalAuctionsCreated(auctionRepository.countAuctionsCreatedInPeriod(user, startDate, endDate));
        report.setAverageBiddersPerAuction(auctionRepository.calculateAverageBiddersInPeriod(user, startDate, endDate));
        report.setAverageItemPrice(transactionRepository.calculateAveragePriceInPeriod(user, startDate, endDate));

        return report;
    }

    @Data
    public static class Report {
        private BigDecimal totalRevenue = BigDecimal.ZERO;
        private long totalSoldItems = 0;
        private long totalAuctionsCreated = 0;
        private double averageBiddersPerAuction = 0.0;
        private BigDecimal averageItemPrice = BigDecimal.ZERO;
    }
}
