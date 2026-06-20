package eu.cokeman.velomarker.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UserContextHelper {
    public UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new IllegalStateException("User is not authenticated");
        }
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            String userIdClaim = jwt.getClaimAsString("user_id");
            if (userIdClaim == null || userIdClaim.isEmpty()) {
                throw new IllegalStateException("JWT token is missing user_id claim");
            }
            try {
                return UUID.fromString(userIdClaim);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Invalid user_id format in JWT token", e);
            }
        }
        throw new IllegalStateException("Unsupported authentication type: " + authentication.getClass().getName());
    }

}
