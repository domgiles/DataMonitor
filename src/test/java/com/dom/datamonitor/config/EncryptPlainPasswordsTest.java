package com.dom.datamonitor.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** {@link ConfigWriter#encryptPlainPasswords}: plain passwords are encrypted in the file itself. */
class EncryptPlainPasswordsTest {

    @TempDir Path dir;

    @Test
    void encryptsPlainPasswordsAndKeepsTheRestOfTheFile() throws Exception {
        File f = write("""
            # My dashboard
            settings:
              connection:
                username: system
                password: welcome1        # change me
                connectString: //h/s      # keep this comment
            qmodules:
              - title: "A"
                connection:
                  username: u
                  password: "pa ss#word"
                  connectString: //h2/s
                sql: |
                  SELECT 1 FROM dual
                  password: notapassword
            """);

        assertEquals(2, ConfigWriter.encryptPlainPasswords(f));
        String out = Files.readString(f.toPath());
        assertFalse(out.contains("welcome1"), out);
        assertFalse(out.contains("pa ss#word"), out);
        assertTrue(out.contains("# My dashboard"));
        assertTrue(out.contains("connectString: //h/s      # keep this comment"));
        assertTrue(out.matches("(?s).*password: enc\\([^)]+\\)        # change me.*"), "comment after the value kept");
        assertTrue(out.contains("      password: notapassword"), "SQL is not touched");

        AppConfig config = ConfigLoader.load(f);
        assertEquals("welcome1",   PasswordCipher.reveal(config.getSettings().getConnection().getPassword()));
        assertEquals("pa ss#word", PasswordCipher.reveal(config.getQmodules().get(0).getConnection().getPassword()));
    }

    @Test
    void upgradesTheOldBase64Encoding() throws Exception {
        File f = write("settings:\n  connection:\n    password: enc:dGlnZXI=\n");
        assertEquals(1, ConfigWriter.encryptPlainPasswords(f));
        String stored = storedPassword(Files.readString(f.toPath()));
        assertTrue(PasswordCipher.isEncrypted(stored), stored);
        assertEquals("tiger", PasswordCipher.reveal(stored));
    }

    @Test
    void leavesEncryptedPasswordsAndTheFileAlone() throws Exception {
        String text = "settings:\n  connection:\n    password: " + PasswordCipher.encrypt("tiger") + "\n";
        File f = write(text);
        long modified = f.lastModified();
        assertEquals(0, ConfigWriter.encryptPlainPasswords(f));
        assertEquals(text, Files.readString(f.toPath()));
        assertEquals(modified, f.lastModified());
    }

    @Test
    void keepsWindowsLineEndings() throws Exception {
        File f = write("settings:\r\n  connection:\r\n    password: tiger\r\n    username: u\r\n");
        assertEquals(1, ConfigWriter.encryptPlainPasswords(f));
        String out = Files.readString(f.toPath());
        assertTrue(out.endsWith("    username: u\r\n"), out);
        assertFalse(out.replace("\r\n", "").contains("\n"), "no bare LF line endings");
    }

    private File write(String text) throws Exception {
        Path p = dir.resolve("datamonitor.yaml");
        Files.writeString(p, text);
        return p.toFile();
    }

    private static String storedPassword(String yaml) {
        Matcher m = Pattern.compile("password: (\\S+)").matcher(yaml);
        assertTrue(m.find(), yaml);
        return m.group(1);
    }
}
