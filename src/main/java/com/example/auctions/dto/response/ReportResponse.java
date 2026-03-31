package com.example.auctions.dto.response;

import com.example.auctions.service.ReportService;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ReportResponse(
        BigDecimal totalRevenue,
        long totalSoldItems,
        long totalAuctionsCreated,
        double averageBiddersPerAuction,
        BigDecimal averageItemPrice,
        LocalDateTime startDate,
        LocalDateTime endDate
) {
    public static ReportResponse from(ReportService.Report report, LocalDateTime startDate, LocalDateTime endDate) {
        return new ReportResponse(
                report.getTotalRevenue(),
                report.getTotalSoldItems(),
                report.getTotalAuctionsCreated(),
                report.getAverageBiddersPerAuction(),
                report.getAverageItemPrice(),
                startDate,
                endDate
        );
    }
}
