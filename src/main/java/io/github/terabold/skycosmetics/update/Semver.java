package io.github.terabold.skycosmetics.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A release version, ordered as SemVer orders them: {@code 1.5.0-beta.1 < 1.5.0-beta.2 < 1.5.0-rc.1 < 1.5.0 < 1.5.1}.
 * A leading "v" (the tag) and build metadata ({@code +26.1.2}) are ignored. Two or three numbers are accepted, so
 * "1.5" is 1.5.0. The same order Fabric Loader uses to pick between two copies of a mod, which the installer relies
 * on.
 *
 * @param pre the dot-separated pre-release identifiers ("beta", "2"); empty for a stable release
 */
public record Semver(int major, int minor, int patch, List<String> pre) implements Comparable<Semver> {
    public Semver {
        pre = List.copyOf(pre);
    }

    /** The version, or null when it isn't one. */
    public static Semver parse(String s) {
        if (s == null) return null;
        String v = s.strip();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        int plus = v.indexOf('+');
        if (plus >= 0) v = v.substring(0, plus);
        String core = v, tail = "";
        int dash = v.indexOf('-');
        if (dash >= 0) {
            core = v.substring(0, dash);
            tail = v.substring(dash + 1);
            if (tail.isEmpty()) return null;
        }
        String[] nums = core.split("\\.", -1);
        if (nums.length < 2 || nums.length > 3) return null;
        int[] n = new int[3];
        for (int i = 0; i < nums.length; i++) {
            if (!nums[i].matches("\\d{1,9}")) return null;
            n[i] = Integer.parseInt(nums[i]);
        }
        List<String> pre = new ArrayList<>();
        if (!tail.isEmpty()) {
            for (String id : tail.split("\\.", -1)) {
                if (!id.matches("[0-9A-Za-z-]+")) return null;
                pre.add(id.toLowerCase(Locale.ROOT));
            }
        }
        return new Semver(n[0], n[1], n[2], pre);
    }

    /** A beta, alpha or release candidate. */
    public boolean prerelease() {
        return !pre.isEmpty();
    }

    public boolean newerThan(Semver other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(Semver o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) c = Integer.compare(minor, o.minor);
        if (c == 0) c = Integer.compare(patch, o.patch);
        if (c != 0) return c;
        // A pre-release is older than its release; otherwise identifier by identifier, numbers below words.
        if (pre.isEmpty() || o.pre.isEmpty()) return Boolean.compare(pre.isEmpty(), o.pre.isEmpty());
        for (int i = 0; i < Math.min(pre.size(), o.pre.size()); i++) {
            String a = pre.get(i), b = o.pre.get(i);
            boolean an = a.matches("\\d+"), bn = b.matches("\\d+");
            if (an && bn) c = Long.compare(Long.parseLong(a.length() > 18 ? a.substring(0, 18) : a),
                Long.parseLong(b.length() > 18 ? b.substring(0, 18) : b));
            else if (an != bn) c = an ? -1 : 1;
            else c = a.compareTo(b);
            if (c != 0) return c;
        }
        return Integer.compare(pre.size(), o.pre.size());
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (pre.isEmpty() ? "" : "-" + String.join(".", pre));
    }
}
