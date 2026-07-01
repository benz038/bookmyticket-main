package bookmyticket.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

@RestController
public class AuthController {

    private final boolean googleEnabled;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(ObjectProvider<ClientRegistrationRepository> clientRegistrations) {
        this.googleEnabled = clientRegistrations.getIfAvailable() != null;
    }

    @GetMapping("/api/auth/status")
    public Map<String, Object> status() {
        return Map.of("googleEnabled", googleEnabled, "loginUrl", "/oauth2/authorization/google");
    }

    @GetMapping("/dev-login")
    public void devLogin(@RequestParam(defaultValue = "Demo User") String name, @RequestParam(defaultValue = "demo@bmt.local") String email, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (googleEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Use Google sign-in.");
        }
        OAuth2User principal = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("email", email, "name", name, "sub", email), "email");
        var auth = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "dev");

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        response.sendRedirect("/");
    }
}
