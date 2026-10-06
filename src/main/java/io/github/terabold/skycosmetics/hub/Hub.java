package io.github.terabold.skycosmetics.hub;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** The settings' sections. Each feature adds one from its init() on the client thread; the screen lists them. */
public final class Hub {
    /** The studio's own section: first, and where the settings open by default. */
    public static final String STUDIO = "studio";

    private static final List<Section> SECTIONS = new ArrayList<>();

    private Hub() {}

    /** A second section with the same id is a programming error and throws. */
    public static void add(Section s) {
        if (find(s.id()) != null) throw new IllegalArgumentException("Section '" + s.id() + "' is already added");
        SECTIONS.add(s);
        // By id, not name: names are translated, and the language isn't loaded during mod init.
        SECTIONS.sort(Comparator.comparingInt(Section::order).thenComparing(Section::id));
    }

    /** Takes a section out again, for tests that add their own; false when none has this id. */
    public static boolean remove(String id) {
        return SECTIONS.removeIf(s -> s.id().equals(id));
    }

    public static List<Section> sections() {
        return Collections.unmodifiableList(SECTIONS);
    }

    /** The section with this id, or null when its feature isn't in this build. */
    public static Section find(String id) {
        for (Section s : SECTIONS) if (s.id().equals(id)) return s;
        return null;
    }
}
