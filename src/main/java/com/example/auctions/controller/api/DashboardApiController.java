package com.example.auctions.controller.api;

import com.example.auctions.dto.response.ApiResponse;
import com.example.auctions.dto.response.DashboardResponse;
import com.example.auctions.dto.response.UserResponse;
import com.example.auctions.model.User;
import com.example.auctions.model.UserRole;
import com.example.auctions.service.AuctionService;
import com.example.auctions.service.BidService;
import com.example.auctions.service.TransactionService;
import com.example.auctions.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardApiController {

    private final AuctionService auctionService;
    private final BidService bidService;
    private final TransactionService transactionService;
    private final UserService userService;

    public DashboardApiController(AuctionService auctionService, BidService bidService,
                                   TransactionService transactionService, UserService userService) {
        this.auctionService = auctionService;
        this.bidService = bidService;
        this.transactionService = transactionService;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<DashboardResponse>> dashboard(@AuthenticationPrincipal User currentUser) {
        User freshUser = userService.getCurrentUser(currentUser.getEmail());
        UserResponse userResponse = UserResponse.from(freshUser);

        DashboardResponse dashboard;
        if (freshUser.getRole() == UserRole.SELLER) {
            long active = auctionService.countActiveAuctionsBySeller(freshUser);
            long successful = auctionService.countSuccessfulAuctionsBySeller(freshUser);
            var totalSales = transactionService.getTotalSalesBySeller(freshUser);
            dashboard = DashboardResponse.forSeller(userResponse, active, successful,
                    totalSales != null ? totalSales : java.math.BigDecimal.ZERO);
        } else {
            long participating = bidService.countActiveAuctionsParticipating(freshUser);
            long highestBidder = bidService.countAuctionsAsHighestBidder(freshUser);
            long won = bidService.countTotalAuctionsWon(freshUser);
            var totalSpent = transactionService.getTotalSpentByBuyer(freshUser);
            long watched = auctionService.countWatchedAuctions(freshUser);
            dashboard = DashboardResponse.forBuyer(userResponse, participating, highestBidder, won,
                    totalSpent != null ? totalSpent : java.math.BigDecimal.ZERO, watched);
        }
        return ResponseEntity.ok(ApiResponse.ok(dashboard));
    }
}
