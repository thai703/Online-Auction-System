package com.example.auctions.auction;

import com.example.auctions.model.Auction;
import com.example.auctions.model.AuctionStatus;
import com.example.auctions.model.AuctionVisibility;
import com.example.auctions.model.User;
import com.example.auctions.model.UserRole;
import com.example.auctions.repository.AuctionAccessCodeRepository;
import com.example.auctions.repository.AuctionRepository;
import com.example.auctions.repository.BidRepository;
import com.example.auctions.repository.TransactionRepository;
import com.example.auctions.repository.UserRepository;
import com.example.auctions.repository.WalletTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BrowseSearchIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private AuctionAccessCodeRepository auctionAccessCodeRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User buyer;
    private User seller;

    @BeforeEach
    void setUp() {
        walletTransactionRepository.deleteAll();
        transactionRepository.deleteAll();
        auctionAccessCodeRepository.deleteAll();
        bidRepository.deleteAll();
        auctionRepository.deleteAll();
        userRepository.deleteAll();

        seller = new User();
        seller.setEmail("seller-browse@test.local");
        seller.setPassword(passwordEncoder.encode("password"));
        seller.setFullName("Browse Seller");
        seller.setPhoneNumber("0900000010");
        seller.setRole(UserRole.SELLER);
        seller = userRepository.save(seller);

        buyer = new User();
        buyer.setEmail("buyer-browse@test.local");
        buyer.setPassword(passwordEncoder.encode("password"));
        buyer.setFullName("Browse Buyer");
        buyer.setPhoneNumber("0900000011");
        buyer.setRole(UserRole.BUYER);
        buyer = userRepository.save(buyer);
    }

    @Test
    void browseSupportsKeywordAndVisibilityFiltering() throws Exception {
        saveAuction("Hidden Sapphire", "Private gemstone auction", new BigDecimal("150000"), AuctionVisibility.PRIVATE, AuctionStatus.ACTIVE, LocalDateTime.now().plusDays(2));
        saveAuction("City Bicycle", "Public commuter bike", new BigDecimal("50000"), AuctionVisibility.PUBLIC, AuctionStatus.ACTIVE, LocalDateTime.now().plusDays(2));
        saveAuction("Expired Sapphire", "Old private gemstone auction", new BigDecimal("170000"), AuctionVisibility.PRIVATE, AuctionStatus.ENDED, LocalDateTime.now().minusDays(1));

        // Private auctions are visible in browse (product info shown, bidding requires unlock)
        mockMvc.perform(get("/auctions/browse")
                        .param("keyword", "sapphire")
                        .param("visibility", "private")
                        .param("sort", "endingSoon")
                        .with(SecurityMockMvcRequestPostProcessors.user(buyer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hidden Sapphire")))
                .andExpect(content().string(not(containsString("Expired Sapphire"))));

        // Public auctions are visible via keyword search
        mockMvc.perform(get("/auctions/browse")
                        .param("keyword", "bicycle")
                        .with(SecurityMockMvcRequestPostProcessors.user(buyer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("City Bicycle")))
                .andExpect(content().string(containsString("active auction(s) found")));
    }

    @Test
    void browseSupportsPriceSorting() throws Exception {
        saveAuction("Budget Lamp", "Simple lamp", new BigDecimal("10000"), AuctionVisibility.PUBLIC, AuctionStatus.ACTIVE, LocalDateTime.now().plusDays(1));
        saveAuction("Luxury Vase", "Premium vase", new BigDecimal("90000"), AuctionVisibility.PUBLIC, AuctionStatus.ACTIVE, LocalDateTime.now().plusDays(1));

        String response = mockMvc.perform(get("/auctions/browse")
                        .param("sort", "priceHigh")
                        .with(SecurityMockMvcRequestPostProcessors.user(buyer)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        int luxuryIndex = response.indexOf("Luxury Vase");
        int budgetIndex = response.indexOf("Budget Lamp");
        assertTrue(luxuryIndex >= 0 && budgetIndex >= 0 && luxuryIndex < budgetIndex,
                "Higher-priced auction should appear before lower-priced auction when sorting by priceHigh");
    }

    private void saveAuction(String productName,
                             String description,
                             BigDecimal price,
                             AuctionVisibility visibility,
                             AuctionStatus status,
                             LocalDateTime endTime) {
        Auction auction = new Auction();
        auction.setProductName(productName);
        auction.setDescription(description);
        auction.setStartingPrice(price);
        auction.setCurrentPrice(price);
        auction.setVisibility(visibility);
        auction.setStatus(status);
        auction.setEndTime(endTime);
        auction.setSeller(seller);
        auctionRepository.save(auction);
    }
}
