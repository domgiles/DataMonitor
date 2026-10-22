package com.dom.datamonitor.config;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts passwords stored in the config file, e.g. {@code welcome1} becomes
 * {@code enc(Qm9x...)}.
 *
 * <p>Uses AES-GCM with a random IV per value, so the same password encrypts differently
 * each time. The key is built into the application: this keeps passwords out of plain
 * sight (screen shares, a config pasted into a ticket) and lets a config move between
 * machines, but anyone with the application can decrypt them. Keep the config file
 * readable only by you, or pass the password with {@code -p} instead.
 *
 * <p>Plain text is never left in the file: the editors save passwords encrypted, and
 * {@link ConfigWriter#encryptPlainPasswords} encrypts any typed into the file by hand when
 * it is loaded. The older {@code enc:<base64>} form is still read, and re-encrypted the
 * same way; it is an encoding, not encryption.
 */
public final class PasswordCipher {

    private static final String PREFIX     = "enc(";
    private static final String SUFFIX     = ")";
    private static final String OLD_PREFIX = "enc:";
    private static final int    IV_BYTES   = 12;
    private static final int    TAG_BITS   = 128;

    private static final SecretKeySpec KEY = new SecretKeySpec(
        sha256("com.dom.datamonitor/password-cipher/v1"), 0, 16, "AES");
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordCipher() {}

    /** True for {@code enc(...)}; false for plain text and the older {@code enc:...} encoding. */
    public static boolean isEncrypted(String value) {
        return value != null && isNewForm(value);
    }

    /** Returns {@code plain} encrypted as {@code enc(...)}. */
    public static String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(TAG_BITS, iv));
            byte[] body = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out  = new byte[iv.length + body.length];
            System.arraycopy(iv,   0, out, 0,         iv.length);
            System.arraycopy(body, 0, out, iv.length, body.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out) + SUFFIX;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt password", e);
        }
    }

    /**
     * The form to store: plain text and {@code enc:<base64>} become {@code enc(...)};
     * a value already encrypted is kept as it is.
     */
    public static String protect(String value) {
        if (value == null || value.isEmpty() || isEncrypted(value)) return value;
        return encrypt(reveal(value));
    }

    /**
     * Returns the plain-text password for a stored value: {@code enc(...)} is decrypted,
     * {@code enc:...} Base-64 decoded, and anything else returned unchanged.
     *
     * @throws IllegalArgumentException if an encrypted value is damaged or was not made by this class
     */
    public static String reveal(String value) {
        if (value == null) return "";
        if (value.startsWith(OLD_PREFIX)) {
            return new String(Base64.getDecoder().decode(value.substring(OLD_PREFIX.length()).trim()),
                              StandardCharsets.UTF_8);
        }
        if (!isNewForm(value)) return value;
        try {
            byte[] in = Base64.getUrlDecoder().decode(
                value.substring(PREFIX.length(), value.length() - SUFFIX.length()).trim());
            // IV + tag at least; checked here because JDK 17 reports a short value as a ProviderException
            if (in.length < IV_BYTES + TAG_BITS / 8) throw new IllegalArgumentException("too short");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, KEY, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalArgumentException(
                "encrypted password cannot be decrypted; re-enter it or create a new one with --encrypt", e);
        }
    }

    private static boolean isNewForm(String value) {
        return value.startsWith(PREFIX) && value.endsWith(SUFFIX);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
