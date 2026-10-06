package io.github.terabold.skycosmetics.hand;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.Settings;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Hand feature: how your first-person hand and held item are drawn. A pose for everything, plus rules for a
 * kind of item (swords, bows...) or one SkyBlock item (by id); the most specific rule wins. Presets save the
 * whole setup, and share codes carry it between players.
 *
 * Purely visual. Only {@code ItemInHandRenderer}'s own drawing and its own fields change ({@link HandRender});
 * swings are timed by SkyCosmetics' own timer ({@link HandSwing}). The player's real swing, attack cooldown,
 * selected slot and items are only ever read, and nothing is sent to the server.
 *
 * Kept in settings.json under "hand". Every change bumps {@link #version}, which the per-hand pose caches
 * compare, so the render path never looks a rule up twice for the same stack.
 */
public final class Hand {
    /** When the held item dips down and back up. */
    public enum Equip {
        /** As Minecraft: on every swap, lore update, attack cooldown and ability use. */
        NORMAL("normal"),
        /** Only when you switch hotbar slots. */
        SLOTS("slots"),
        /** Never. */
        OFF("off");

        public final String key;

        Equip(String key) {
            this.key = key;
        }
    }

    /** A pose for one kind of item ({@link HandCategory#key}) or one SkyBlock item ({@code item:<ID>}). */
    public record Rule(String key, String name, HandPose pose) {
        public Rule with(HandPose p) {
            return new Rule(key, name, p);
        }
    }

    /** A saved setup: every pose and option, as a share code holds it. */
    public record Preset(String name, JsonObject setup) {}

    /** Rule key of the pose for everything. */
    public static final String EVERYTHING = "";
    /** Item rules are keyed "item:" + the SkyBlock id. */
    public static final String ITEM = "item:";
    /** Saved before a preset, import or code replaces a setup nobody saved. */
    public static final String PREVIOUS = "Previous setup";
    static final int MAX_RULES = 200, MAX_PRESETS = 50, MAX_NAME = 64, MAX_ID = 128;

    // Options. Defaults draw exactly as Minecraft does.
    static boolean enabled = true;
    static Equip equip = Equip.NORMAL;
    /** How far the hand lags behind when you turn: 0 none, 1 Minecraft's. */
    static float sway = 1;
    static boolean emptyAndMaps = true;
    static boolean ignoreEffects;
    static boolean fullSwings;
    static HandPose everything = HandPose.VANILLA;
    private static final Map<String, Rule> RULES = new LinkedHashMap<>();
    private static final List<Preset> PRESETS = new ArrayList<>();

    private static int version;
    /** On, and something differs from Minecraft's hand (or the editor is previewing): what every hook checks. */
    private static boolean active;

    /** Set while the editor is open: the main hand shows this rule's pose (and maybe a sample item). */
    private static String previewKey;
    private static ItemStack previewStack;

    private Hand() {}

    public static void init() {
        Settings.register("hand", Hand::read, Hand::write);
        HandSwing.init();
        HandSection.register();
    }

    // ------------------------------------------------------------- state ---

    public static boolean enabled() {
        return enabled;
    }

    public static Equip equip() {
        return equip;
    }

    public static float sway() {
        return sway;
    }

    public static boolean emptyAndMaps() {
        return emptyAndMaps;
    }

    public static boolean ignoreEffects() {
        return ignoreEffects;
    }

    public static boolean fullSwings() {
        return fullSwings;
    }

    public static int version() {
        return version;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        changed();
    }

    public static void setEquip(Equip e) {
        if (e != null) equip = e;
        changed();
    }

    public static void setSway(float s) {
        sway = Float.isFinite(s) ? Math.clamp(s, 0, 2) : 1;
        changed();
    }

    public static void setEmptyAndMaps(boolean on) {
        emptyAndMaps = on;
        changed();
    }

    public static void setIgnoreEffects(boolean on) {
        ignoreEffects = on;
        changed();
    }

    public static void setFullSwings(boolean on) {
        fullSwings = on;
        changed();
    }

    /** After any change: poses are looked up again on the next frame. Saving is the caller's (once per edit). */
    static void changed() {
        version++;
        active = enabled && (previewKey != null || !isVanillaSetup());
    }

    /**
     * Whether the hooks change anything. False when the feature is off or nothing differs from Minecraft, so a
     * default install draws (and times swings) exactly as Minecraft, at the cost of one boolean per hook.
     */
    public static boolean active() {
        return active;
    }

    public static void save() {
        Settings.save();
    }

    // ------------------------------------------------------------- rules ---

    /** The pose for everything first, then the rules: kinds in a fixed order, then items by name. */
    public static List<Rule> rules() {
        List<Rule> out = new ArrayList<>(RULES.size() + 1);
        out.add(new Rule(EVERYTHING, "", everything));
        for (HandCategory c : HandCategory.values()) {
            Rule r = RULES.get(c.key);
            if (r != null) out.add(r);
        }
        List<Rule> items = new ArrayList<>();
        for (Rule r : RULES.values()) if (r.key().startsWith(ITEM)) items.add(r);
        items.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        out.addAll(items);
        return Collections.unmodifiableList(out);
    }

    public static Rule rule(String key) {
        if (EVERYTHING.equals(key)) return new Rule(EVERYTHING, "", everything);
        return key == null ? null : RULES.get(key);
    }

    /** The pose a rule key has, or null; allocates nothing (the editor's preview reads it every frame). */
    public static HandPose pose(String key) {
        if (EVERYTHING.equals(key)) return everything;
        Rule r = key == null ? null : RULES.get(key);
        return r == null ? null : r.pose();
    }

    /** Changes a rule's pose (or the pose for everything) in place; unknown keys are ignored. */
    public static void setPose(String key, HandPose pose) {
        if (pose == null) return;
        if (EVERYTHING.equals(key)) everything = pose;
        else {
            Rule r = RULES.get(key);
            if (r == null) return;
            RULES.put(key, r.with(pose));
        }
        changed();
    }

    /**
     * Adds a rule starting from the pose that applies to it now, so adding one never changes what you see; a rule
     * that exists is kept. Returns the rule, or null when the key isn't one a rule can have.
     */
    public static Rule addRule(String key, String name) {
        Rule have = RULES.get(key);
        if (have != null) return have;
        if (!validKey(key) || RULES.size() >= MAX_RULES) return null;
        HandPose start = everything;
        if (key.startsWith(ITEM)) {
            // An item rule starts from its kind's rule, when the item is at hand to tell which kind it is.
            HandCategory c = categoryOfHeld(key.substring(ITEM.length()));
            if (c != null && RULES.containsKey(c.key)) start = RULES.get(c.key).pose();
        }
        Rule r = new Rule(key, cleanName(name, key), start);
        RULES.put(key, r);
        changed();
        return r;
    }

    public static void removeRule(String key) {
        if (RULES.remove(key) != null) changed();
    }

    /** Puts a removed rule back as it was (Undo). */
    public static void restoreRule(Rule r) {
        if (r == null || !validKey(r.key()) || RULES.containsKey(r.key()) || RULES.size() >= MAX_RULES) return;
        RULES.put(r.key(), r);
        changed();
    }

    /** What a rule is called in the menus: "Everything", "Sword", or the item's name. */
    public static net.minecraft.network.chat.Component label(String key) {
        if (EVERYTHING.equals(key)) return net.minecraft.network.chat.Component.translatable("skycosmetics.hand.rule.everything");
        HandCategory c = HandCategory.byKey(key);
        if (c != null) return c.label();
        Rule r = key == null ? null : RULES.get(key);
        return net.minecraft.network.chat.Component.literal(r != null ? r.name() : cleanName(null, key));
    }

    /** A rule key: a kind's ("sword") or an item's ("item:TERMINATOR"). */
    static boolean validKey(String key) {
        if (key == null) return false;
        if (HandCategory.byKey(key) != null) return true;
        if (!key.startsWith(ITEM)) return false;
        String id = key.substring(ITEM.length());
        return !id.isEmpty() && id.length() <= MAX_ID && id.chars().noneMatch(Character::isWhitespace);
    }

    /** "Aspect of the Dragons" for item:ASPECT_OF_THE_DRAGONS when no name was given. */
    static String cleanName(String name, String key) {
        String n = name == null ? "" : name.strip().replaceAll("\\p{Cntrl}", "");
        if (n.length() > MAX_NAME) n = n.substring(0, MAX_NAME);
        if (!n.isEmpty()) return n;
        if (key == null || !key.startsWith(ITEM)) return "";
        String[] words = key.substring(ITEM.length()).toLowerCase(Locale.ROOT).split("[_:]");
        StringBuilder b = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            boolean small = !b.isEmpty() && SMALL_WORDS.contains(w);
            if (!b.isEmpty()) b.append(' ');
            b.append(small ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return b.toString();
    }

    private static final java.util.Set<String> SMALL_WORDS = java.util.Set.of("of", "the", "and", "a", "an", "in", "on");

    private static HandCategory categoryOfHeld(String id) {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null) return null;
            for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
                ItemStack s = mc.player.getInventory().getItem(i);
                Cosmetics.Ident ident = Cosmetics.identify(s);
                if (ident != null && id.equals(ident.type())) return HandCategory.of(s);
            }
        } catch (RuntimeException e) {
            Io.failed("Finding an item for a hand rule", e);
        }
        return null;
    }

    // ------------------------------------------------------------ lookup ---

    /**
     * The rule key that decides how this stack is held: its item's rule, else its kind's, else everything's.
     * Empty hands and maps use everything's pose only with "Empty Hand & Maps" on; null means vanilla.
     */
    public static String keyFor(ItemStack s) {
        if (s == null || s.isEmpty()) {
            if (RULES.containsKey(HandCategory.EMPTY.key)) return HandCategory.EMPTY.key;
            return emptyAndMaps ? EVERYTHING : null;
        }
        Cosmetics.Ident id = Cosmetics.identify(s);
        if (id != null && id.type() != null) {
            String k = ITEM + id.type();
            if (RULES.containsKey(k)) return k;
        }
        if (s.has(DataComponents.MAP_ID)) return emptyAndMaps ? EVERYTHING : null;
        HandCategory c = HandCategory.of(s);
        if (RULES.containsKey(c.key)) return c.key;
        return EVERYTHING;
    }

    /** The pose this stack is drawn with, ignoring the editor's preview; null when it is drawn as Minecraft does. */
    public static HandPose resolve(ItemStack s) {
        String key = keyFor(s);
        HandPose p = key == null ? null : pose(key);
        return p == null || p.isVanilla() ? null : p;
    }

    // ----------------------------------------------------------- preview ---

    /** The editor is showing this rule on your main hand, with {@code sample} drawn instead of it (null: as held). */
    public static void preview(String key, ItemStack sample) {
        previewKey = key;
        previewStack = sample;
        changed();
    }

    public static void endPreview() {
        if (previewKey == null && previewStack == null) return;
        previewKey = null;
        previewStack = null;
        changed();
    }

    public static String previewKey() {
        return previewKey;
    }

    static ItemStack previewStack() {
        return previewStack;
    }

    // ------------------------------------------------------------ presets ---

    public static List<Preset> presets() {
        return Collections.unmodifiableList(PRESETS);
    }

    /** Saves the current setup under a free "Preset N" name, or {@code name}; returns the preset. */
    public static Preset savePreset(String name) {
        String n = name == null || name.isBlank() ? freeName() : cleanName(name, "");
        Preset p = new Preset(n, setup());
        PRESETS.removeIf(x -> x.name().equals(n));
        if (PRESETS.size() >= MAX_PRESETS) PRESETS.removeFirst();
        PRESETS.add(p);
        return p;
    }

    private static String freeName() {
        for (int i = 1; ; i++) {
            String n = "Preset " + i;
            if (PRESETS.stream().noneMatch(p -> p.name().equals(n))) return n;
        }
    }

    /** Removes a saved preset; returns it (for Undo), or null when there was none by that name. */
    public static Preset removePreset(String name) {
        for (int i = 0; i < PRESETS.size(); i++) {
            if (PRESETS.get(i).name().equals(name)) return PRESETS.remove(i);
        }
        return null;
    }

    /** Puts a removed preset back (Undo); one saved since under the same name wins. */
    public static void restorePreset(Preset p) {
        if (p == null || PRESETS.size() >= MAX_PRESETS || PRESETS.stream().anyMatch(x -> x.name().equals(p.name()))) return;
        PRESETS.add(p);
    }

    /**
     * Replaces the whole setup (a preset, an import, a pasted code). The setup it replaces is kept as "Previous
     * setup" first, unless it is vanilla or already saved, so nothing is lost by a misclick.
     */
    public static void apply(JsonObject setup) {
        JsonObject now = setup();
        boolean saved = isVanillaSetup() || PRESETS.stream().anyMatch(p -> p.setup().equals(now));
        if (!saved && !now.equals(setup)) savePreset(PREVIOUS);
        readSetup(setup);
        enabled = true; // what was picked shows at once
        changed();
    }

    /** No pose, rule or option differs from Minecraft's. */
    public static boolean isVanillaSetup() {
        return everything.isVanilla() && RULES.isEmpty() && equip == Equip.NORMAL && sway == 1 && emptyAndMaps
            && !ignoreEffects && !fullSwings;
    }

    // --------------------------------------------------------------- JSON ---

    /** Every pose and option, without presets: what a preset or share code holds. */
    public static JsonObject setup() {
        JsonObject o = new JsonObject();
        o.add("pose", everything.toJson());
        JsonObject rules = new JsonObject();
        for (Rule r : rules()) {
            if (r.key().equals(EVERYTHING)) continue;
            JsonObject e = new JsonObject();
            if (r.key().startsWith(ITEM)) e.addProperty("name", r.name());
            e.add("pose", r.pose().toJson());
            rules.add(r.key(), e);
        }
        o.add("rules", rules);
        o.addProperty("equip", equip.key);
        o.addProperty("sway", sway);
        o.addProperty("emptyHandAndMaps", emptyAndMaps);
        o.addProperty("ignoreEffects", ignoreEffects);
        o.addProperty("fullSwings", fullSwings);
        return o;
    }

    /** Reads a setup; anything missing or broken becomes Minecraft's value, never the previous one. */
    static void readSetup(JsonObject o) {
        everything = HandPose.fromJson(o.get("pose"));
        RULES.clear();
        if (o.get("rules") instanceof JsonObject rules) {
            for (Map.Entry<String, JsonElement> e : rules.entrySet()) {
                if (RULES.size() >= MAX_RULES || !validKey(e.getKey())) continue;
                JsonObject r = Settings.obj(e.getValue());
                RULES.put(e.getKey(), new Rule(e.getKey(), cleanName(Settings.str(r.get("name"), ""), e.getKey()),
                    HandPose.fromJson(r.get("pose"))));
            }
        }
        equip = Equip.NORMAL;
        String eq = Settings.str(o.get("equip"), "");
        for (Equip e : Equip.values()) if (e.key.equals(eq)) equip = e;
        sway = (float) Settings.num(o.get("sway"), 1, 0, 2);
        emptyAndMaps = Settings.bool(o.get("emptyHandAndMaps"), true);
        ignoreEffects = Settings.bool(o.get("ignoreEffects"), false);
        fullSwings = Settings.bool(o.get("fullSwings"), false);
    }

    private static void read(JsonElement e) {
        if (e == null) return; // nothing saved yet: keep the defaults
        JsonObject o = Settings.obj(e);
        readSetup(o);
        enabled = Settings.bool(o.get("enabled"), true);
        PRESETS.clear();
        if (o.get("presets") instanceof JsonArray list) {
            for (JsonElement p : list) {
                if (PRESETS.size() >= MAX_PRESETS || !(p instanceof JsonObject po)) continue;
                String name = cleanName(Settings.str(po.get("name"), ""), "");
                if (name.isEmpty() || !(po.get("setup") instanceof JsonObject s)) continue;
                PRESETS.removeIf(x -> x.name().equals(name));
                PRESETS.add(new Preset(name, s.deepCopy()));
            }
        }
        changed();
    }

    private static JsonElement write() {
        JsonObject o = setup();
        o.add("enabled", new JsonPrimitive(enabled));
        JsonArray list = new JsonArray();
        for (Preset p : PRESETS) {
            JsonObject po = new JsonObject();
            po.addProperty("name", p.name());
            po.add("setup", p.setup().deepCopy());
            list.add(po);
        }
        o.add("presets", list);
        return o;
    }

    /** Back to Minecraft's hand, presets kept (for tests and the Vanilla preset). */
    public static void reset() {
        readSetup(new JsonObject());
        enabled = true;
        changed();
    }
}
