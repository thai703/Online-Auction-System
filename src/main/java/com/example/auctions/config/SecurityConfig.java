package com.example.auctions.config;

import com.example.auctions.model.User;
import com.example.auctions.security.AccountStatusFilter;
import com.example.auctions.security.JwtAuthenticationFilter;
import com.example.auctions.security.GoogleOAuth2SuccessHandler;
import com.example.auctions.security.RateLimitFilter;
import com.example.auctions.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.authentication.DisabledException;
import org.springframework.util.StringUtils;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final AccountStatusFilter accountStatusFilter;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final GoogleOAuth2SuccessHandler googleOAuth2SuccessHandler;
    private final RateLimitFilter rateLimitFilter;

    @Value("${spring.security.oauth2.client.registration.google.client-id:}")
    private String googleClientId;

    @Value("${spring.security.oauth2.client.registration.google.client-secret:}")
    private String googleClientSecret;

    public SecurityConfig(UserService userService, PasswordEncoder passwordEncoder,
                          AccountStatusFilter accountStatusFilter,
                          JwtAuthenticationFilter jwtAuthenticationFilter,
                          GoogleOAuth2SuccessHandler googleOAuth2SuccessHandler,
                          RateLimitFilter rateLimitFilter) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.accountStatusFilter = accountStatusFilter;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.googleOAuth2SuccessHandler = googleOAuth2SuccessHandler;
        this.rateLimitFilter = rateLimitFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("default-src 'self'; script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; font-src 'self' https://cdn.jsdelivr.net; img-src 'self' data:; connect-src 'self' ws: wss:; frame-ancestors 'self'")
                )
            )
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/**", "/ws/**", "/ws/raw/**")
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/register", "/register/verify", "/register/resend", "/login", "/forgot-password", "/reset-password", "/error", "/oauth2/onboarding/**", "/oauth2/authorization/**", "/login/oauth2/**", "/css/**", "/js/**", "/images/**", "/webjars/**", "/ws/**", "/ws/raw/**").permitAll()
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auctions", "/api/auctions/*").permitAll()
                .requestMatchers("/api/seller/**").hasRole("SELLER")
                .requestMatchers("/api/reports").hasRole("SELLER")
                .requestMatchers("/api/**").authenticated()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/seller/**").hasRole("SELLER")
                .requestMatchers("/buyer/**").hasRole("BUYER")
                .requestMatchers("/auctions/create", "/auctions/create/**").hasRole("SELLER")
                .requestMatchers("/auctions/edit/**", "/auctions/delete/**").hasRole("SELLER")
                .requestMatchers("/transactions/**").authenticated()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .usernameParameter("username")
                .passwordParameter("password")
                .defaultSuccessUrl("/dashboard", true)
                .failureHandler(authenticationFailureHandler())
                .permitAll()
            )
            .logout(logout -> logout
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout"))
                .logoutSuccessUrl("/login?logout=true")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .permitAll()
            )
            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(accountStatusFilter, UsernamePasswordAuthenticationFilter.class);

        if (isGoogleLoginEnabled()) {
            http.oauth2Login(oauth2 -> oauth2
                    .loginPage("/login")
                    .successHandler(googleOAuth2SuccessHandler));
        }

        return http.build();
    }

    public boolean isGoogleLoginEnabled() {
        return StringUtils.hasText(googleClientId) && StringUtils.hasText(googleClientSecret);
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userService);
        authProvider.setPasswordEncoder(passwordEncoder);
        authProvider.setHideUserNotFoundExceptions(false);
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public AuthenticationFailureHandler authenticationFailureHandler() {
        return (request, response, exception) -> {
            String redirectUrl = "/login?error=true";
            if (exception instanceof DisabledException) {
                String email = request.getParameter("username");
                User user = email != null && !email.isBlank()
                        ? userService.findByEmail(email.trim().toLowerCase()).orElse(null)
                        : null;
                if (user != null && userService.requiresEmailVerification(user)) {
                    redirectUrl = "/register/verify?email=" + java.net.URLEncoder.encode(email, "UTF-8");
                } else {
                    redirectUrl = "/login?disabled=true";
                }
            }
            response.sendRedirect(redirectUrl);
        };
    }
}
