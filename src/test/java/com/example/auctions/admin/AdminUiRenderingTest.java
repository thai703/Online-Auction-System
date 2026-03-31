package com.example.auctions.admin;

import com.example.auctions.model.User;
import com.example.auctions.model.UserRole;
import com.example.auctions.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminUiRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User adminUser;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        adminUser = new User();
        adminUser.setEmail("admin@test.local");
        adminUser.setPassword(passwordEncoder.encode("password"));
        adminUser.setFullName("Admin User");
        adminUser.setPhoneNumber("0900000000");
        adminUser.setRole(UserRole.ADMIN);
        adminUser = userRepository.save(adminUser);

        User buyer = new User();
        buyer.setEmail("buyer@test.local");
        buyer.setPassword(passwordEncoder.encode("password"));
        buyer.setFullName("Buyer User");
        buyer.setPhoneNumber("0911111111");
        buyer.setRole(UserRole.BUYER);
        userRepository.save(buyer);
    }

    @Test
    void usersPageRendersWhenAllFiltersAreSubmitted() throws Exception {
        mockMvc.perform(get("/admin/users")
                        .param("keyword", "")
                        .param("role", "all")
                        .param("enabled", "all")
                        .with(SecurityMockMvcRequestPostProcessors.user(adminUser)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/users"))
                .andExpect(content().string(containsString("User Directory")));
    }
}
