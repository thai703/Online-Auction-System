package com.example.auctions.auth;

import com.example.auctions.dto.GoogleOAuthPendingProfile;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GoogleOnboardingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuctionAccessCodeRepository auctionAccessCodeRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    @BeforeEach
    void setUp() {
        walletTransactionRepository.deleteAll();
        transactionRepository.deleteAll();
        auctionAccessCodeRepository.deleteAll();
        bidRepository.deleteAll();
        auctionRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void onboardingPagePrefillsGoogleProfileData() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                GoogleOAuthPendingProfile.SESSION_ATTRIBUTE,
                new GoogleOAuthPendingProfile("google-user@test.local", "Google Display Name", "google-sub-123"));

        mockMvc.perform(get("/oauth2/onboarding").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/oauth-onboarding"))
                .andExpect(content().string(containsString("google-user@test.local")))
                .andExpect(content().string(containsString("Google Display Name")));
    }

    @Test
    void completingGoogleOnboardingCreatesUserAndSignsThemIn() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                GoogleOAuthPendingProfile.SESSION_ATTRIBUTE,
                new GoogleOAuthPendingProfile("google-user@test.local", "Google Display Name", "google-sub-123"));

        mockMvc.perform(post("/oauth2/onboarding")
                        .session(session)
                        .with(csrf())
                        .param("fullName", "Updated Display Name")
                        .param("phoneNumber", "0987654321")
                        .param("role", "SELLER"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard"));

        Optional<User> savedUser = userRepository.findByEmail("google-user@test.local");
        assertTrue(savedUser.isPresent());
        assertEquals("Updated Display Name", savedUser.get().getFullName());
        assertEquals("0987654321", savedUser.get().getPhoneNumber());
        assertEquals(UserRole.SELLER, savedUser.get().getRole());
        assertEquals("google-sub-123", savedUser.get().getGoogleId());
        assertTrue(savedUser.get().isEnabled());
        assertTrue(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null);
    }

    @Test
    void onboardingWithoutPendingGoogleProfileRedirectsBackToLogin() throws Exception {
        mockMvc.perform(get("/oauth2/onboarding"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?oauth2Error=onboarding_expired"));
    }
}
