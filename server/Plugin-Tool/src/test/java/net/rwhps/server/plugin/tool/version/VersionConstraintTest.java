package net.rwhps.server.plugin.tool.version;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionConstraintTest {
    @Test
    void supportsComparatorsExactVersionsAndIntervals() {
        SemVersion current = SemVersion.parse("1.2.0");
        assertTrue(VersionConstraint.parse(">= 1.1.0").matches(current));
        assertTrue(VersionConstraint.parse("= 1.2.0").matches(current));
        assertTrue(VersionConstraint.parse("1.2.0").matches(current));
        assertTrue(VersionConstraint.parse("[1.1.0,2.0.0)").matches(current));
        assertFalse(VersionConstraint.parse("[1.0.0,1.2.0)").matches(current));
        assertFalse(VersionConstraint.parse("!= 1.2.0").matches(current));
    }

    @Test
    void followsSemverPrereleaseOrderingAndRejectsMalformedInput() {
        assertTrue(SemVersion.parse("1.2.0").compareTo(SemVersion.parse("1.2.0-rc.1")) > 0);
        assertTrue(SemVersion.parse("1.2.0-rc.2").compareTo(SemVersion.parse("1.2.0-rc.1")) > 0);
        assertThrows(IllegalArgumentException.class, () -> SemVersion.parse("1.2.0-01"));
        assertThrows(IllegalArgumentException.class, () -> SemVersion.parse("1.2.0+bad..meta"));
        assertThrows(IllegalArgumentException.class, () -> VersionConstraint.parse("[1.0.0,]"));
    }
}
