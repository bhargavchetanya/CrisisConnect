package com.crisisconnect.dao;

import com.crisisconnect.model.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserDao extends GenericDao<User, Integer> {

    Optional<User> findByEmail(String email);

    List<User> findPending();

    /** Saves failed-login counter, lock time and last-login time in one statement. */
    boolean updateLoginState(User u);

    boolean updatePassword(int userId, String passwordHash);

    // ---- sessions (only the SHA-256 of the token is stored) ----
    void createSession(String tokenHash, int userId, LocalDateTime expiresAt, String userAgent);

    Optional<User> findBySessionHash(String tokenHash);

    void deleteSession(String tokenHash);

    void deleteSessionsFor(int userId);

    int purgeExpiredSessions();

    // ---- e-mail verification codes (hashed) ----
    void saveEmailCode(int userId, String codeHash, LocalDateTime expiresAt);

    /** Latest code for a user: [codeHash, expiresAt, attempts, createdAt] or empty. */
    Optional<EmailCode> latestEmailCode(int userId);

    void incrementCodeAttempts(int codeId);

    void deleteEmailCodes(int userId);

    record EmailCode(int id, String codeHash, LocalDateTime expiresAt, int attempts, LocalDateTime createdAt) { }
}
