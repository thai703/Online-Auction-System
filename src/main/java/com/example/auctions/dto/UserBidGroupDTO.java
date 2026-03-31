package com.example.auctions.dto;

import com.example.auctions.model.Auction;
import com.example.auctions.model.Bid;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserBidGroupDTO {
    private Auction auction;
    private BigDecimal highestBid;
    private List<Bid> allUserBids;
    private LocalDateTime latestBidTime;

    public long getBidCount() {
        return allUserBids != null ? allUserBids.size() : 0;
    }
}
