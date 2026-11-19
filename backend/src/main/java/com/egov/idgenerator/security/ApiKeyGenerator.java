package com.egov.idgenerator.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * API key = "egid_live_" + base64url(32 random bytes from SecureRandom) = 256 bits of entropy, 53 characters.
 *
 * Only SHA-256(key) is stored. A slow password hash (BCrypt) is designed for low-entropy human passwords; a 256-bit random
 * secret cannot be brute-forced, so a fast hash is the standard choice and lets each request be checked with one indexed
 * lookup. The display prefix exposes only 8 of the 43 random characters (48 bits), leaving 208 secret bits.
 */
@Component
public class ApiKeyGenerator {

    public static final String PREFIX = "egid_live_";
    public static final int RANDOM_BYTES = 32;
    public static final int KEY_LENGTH = PREFIX.length() + 43;
    private static final int VISIBLE_CHARS = 8;

    public record GeneratedKey(String plaintext, String displayPrefix, String hash) {
        @Override
        public String toString() { // never print the plaintext key by accident
            return "GeneratedKey[" + displayPrefix + "...]";
        }
    }

    private final SecureRandom random = new SecureRandom();

    public GeneratedKey generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        String key = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new GeneratedKey(key, key.substring(0, PREFIX.length() + VISIBLE_CHARS), hash(key));
    }

    /** Quick shape check before touching the database. */
    public static boolean looksLikeKey(String key) {
        return key != null && key.length() == KEY_LENGTH && key.startsWith(PREFIX)
                && key.substring(PREFIX.length()).chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_');
    }

    public static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
