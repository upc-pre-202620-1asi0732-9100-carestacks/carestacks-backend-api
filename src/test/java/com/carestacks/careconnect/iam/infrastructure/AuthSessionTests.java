package com.carestacks.careconnect.iam.infrastructure;

import com.carestacks.careconnect.iam.application.iam.requests.LoginRequest;
import com.carestacks.careconnect.iam.domain.iam.enums.UserRole;
import com.carestacks.careconnect.iam.infrastructure.persistence.UserJpaEntity;
import com.carestacks.careconnect.iam.infrastructure.repositories.UserJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthSessionTests {
    @Test
    void issuedSessionExpiresAtThirtyMinutesAndCannotBeReconstructed() {
        var clock = new MutableClock();
        var users = mock(UserJpaRepository.class);
        var passwords = mock(PasswordEncoder.class);
        var user = new UserJpaEntity(UUID.randomUUID(), "session@example.test", "hash", "Sesión", UserRole.PATIENT,
                true, 0, null, LocalDateTime.now(), LocalDateTime.now());
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwords.matches("TestPass123", "hash")).thenReturn(true);
        var auth = new AuthServiceImpl(users, passwords, clock);
        var request = new LoginRequest();
        request.setEmail(user.getEmail());
        request.setPassword("TestPass123");
        var token = auth.login(request).token();
        assertEquals(user.getId(), auth.validateSession(token, null).userId());
        assertFalse(auth.validateToken("mock-token-" + user.getId() + "." + clock.instant().plusSeconds(1800).getEpochSecond()));
        clock.now = clock.now.plusSeconds(1799);
        assertTrue(auth.validateToken(token));
        clock.now = clock.now.plusSeconds(1);
        assertFalse(auth.validateToken(token));
    }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-04T22:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
