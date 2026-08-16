package co.istad.gatewaybff;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the frontend ask "am I signed in?" without provoking a 401 from a
 * protected endpoint.
 *
 * <p>Deliberately mounted under {@code /bff} rather than {@code /api}: everything
 * beginning with {@code /api} is routed to the backend, and relying on a
 * controller quietly out-ranking a gateway route is a subtle thing to leave in
 * place. Richer identity — roles, full name, avatar — still comes from the
 * backend's {@code /api/v1/me}.
 */
@RestController
@RequestMapping("/bff")
public class SessionController {

    @GetMapping("/session")
    public SessionResponse session(@AuthenticationPrincipal OidcUser principal) {
        if (principal == null) {
            return new SessionResponse(false, null, null);
        }
        return new SessionResponse(
                true,
                principal.getPreferredUsername(),
                principal.getEmail()
        );
    }

    public record SessionResponse(
            boolean authenticated,
            String username,
            String email
    ) {
    }
}
