package net.rwhps.server.plugin.tool.version;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SemVersion implements Comparable<SemVersion> {
    private static final Pattern PATTERN = Pattern.compile(
        "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*))?" +
            "(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?$"
    );

    public final int major;
    public final int minor;
    public final int patch;
    public final String prerelease;
    public final String metadata;

    private SemVersion(int major, int minor, int patch, String prerelease, String metadata) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
        this.metadata = metadata;
    }

    public static SemVersion parse(String raw) {
        Matcher matcher = PATTERN.matcher(Objects.requireNonNull(raw, "raw").trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("invalid semantic version: " + raw);
        }
        String prerelease = valueOrEmpty(matcher.group(4));
        validatePrerelease(prerelease);
        String metadata = valueOrEmpty(matcher.group(5));
        validateIdentifiers(metadata, "metadata");
        return new SemVersion(
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
            matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)),
            prerelease,
            metadata
        );
    }

    @Override
    public int compareTo(SemVersion other) {
        int compared = Integer.compare(major, other.major);
        if (compared == 0) compared = Integer.compare(minor, other.minor);
        if (compared == 0) compared = Integer.compare(patch, other.patch);
        if (compared != 0) return compared;
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0;
        if (prerelease.isEmpty()) return 1;
        if (other.prerelease.isEmpty()) return -1;
        List<String> left = split(prerelease);
        List<String> right = split(other.prerelease);
        int length = Math.max(left.size(), right.size());
        for (int index = 0; index < length; index++) {
            if (index >= left.size()) return -1;
            if (index >= right.size()) return 1;
            String a = left.get(index);
            String b = right.get(index);
            boolean aNumber = a.matches("[0-9]+");
            boolean bNumber = b.matches("[0-9]+");
            int value;
            if (aNumber && bNumber) value = compareNumericIdentifiers(a, b);
            else if (aNumber) value = -1;
            else if (bNumber) value = 1;
            else value = a.compareTo(b);
            if (value != 0) return value;
        }
        return 0;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch +
            (prerelease.isEmpty() ? "" : "-" + prerelease) +
            (metadata.isEmpty() ? "" : "+" + metadata);
    }

    private static void validatePrerelease(String prerelease) {
        validateIdentifiers(prerelease, "prerelease");
        for (String identifier : split(prerelease)) {
            if (identifier.matches("[0-9]+") && identifier.length() > 1 && identifier.startsWith("0")) {
                throw new IllegalArgumentException("numeric prerelease identifier has a leading zero: " + identifier);
            }
        }
    }

    private static void validateIdentifiers(String value, String label) {
        for (String identifier : split(value)) {
            if (identifier.isEmpty()) throw new IllegalArgumentException("empty " + label + " identifier");
        }
    }

    private static int compareNumericIdentifiers(String left, String right) {
        String a = left.replaceFirst("^0+(?!$)", "");
        String b = right.replaceFirst("^0+(?!$)", "");
        int length = Integer.compare(a.length(), b.length());
        return length == 0 ? a.compareTo(b) : length;
    }

    private static List<String> split(String value) {
        List<String> result = new ArrayList<>();
        if (!value.isEmpty()) {
            for (String part : value.split("\\.", -1)) result.add(part);
        }
        return result;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
