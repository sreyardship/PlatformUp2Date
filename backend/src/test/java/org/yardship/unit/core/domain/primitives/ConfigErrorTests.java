package org.yardship.unit.core.domain.primitives;

import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.ConfigError;
import org.yardship.core.domain.primitives.ConfigErrorScope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link ConfigError} is a plain value: equal exactly when application, scope and reason all
 * match (plan.md, Value Objects). It is a Java record, so most of that contract is the language
 * spec, not our behaviour — these two tests pin the one thing worth pinning: field-wise equality,
 * and that the three fields are readable through their own accessors.
 */
class ConfigErrorTests {

    @Test
    void equal_onlyWhenApplicationScopeAndReasonAllMatch() {
        ConfigError a = new ConfigError("alpha", ConfigErrorScope.CURRENT, "blank url");
        ConfigError b = new ConfigError("alpha", ConfigErrorScope.CURRENT, "blank url");
        ConfigError differentApp = new ConfigError("beta", ConfigErrorScope.CURRENT, "blank url");
        ConfigError differentScope = new ConfigError("alpha", ConfigErrorScope.LATEST, "blank url");
        ConfigError differentReason = new ConfigError("alpha", ConfigErrorScope.CURRENT, "unreachable host");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, differentApp);
        assertNotEquals(a, differentScope);
        assertNotEquals(a, differentReason);
    }

    @Test
    void exposesItsApplicationScopeAndReason() {
        ConfigError error = new ConfigError("alpha", ConfigErrorScope.LATEST, "unknown type 'mystery'");

        assertEquals("alpha", error.application());
        assertEquals(ConfigErrorScope.LATEST, error.scope());
        assertEquals("unknown type 'mystery'", error.reason());
    }
}
