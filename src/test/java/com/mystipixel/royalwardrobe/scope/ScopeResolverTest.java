package com.mystipixel.royalwardrobe.scope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScopeResolverTest {

    @Test
    void theExpansionIsTheTextBeforeTheFirstUnderscore() {
        assertEquals("royalskyblock", ScopeResolver.expansionOf("%royalskyblock_profile_id%"));
        assertEquals("myprofiles", ScopeResolver.expansionOf("%MyProfiles_current%"));
    }

    @Test
    void anythingThatIsNotAPlaceholderHasNoExpansion() {
        assertNull(ScopeResolver.expansionOf(""));
        assertNull(ScopeResolver.expansionOf("royalskyblock_profile_id"));
        assertNull(ScopeResolver.expansionOf("%_profile%"));
        assertNull(ScopeResolver.expansionOf("%noseparator%"));
        assertNull(ScopeResolver.expansionOf(null));
    }

    @Test
    void perProfileModeSticksOnceTheExpansionHasBeenSeen() {
        org.junit.jupiter.api.Assertions.assertFalse(ScopeResolver.perProfile(false, false), "never seen: per-player");
        org.junit.jupiter.api.Assertions.assertTrue(ScopeResolver.perProfile(true, false));
        org.junit.jupiter.api.Assertions.assertTrue(ScopeResolver.perProfile(false, true),
                "an expansion unregistered at runtime must not fall back to the shared wardrobe");
    }
}
