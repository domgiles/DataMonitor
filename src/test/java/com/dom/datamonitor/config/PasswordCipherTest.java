package com.dom.datamonitor.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordCipherTest {

    @Test
    void encryptsToEncFormAndBack() {
        String enc = PasswordCipher.encrypt("hello");
        assertTrue(enc.matches("enc\\([A-Za-z0-9_-]+\\)"), enc);
        assertFalse(enc.contains("hello"));
        assertEquals("hello", PasswordCipher.reveal(enc));
    }

    @Test
    void samePasswordEncryptsDifferentlyEachTime() {
        String a = PasswordCipher.encrypt("hello");
        String b = PasswordCipher.encrypt("hello");
        assertNotEquals(a, b);
        assertEquals(PasswordCipher.reveal(a), PasswordCipher.reveal(b));
    }

    @Test
    void handlesNonAsciiPasswords() {
        assertEquals("pässwörd€", PasswordCipher.reveal(PasswordCipher.encrypt("pässwörd€")));
    }

    @Test
    void readsPlainAndOldBase64Forms() {
        assertEquals("tiger", PasswordCipher.reveal("tiger"));
        assertEquals("tiger", PasswordCipher.reveal("enc:dGlnZXI="));
        assertEquals("",      PasswordCipher.reveal(null));
    }

    @Test
    void protectEncryptsPlainTextAndOldEncoding() {
        assertEquals("tiger", PasswordCipher.reveal(PasswordCipher.protect("tiger")));
        assertEquals("tiger", PasswordCipher.reveal(PasswordCipher.protect("enc:dGlnZXI=")));
        assertTrue(PasswordCipher.protect("enc:dGlnZXI=").startsWith("enc("));
        String enc = PasswordCipher.encrypt("tiger");
        assertEquals(enc,            PasswordCipher.protect(enc));
        assertNull(PasswordCipher.protect(null));
        assertEquals("",             PasswordCipher.protect(""));
    }

    @Test
    void rejectsTamperedValues() {
        String enc = PasswordCipher.encrypt("hello");
        char c = enc.charAt(10);
        String tampered = enc.substring(0, 10) + (c == 'A' ? 'B' : 'A') + enc.substring(11);
        assertThrows(IllegalArgumentException.class, () -> PasswordCipher.reveal(tampered));
        assertThrows(IllegalArgumentException.class, () -> PasswordCipher.reveal("enc(abc)"));
        assertThrows(IllegalArgumentException.class, () -> PasswordCipher.reveal("enc(not base64!)"));
    }
}
