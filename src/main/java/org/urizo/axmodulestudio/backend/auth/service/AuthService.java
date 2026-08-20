package org.urizo.axmodulestudio.backend.auth.service;

import java.time.Instant;
import java.util.UUID;

import org.urizo.axmodulestudio.backend.security.AuthenticatedActor;

public interface AuthService {

    IssuedSession login(String loginId, char[] password);

    IssuedSession refresh(String refreshToken);

    void logout(UUID authenticatedAccountId, String refreshToken);

    AuthenticatedActor loadActor(UUID accountId);

    record IssuedSession(
            String accessToken,
            Instant accessExpiresAt,
            String refreshToken,
            Instant refreshExpiresAt,
            AuthenticatedActor actor) {
    }
}
