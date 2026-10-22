package io.github.terabold.skycosmetics.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.resources.Identifier;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The house rules for what players read, over every text in the language file: no middle dot, no old mod name,
 * and labels in sentence case (a word after the first is capitalised only when it is a name such as SkyBlock or
 * My Items, an acronym such as PNG, or starts a new sentence). Key names in Controls keep Minecraft's Title Case.
 */
public final class TextRulesTest implements FabricClientGameTest {
    private static final Set<String> NAMES = Set.of("SkyBlock", "SkyCosmetics", "Hypixel", "Minecraft", "GitHub", "My", "Items",
        "Mod", "Menu", "Skyblocker", "SkyHanni", "SkyOcean", "Firmament", "Fabric", "Wardrobe", "Loadouts", "Pets", "Equipment",
        "Ender", "Chest", "Java", "Shift", "Ctrl", "Alt", "Esc", "Enter", "Haste", "Fatigue", "Very", "Special", "Other", "Mods",
        "Settings", "Controls", "Stable", "Betas", "Modrinth", "OptiFine", "Essential", "Zoomify",
        "Dungeons", "Dungeon", "Catacombs", "Rift", "Safari", "Hub", "Garden", "Kuudra", "Crimson", "Isle", "Dwarven", "Mines",
        "Crystal", "Hollows", "Park", "Island", "Bazaar", "Auction", "House");
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z'’]*");
    private static final Set<String> LONG_TEXT = Set.of(".tooltip", ".search", ".sub", ".help", ".hint", ".desc", ".message", ".body", ".note");

    /** Every text that breaks a rule, listed together at the end. */
    private static final java.util.List<String> PROBLEMS = new java.util.ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        PROBLEMS.clear();
        JsonObject lang = ctx.computeOnClient(mc -> {
            try (var in = mc.getResourceManager().getResourceOrThrow(Identifier.fromNamespaceAndPath("skycosmetics", "lang/en_us.json")).open()) {
                return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                throw new AssertionError("the language file can't be read", e);
            }
        });
        int labels = 0;
        for (var e : lang.entrySet()) {
            String key = e.getKey(), text = e.getValue().getAsString();
            rule(text.indexOf('\u00b7') < 0, "a middle dot in " + key);
            rule(!text.contains("Skin Studio"), "the old name in " + key);
            if (key.startsWith("key.") || key.startsWith("skycosmetics.rarity.") || text.length() > 40
                || LONG_TEXT.stream().anyMatch(key::endsWith)) continue;
            labels++;
            String odd = titleCaseWord(text);
            rule(odd == null, "Title Case in " + key + " (\"" + text + "\": " + odd + ")");
        }
        StudioLayoutTest.check(labels > 100, "the labels were read: " + labels);
        int rows = ctx.computeOnClient(mc -> settingsRows());
        StudioLayoutTest.check(rows > 100, "every settings section's rows were read: " + rows);
        StudioLayoutTest.check(PROBLEMS.isEmpty(), PROBLEMS.size() + " texts break the rules: " + String.join("; ", PROBLEMS));
        System.out.println("[SkyCosmeticsTest] text rules: " + lang.size() + " texts, " + labels + " labels, " + rows + " settings rows");
    }

    /**
     * The same rules over what the settings actually show, written in code or in the language file: every section's
     * name, row titles and choice labels. Returns how many rows were read.
     */
    private static int settingsRows() {
        int n = 0;
        for (var section : io.github.terabold.skycosmetics.hub.Hub.sections()) {
            label(section.name().getString(), "section " + section.id());
            java.util.List<io.github.terabold.skycosmetics.hub.Option> options;
            try {
                options = section.rows().apply(null);
            } catch (RuntimeException e) {
                continue; // a section that needs its screen to build its rows
            }
            for (var o : options) {
                n++;
                label(o.title().getString(), o.id());
                rule(o.help().getString().indexOf('\u00b7') < 0, "a middle dot in the help of " + o.id());
                if (o.control() instanceof io.github.terabold.skycosmetics.hub.Control.Choice<?> c) choices(c, o.id());
            }
        }
        return n;
    }

    private static <T> void choices(io.github.terabold.skycosmetics.hub.Control.Choice<T> c, String id) {
        for (T v : c.values()) label(c.label().apply(v).getString(), id + " choice");
    }

    private static void label(String text, String where) {
        rule(text.indexOf('\u00b7') < 0, "a middle dot in " + where);
        rule(!text.contains("Skin Studio"), "the old name in " + where);
        if (text.length() > 40) return;
        String odd = titleCaseWord(text);
        rule(odd == null, "Title Case in " + where + " (\"" + text + "\": " + odd + ")");
    }

    private static void rule(boolean ok, String problem) {
        if (!ok && PROBLEMS.size() < 200) PROBLEMS.add(problem);
    }

    /** The first capitalised word after the first that isn't a name, an acronym or a sentence start; null if none. */
    static String titleCaseWord(String text) {
        Matcher m = WORD.matcher(text);
        boolean first = true;
        while (m.find()) {
            String w = m.group();
            if (first) { first = false; continue; }
            String bare = w.replaceAll("['’]s$", ""); // Hypixel's
            if (!Character.isUpperCase(w.charAt(0)) || NAMES.contains(bare) || bare.equals(bare.toUpperCase())) continue;
            String before = text.substring(0, m.start()).stripTrailing();
            if (before.endsWith(".") || before.endsWith(":") || before.endsWith("!") || before.endsWith("?")
                || before.endsWith("(") || before.endsWith("\"") || before.endsWith("%s")) continue;
            return w;
        }
        return null;
    }
}
