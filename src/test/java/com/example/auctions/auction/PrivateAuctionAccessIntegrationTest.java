package com.example.auctions.auction;

import com.example.auctions.model.Auction;
import com.example.auctions.model.AuctionAccessCode;
import com.example.auctions.model.AuctionStatus;
import com.example.auctions.model.AuctionVisibility;
import com.example.auctions.model.User;
import com.example.auctions.model.UserRole;
import com.example.auctions.repository.AuctionAccessCodeRepository;
import com.example.auctions.repository.AuctionRepository;
import com.example.auctions.repository.BidRepository;
import com.example.auctions.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrivateAuctionAccessIntegrationTest {

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
    private PasswordEncoder passwordEncoder;

    private User seller;
    private User buyer;
    private Auction privateAuction;

    @BeforeEach
    void setUp() {
        auctionAccessCodeRepository.deleteAll();
        bidRepository.deleteAll();
        auctionRepository.deleteAll();
        userRepository.deleteAll();

        seller = new User();
        seller.setEmail("seller@test.local");
        seller.setPassword(passwordEncoder.encode("password"));
        seller.setFullName("Seller User");
        seller.setPhoneNumber("0900000001");
        seller.setRole(UserRole.SELLER);
        seller.setBalance(new BigDecimal("500000"));
        seller = userRepository.save(seller);

        buyer = new User();
        buyer.setEmail("buyer@test.local");
        buyer.setPassword(passwordEncoder.encode("password"));
        buyer.setFullName("Buyer User");
        buyer.setPhoneNumber("0900000002");
        buyer.setRole(UserRole.BUYER);
        buyer.setBalance(new BigDecimal("900000"));
        buyer = userRepository.save(buyer);

        privateAuction = new Auction();
        privateAuction.setProductName("VIP Figurine");
        privateAuction.setDescription("A private collectible auction reserved for invited buyers.");
        privateAuction.setStartingPrice(new BigDecimal("100000"));
        privateAuction.setCurrentPrice(new BigDecimal("100000"));
        privateAuction.setEndTime(LocalDateTime.now().plusDays(2));
        privateAuction.setSeller(seller);
        privateAuction.setStatus(AuctionStatus.ACTIVE);
        privateAuction.setVisibility(AuctionVisibility.PRIVATE);
        privateAuction = auctionRepository.save(privateAuction);

        AuctionAccessCode accessCode = new AuctionAccessCode();
        accessCode.setAuction(privateAuction);
        accessCode.setAccessCode("VIP2026");
        accessCode.setEnabled(true);
        auctionAccessCodeRepository.save(accessCode);
    }

    @Test
    void privateAuctionDetailPromptsForUnlockWhenBuyerHasNoAccess() throws Exception {
        mockMvc.perform(get("/auctions/{id}", privateAuction.getId())
                        .with(SecurityMockMvcRequestPostProcessors.user(buyer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Unlock to Place Bids")))
                .andExpect(content().string(containsString("PRIVATE")));
    }

    @Test
    void placeBidFailsUntilPrivateAuctionIsUnlocked() throws Exception {
        mockMvc.perform(post("/bids/place")
                        .with(SecurityMockMvcRequestPostProcessors.user(buyer))
                        .with(csrf())
                        .param("auctionId", privateAuction.getId().toString())
                        .param("amount", "150000"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("requires a valid access code")));
    }

    @Test
    void unlockingPrivateAuctionAllowsBuyerToPlaceBid() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/auctions/{id}/unlock", privateAuction.getId())
                        .with(SecurityMockMvcRequestPostProcessors.user(buyer))
                        .with(csrf())
                        .session(session)
                        .param("accessCode", "VIP2026"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auctions/" + privateAuction.getId()));

        mockMvc.perform(post("/bids/place")
                        .with(SecurityMockMvcRequestPostProcessors.user(buyer))
                        .with(csrf())
                        .session(session)
                        .param("auctionId", privateAuction.getId().toString())
                        .param("amount", "150000"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"success\":true")));
    }
}
