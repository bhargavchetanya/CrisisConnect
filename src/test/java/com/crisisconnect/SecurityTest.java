package com.crisisconnect;

import com.crisisconnect.util.Security;
import com.crisisconnect.util.TrackingCodes;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Password hashing, token helpers and SOS tracking codes. */
class SecurityTest {

    @Test
    void passwordHashIsSaltedAndVerifiable() {
        String a = Security.hashPassword("Relief2026");
        String b = Security.hashPassword("Relief2026");
        assertNotEquals(a, b, "same password must give different hashes (random salt)");
        assertTrue(a.startsWith("pbkdf2$"));
        assertTrue(Security.verifyPassword("Relief2026", a));
        assertFalse(Security.verifyPassword("relief2026", a));
        assertFalse(Security.verifyPassword("Relief2026", "garbage"));
    }

    @Test
    void trackingCodesAreReadableAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String c = TrackingCodes.next();
            assertTrue(TrackingCodes.isValid(c), c);
            assertFalse(c.matches(".*[01OIL].*"), "no look-alike characters: " + c);
            seen.add(c);
        }
        assertEquals(2000, seen.size());
        assertEquals("CC-7KQ4MZ", TrackingCodes.normalize(" 7kq4mz "));
        assertFalse(TrackingCodes.isValid("CC-0000"));
    }

    @Test
    void contactsAreMaskedForPublicPages() {
        assertEquals("+91 98•••210", Security.mask("+91 98765 43210"));
        assertEquals("b•••@gmail.com", Security.mask("bhargavchetanya47@gmail.com"));
        assertEquals("", Security.mask(null));
    }

    @Test
    void sessionTokensAreRandomAndHashed() {
        String t = Security.randomToken();
        assertTrue(t.length() >= 40);
        assertNotEquals(t, Security.randomToken());
        assertEquals(64, Security.sha256(t).length());
    }
}
