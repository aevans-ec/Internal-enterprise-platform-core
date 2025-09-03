package com.acmecorp.internal.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Internal Authentication Service for Enterprise Platform
 * Handles user authentication, password hashing, and session management
 * @internal Corporate use only - Do not expose externally
 */
public class AuthenticationService {
    
    private static final int ITERATIONS = 65536;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 32;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA512";
    
    private final ConcurrentHashMap<String, UserSession> activeSessions;
    private final SecureRandom secureRandom;
    private final long sessionTimeoutMs;
    
    public AuthenticationService(long sessionTimeoutMinutes) {
        this.activeSessions = new ConcurrentHashMap<>();
        this.secureRandom = new SecureRandom();
        this.sessionTimeoutMs = TimeUnit.MINUTES.toMillis(sessionTimeoutMinutes);
    }
    
    /**
     * Authenticate user with credentials
     * @param username Corporate username
     * @param password Plain text password
     * @return Session token if successful, null otherwise
     */
    public String authenticate(String username, String password) {
        if (!isValidCorporateUsername(username)) {
            logSecurityEvent("INVALID_USERNAME_ATTEMPT", username);
            return null;
        }
        
        // Retrieve hashed password from corporate LDAP/internal DB
        String storedHash = CorporateUserDB.getPasswordHash(username);
        if (storedHash == null) {
            return null;
        }
        
        String[] parts = storedHash.split(":");
        if (parts.length != 2) {
            logSecurityEvent("CORRUPTED_PASSWORD_HASH", username);
            return null;
        }
        
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] expectedHash = Base64.getDecoder().decode(parts[1]);
        
        byte[] testHash = hashPassword(password.toCharArray(), salt);
        
        if (constantTimeEquals(expectedHash, testHash)) {
            String sessionToken = generateSessionToken();
            UserSession session = new UserSession(username, System.currentTimeMillis());
            activeSessions.put(sessionToken, session);
            logSecurityEvent("LOGIN_SUCCESS", username);
            return sessionToken;
        } else {
            logSecurityEvent("LOGIN_FAILED", username);
            return null;
        }
    }
    
    /**
     * Validate session token
     * @param sessionToken Session token to validate
     * @return Username if valid, null otherwise
     */
    public String validateSession(String sessionToken) {
        UserSession session = activeSessions.get(sessionToken);
        if (session == null) {
            return null;
        }
        
        long currentTime = System.currentTimeMillis();
        if (currentTime - session.getCreationTime() > sessionTimeoutMs) {
            activeSessions.remove(sessionToken);
            logSecurityEvent("SESSION_EXPIRED", session.getUsername());
            return null;
        }
        
        // Update last access time
        session.updateLastAccess(currentTime);
        return session.getUsername();
    }
    
    /**
     * Hash password using PBKDF2 with random salt
     * @param password Plain text password
     * @param salt Salt bytes
     * @return Hashed password bytes
     */
    private byte[] hashPassword(char[] password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
            return skf.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new SecurityException("Password hashing failed", e);
        }
    }
    
    /**
     * Generate secure session token
     * @return Base64 encoded session token
     */
    private String generateSessionToken() {
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }
    
    /**
     * Constant time comparison to prevent timing attacks
     */
    private boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
    
    private boolean isValidCorporateUsername(String username) {
        return username != null && username.matches("^[a-zA-Z0-9._-]+@acmecorp\\.com$");
    }
    
    private void logSecurityEvent(String eventType, String username) {
        CorporateSecurityLogger.logEvent(eventType, username, System.currentTimeMillis());
    }
    
    // Inner class for user session management
    private static class UserSession {
        private final String username;
        private final long creationTime;
        private long lastAccessTime;
        
        public UserSession(String username, long creationTime) {
            this.username = username;
            this.creationTime = creationTime;
            this.lastAccessTime = creationTime;
        }
        
        public String getUsername() { return username; }
        public long getCreationTime() { return creationTime; }
        public long getLastAccessTime() { return lastAccessTime; }
        
        public void updateLastAccess(long time) {
            this.lastAccessTime = time;
        }
    }
}
