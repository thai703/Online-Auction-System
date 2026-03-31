package com.example.auctions.security;

import com.example.auctions.dto.GoogleOAuthPendingProfile;
import com.example.auctions.model.User;
import com.example.auctions.service.AuthenticationSessionService;
import com.example.auctions.service.UserService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

@Component
public class GoogleOAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserService userService;
    private final AuthenticationSessionService authenticationSessionService;

    public GoogleOAuth2SuccessHandler(UserService userService,
                                      AuthenticationSessionService authenticationSessionService) {
        this.userService = userService;
        this.authenticationSessionService = authenticationSessionService;
        setAlwaysUseDefaultTargetUrl(false);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
        String email = oauth2User.getAttribute("email");
        String displayName = oauth2User.getAttribute("name");
        String googleId = oauth2User.getAttribute("sub");

        if (googleId == null || googleId.isBlank()) {
            googleId = oauth2User.getName();
        }

        if (email == null || email.isBlank()) {
            authenticationSessionService.clearAuthentication(request);
            getRedirectStrategy().sendRedirect(request, response, "/login?oauth2Error=missing_email");
            return;
        }

        // Reject if Google has not verified this email
        Boolean emailVerified = oauth2User.getAttribute("email_verified");
        if (emailVerified == null || !emailVerified) {
            authenticationSessionService.clearAuthentication(request);
            getRedirectStrategy().sendRedirect(request, response, "/login?oauth2Error=email_not_verified");
            return;
        }

        String resolvedDisplayName = (displayName == null || displayName.isBlank()) ? email : displayName.trim();
        Optional<User> existingUser = userService.findByGoogleId(googleId)
                .or(() -> userService.findByEmail(email));

        if (existingUser.isPresent()) {
            User user = existingUser.get();
            if (userService.isBlockedFromGoogleLogin(user)) {
                authenticationSessionService.clearAuthentication(request);
                getRedirectStrategy().sendRedirect(request, response, "/login?oauth2Error=account_disabled");
                return;
            }

            if (userService.isProfileComplete(user)) {
                User signedInUser = userService.linkGoogleAccount(user, googleId, resolvedDisplayName);
                authenticationSessionService.signIn(signedInUser, request);
                request.getSession().removeAttribute(GoogleOAuthPendingProfile.SESSION_ATTRIBUTE);
                getRedirectStrategy().sendRedirect(request, response, "/dashboard");
                return;
            }
        }

        HttpSession session = request.getSession(true);
        session.setAttribute(GoogleOAuthPendingProfile.SESSION_ATTRIBUTE,
                new GoogleOAuthPendingProfile(email, resolvedDisplayName, googleId));
        authenticationSessionService.clearAuthentication(request);
        getRedirectStrategy().sendRedirect(request, response, "/oauth2/onboarding");
    }
}
