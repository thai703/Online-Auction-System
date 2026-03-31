package com.example.auctions.controller;

import com.example.auctions.dto.GoogleOAuthPendingProfile;
import com.example.auctions.dto.OAuthOnboardingForm;
import com.example.auctions.model.User;
import com.example.auctions.model.UserRole;
import com.example.auctions.service.AuthenticationSessionService;
import com.example.auctions.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/oauth2/onboarding")
public class OAuthOnboardingController {

    private final UserService userService;
    private final AuthenticationSessionService authenticationSessionService;

    public OAuthOnboardingController(UserService userService,
                                     AuthenticationSessionService authenticationSessionService) {
        this.userService = userService;
        this.authenticationSessionService = authenticationSessionService;
    }

    @GetMapping
    public String showOnboarding(Authentication authentication, HttpSession session, Model model) {
        if (authentication != null && authentication.getPrincipal() instanceof User) {
            return "redirect:/dashboard";
        }

        GoogleOAuthPendingProfile pendingProfile = getPendingProfile(session);
        if (pendingProfile == null) {
            return "redirect:/login?oauth2Error=onboarding_expired";
        }

        if (!model.containsAttribute("onboardingForm")) {
            OAuthOnboardingForm form = new OAuthOnboardingForm();
            form.setFullName(pendingProfile.fullName());
            model.addAttribute("onboardingForm", form);
        }

        model.addAttribute("googleEmail", pendingProfile.email());
        return "auth/oauth-onboarding";
    }

    @PostMapping
    public String completeOnboarding(@Valid @ModelAttribute("onboardingForm") OAuthOnboardingForm onboardingForm,
                                     BindingResult result,
                                     HttpSession session,
                                     HttpServletRequest request,
                                     RedirectAttributes redirectAttributes,
                                     Model model) {
        GoogleOAuthPendingProfile pendingProfile = getPendingProfile(session);
        if (pendingProfile == null) {
            return "redirect:/login?oauth2Error=onboarding_expired";
        }

        if (onboardingForm.getRole() == UserRole.ADMIN) {
            result.rejectValue("role", "error.role", "Admin role cannot be selected here.");
        }

        if (result.hasErrors()) {
            model.addAttribute("googleEmail", pendingProfile.email());
            return "auth/oauth-onboarding";
        }

        User user = userService.completeGoogleOnboarding(pendingProfile, onboardingForm);
        session.removeAttribute(GoogleOAuthPendingProfile.SESSION_ATTRIBUTE);
        authenticationSessionService.signIn(user, request);
        redirectAttributes.addFlashAttribute("success", "Google sign-in completed successfully.");
        return "redirect:/dashboard";
    }

    private GoogleOAuthPendingProfile getPendingProfile(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object pending = session.getAttribute(GoogleOAuthPendingProfile.SESSION_ATTRIBUTE);
        return pending instanceof GoogleOAuthPendingProfile profile ? profile : null;
    }
}
