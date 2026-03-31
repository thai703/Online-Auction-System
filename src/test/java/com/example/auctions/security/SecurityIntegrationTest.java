package com.example.auctions.security;

import com.example.auctions.model.User;
import com.example.auctions.model.UserRole;
import com.example.auctions.repository.*;
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

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTest {

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

    @Autowired
    private PasswordEncoder passwordEncoder;

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
    void registerRejectsAdminRole() throws Exception {
        mockMvc.perform(post("/register")
                        .param("fullName", "Evil User")
                        .param("email", "evil@test.local")
                        .param("phoneNumber", "0900000001")
                        .param("password", "Password1!")
                        .param("role", "ADMIN")
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("register")));

        // Verify no ADMIN user was created
        assertFalse(userRepository.findByEmail("evil@test.local").isPresent(),
                "ADMIN user should not have been created");
    }

    @Test
    void registerAllowsBuyerRole() throws Exception {
        mockMvc.perform(post("/register")
                        .param("fullName", "Good Buyer")
                        .param("email", "buyer@test.local")
                        .param("phoneNumber", "0900000002")
                        .param("password", "Password1!")
                        .param("role", "BUYER")
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());

        assertTrue(userRepository.findByEmail("buyer@test.local").isPresent(),
                "BUYER user should have been created");
        assertEquals(UserRole.BUYER, userRepository.findByEmail("buyer@test.local").get().getRole());
    }

    @Test
    void profileUpdateOnlyAffectsCurrentUser() throws Exception {
        User user1 = createUser("user1@test.local", "User One", UserRole.BUYER);
        User user2 = createUser("user2@test.local", "User Two", UserRole.BUYER);

        // user1 tries to update profile - should only affect user1
        mockMvc.perform(post("/profile")
                        .param("fullName", "Updated Name")
                        .param("phoneNumber", "0999999999")
                        .with(SecurityMockMvcRequestPostProcessors.user(user1))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());

        // Verify user1 was updated
        User updatedUser1 = userRepository.findById(user1.getId()).orElseThrow();
        assertEquals("Updated Name", updatedUser1.getFullName());

        // Verify user2 was NOT affected
        User unchangedUser2 = userRepository.findById(user2.getId()).orElseThrow();
        assertEquals("User Two", unchangedUser2.getFullName());
    }

    @Test
    void passwordChangeRequiresCorrectCurrentPassword() throws Exception {
        User user = createUser("pwtest@test.local", "PW Test User", UserRole.BUYER);

        // Try with wrong current password - should return profile page (200) with error
        mockMvc.perform(post("/profile/password")
                        .param("currentPassword", "wrongpassword")
                        .param("newPassword", "newPassword1!")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("passwordError"));

        // Verify password was NOT changed (original still works)
        User dbUser = userRepository.findById(user.getId()).orElseThrow();
        assertTrue(passwordEncoder.matches("Password1!", dbUser.getPassword()),
                "Password should not have been changed with wrong current password");
    }

    private User createUser(String email, String fullName, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("Password1!"));
        user.setFullName(fullName);
        user.setPhoneNumber("0900000000");
        user.setRole(role);
        user.setBalance(BigDecimal.ZERO);
        user.setEnabled(true);
        return userRepository.save(user);
    }
}
