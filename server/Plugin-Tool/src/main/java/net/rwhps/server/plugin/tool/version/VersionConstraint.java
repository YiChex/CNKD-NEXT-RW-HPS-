package net.rwhps.server.plugin.tool.version;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VersionConstraint {
    private static final Pattern COMPARATOR = Pattern.compile("^(>=|<=|!=|>|<|=)?\\s*(.+)$");

    private final SemVersion lower;
    private final SemVersion upper;
    private final boolean lowerInclusive;
    private final boolean upperInclusive;
    private final String operator;
    private final SemVersion target;

    private VersionConstraint(
        SemVersion lower,
        SemVersion upper,
        boolean lowerInclusive,
        boolean upperInclusive,
        String operator,
        SemVersion target
    ) {
        this.lower = lower;
        this.upper = upper;
        this.lowerInclusive = lowerInclusive;
        this.upperInclusive = upperInclusive;
        this.operator = operator;
        this.target = target;
    }

    public static VersionConstraint parse(String raw) {
        String normalized = Objects.requireNonNull(raw, "raw").trim();
        String compact = normalized.replace(" ", "");
        if (compact.length() >= 5 &&
            (compact.charAt(0) == '[' || compact.charAt(0) == '(') &&
            (compact.charAt(compact.length() - 1) == ']' || compact.charAt(compact.length() - 1) == ')')) {
            int comma = compact.indexOf(',');
            if (comma <= 1 || comma != compact.lastIndexOf(',') || comma >= compact.length() - 2) {
                throw new IllegalArgumentException("invalid version interval: " + raw);
            }
            SemVersion lower = SemVersion.parse(compact.substring(1, comma));
            SemVersion upper = SemVersion.parse(compact.substring(comma + 1, compact.length() - 1));
            if (lower.compareTo(upper) > 0) {
                throw new IllegalArgumentException("version interval lower bound exceeds upper bound");
            }
            return new VersionConstraint(
                lower,
                upper,
                compact.charAt(0) == '[',
                compact.charAt(compact.length() - 1) == ']',
                "",
                null
            );
        }
        Matcher comparator = COMPARATOR.matcher(normalized);
        if (!comparator.matches()) {
            throw new IllegalArgumentException("invalid version constraint: " + raw);
        }
        String operator = comparator.group(1) == null ? "=" : comparator.group(1);
        return new VersionConstraint(null, null, false, false, operator, SemVersion.parse(comparator.group(2)));
    }

    public boolean matches(SemVersion version) {
        if (target != null) {
            int comparison = version.compareTo(target);
            switch (operator) {
                case ">=": return comparison >= 0;
                case "<=": return comparison <= 0;
                case ">": return comparison > 0;
                case "<": return comparison < 0;
                case "!=": return comparison != 0;
                case "=": return comparison == 0;
                default: throw new IllegalStateException("unsupported operator: " + operator);
            }
        }
        int lowerComparison = version.compareTo(lower);
        int upperComparison = version.compareTo(upper);
        return (lowerInclusive ? lowerComparison >= 0 : lowerComparison > 0) &&
            (upperInclusive ? upperComparison <= 0 : upperComparison < 0);
    }
}
