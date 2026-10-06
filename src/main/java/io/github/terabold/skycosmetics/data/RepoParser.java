package io.github.terabold.skycosmetics.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.terabold.skycosmetics.SkyCosmetics;
import net.minecraft.ChatFormatting;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NEU repo files -> skins, dyes and runes. Pure: it reads a {@link RepoSource.Source}
 * and returns a new {@link Parsed}, touching no shared state, so a failed or
 * superseded parse can simply be dropped.
 *
 * The ~9k item files are read and decoded on a few worker threads (file opens
 * dominate on Windows); everything order-dependent runs afterwards on the
 * calling thread in sorted file order, so the result is the same every time.
 */
final class RepoParser {
    private static final Pattern TEXTURE = Pattern.compile("Value:\"([A-Za-z0-9+/=]+)\"");
    private static final Pattern PET_ID = Pattern.compile("^(.+);(\\d)$");
    /** "EPIC COSMETIC", "VERY SPECIAL COSMETIC", April's "LEGENJERRY COSMETIC". */
    static final Pattern COSMETIC = Pattern.compile("^[A-Z]+(?: [A-Z]+)? COSMETIC$");
    /** Runes, statues and Ditto blobs are COSMETIC skulls too; only skins end in "Skin". */
    static final Pattern SKIN_NAME = Pattern.compile("(?:^| )Skin$");
    private static final String[] TIERS = {"Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic", "Divine"};
    private static final String[] VANILLA_DYES = {"Ink Sac", "Red Dye", "Green Dye", "Cocoa Beans",
        "Lapis Lazuli", "Purple Dye", "Cyan Dye", "Light Gray Dye", "Gray Dye", "Pink Dye", "Lime Dye",
        "Yellow Dye", "Light Blue Dye", "Magenta Dye", "Orange Dye", "Bone Meal"};
    /** 1.8 INK_SACK metadata order (black..white) mapped to today's dye items. */
    private static final Item[] VANILLA_DYE_ITEMS = {
        Items.BLACK_DYE, Items.RED_DYE, Items.GREEN_DYE, Items.BROWN_DYE, Items.BLUE_DYE, Items.PURPLE_DYE,
        Items.CYAN_DYE, Items.LIGHT_GRAY_DYE, Items.GRAY_DYE, Items.PINK_DYE, Items.LIME_DYE, Items.YELLOW_DYE,
        Items.LIGHT_BLUE_DYE, Items.MAGENTA_DYE, Items.ORANGE_DYE, Items.WHITE_DYE};

    private RepoParser() {}

    /** A repo, parsed. The skins map holds repo entries only; learned ones are merged on top per catalog. */
    static final class Parsed {
        final String label;
        final int items;
        final Map<String, SkinEntry> skins;
        final Map<String, DyeEntry> dyes;
        final Map<String, String> names;
        final List<DyeEntry> dyeOrder;
        final int missingFrames;

        Parsed(String label, int items, Map<String, SkinEntry> skins, Map<String, DyeEntry> dyes,
               Map<String, String> names, List<DyeEntry> dyeOrder, int missingFrames) {
            this.label = label;
            this.items = items;
            this.skins = skins;
            this.dyes = dyes;
            this.names = names;
            this.dyeOrder = dyeOrder;
            this.missingFrames = missingFrames;
        }
    }

    /** What one item file contributes; decoded on a worker thread. */
    private record ItemInfo(String id, String name, int color, String texture, SkinEntry.Kind kind,
                            boolean animatedLore, SkinEntry.Use use) {}

    private record Named(String name, int color) {}

    static Parsed parse(RepoSource.Source src, String label) throws IOException {
        JsonObject animated = JsonParser.parseString(text(src, "constants/animatedskulls.json")).getAsJsonObject();
        JsonObject dyesJson = JsonParser.parseString(text(src, "constants/dyes.json")).getAsJsonObject();
        JsonObject anim = animated.getAsJsonObject("skins");
        if (anim == null) throw new IOException("animatedskulls.json has no 'skins'");

        List<String> paths = src.items();
        ItemInfo[] infos = readAll(src, paths);

        Map<String, SkinEntry> skins = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        Map<String, Integer> nameColors = new HashMap<>();
        Set<String> headTextures = new HashSet<>();
        Map<String, String> itemTextures = new HashMap<>();
        Set<String> animatedLore = new HashSet<>();
        int items = 0;

        for (ItemInfo it : infos) {
            if (it == null) continue;
            items++;
            if (!it.id.contains(";")) {
                names.put(it.id, it.name);
                nameColors.put(it.id, it.color);
            }
            if (it.texture == null) continue;
            itemTextures.put(it.id, it.texture);
            if (it.animatedLore) animatedLore.add(it.id);

            String name = it.name;
            Matcher pet = PET_ID.matcher(it.id);
            if (pet.matches()) {
                int tier = Integer.parseInt(pet.group(2));
                name = it.name + " Pet (" + (tier < TIERS.length ? TIERS[tier] : "Tier " + tier) + ")";
            }
            // Plain heads repeat a lot (one pet texture per rarity). Every id stays
            // resolvable, but only the first of each texture is listed in the grid.
            SkinEntry entry = SkinEntry.still(it.id, name, it.color, it.kind, it.texture, null);
            entry.use = it.use;
            if (it.kind == SkinEntry.Kind.HEAD && !headTextures.add(it.texture)) entry.listed = false;
            skins.put(it.id, entry);
        }

        // Names and colours for variants that exist only in animatedskulls.json.
        Map<String, Named> helpNames = new HashMap<>();
        Set<String> indexed = new HashSet<>();
        JsonObject help = animated.getAsJsonObject("help");
        if (help != null) {
            for (Map.Entry<String, JsonElement> e : help.entrySet()) {
                if (!e.getValue().isJsonArray()) continue;
                JsonArray lines = e.getValue().getAsJsonArray();
                if (lines.size() > 0 && lines.get(0).getAsString().contains(":number")) indexed.add(e.getKey());
                for (int i = 1; i < lines.size(); i++) {
                    String line = lines.get(i).getAsString();
                    String n = strip(line).trim();
                    if (n.isEmpty()) continue;
                    helpNames.put(n.toUpperCase(Locale.ROOT).replace(' ', '_'), new Named(n, colorOf(line)));
                }
            }
        }

        // Every "ID" and "ID_" prefix that has real frames, to spot animated items without any.
        Set<String> framed = new HashSet<>();
        for (Map.Entry<String, JsonElement> e : anim.entrySet()) {
            String id = e.getKey();
            // FERMENTO_ULTIMATE is a pick-by-index sheet, not an animation; its
            // real variants are listed separately.
            if (indexed.contains(id) || !e.getValue().isJsonObject()) continue;
            JsonObject o = e.getValue().getAsJsonObject();
            String[] frames = frames(o.getAsJsonArray("textures"));
            if (frames == null) continue;
            int[] ticks = ticks(o, frames.length);
            if (frames.length > 1) {
                framed.add(id);
                for (int i = id.indexOf('_'); i > 0; i = id.indexOf('_', i + 1)) framed.add(id.substring(0, i));
            }

            SkinEntry existing = skins.get(id);
            if (existing != null) {
                SkinEntry entry = new SkinEntry(id, existing.name, existing.color, existing.kind, frames, ticks, null);
                entry.use = existing.use;
                skins.put(id, entry);
                continue;
            }
            String parent = parentOf(id, skins);
            String name;
            int color;
            Named hn = helpNames.get(id);
            if (parent != null) {
                SkinEntry p = skins.get(parent);
                name = p.name + " (" + Catalog.title(id.substring(parent.length() + 1)) + ")";
                color = hn != null ? hn.color : p.color;
            } else if (hn != null) {
                name = hn.name;
                color = hn.color;
            } else {
                name = Catalog.title(id);
                color = 0xFFFF55FF;
            }
            skins.put(id, new SkinEntry(id, name, color, SkinEntry.Kind.VARIANT, frames, ticks, parent));
        }

        int missing = 0;
        for (String id : animatedLore) {
            SkinEntry e = skins.get(id);
            if (e != null && !e.animated() && !framed.contains(id)) {
                e.missingFrames = true;
                missing++;
            }
        }

        // -- dyes: animated first, then Hypixel static, then vanilla --
        Map<String, DyeEntry> dyes = new LinkedHashMap<>();
        List<DyeEntry> order = new ArrayList<>();
        List<DyeEntry> group = new ArrayList<>();
        JsonObject a = dyesJson.getAsJsonObject("animated");
        if (a != null) {
            for (Map.Entry<String, JsonElement> e : a.entrySet()) {
                String id = e.getKey();
                // NEU also lists FAIRY_HELMET..BOOTS here: the colour cycle of Fairy
                // armour, not a dye anyone can own. Listed, they confused players.
                if (!isAnimatedDye(id)) continue;
                JsonArray arr = e.getValue().getAsJsonArray();
                int[] cols = new int[arr.size()];
                for (int i = 0; i < cols.length; i++) cols[i] = hex(arr.get(i).getAsString());
                if (cols.length == 0) continue;
                group.add(new DyeEntry(id, names.getOrDefault(id, Catalog.title(id)),
                    nameColors.getOrDefault(id, 0xFFFF55FF), cols));
            }
        }
        addSorted(group, dyes, order);
        JsonObject st = dyesJson.getAsJsonObject("static");
        if (st != null) {
            for (Map.Entry<String, JsonElement> e : st.entrySet()) {
                String id = e.getKey();
                group.add(new DyeEntry(id, names.getOrDefault(id, Catalog.title(id)),
                    nameColors.getOrDefault(id, 0xFFFFFFFF), new int[]{hex(e.getValue().getAsString())}));
            }
        }
        addSorted(group, dyes, order);
        JsonObject va = dyesJson.getAsJsonObject("vanilla");
        if (va != null) {
            for (Map.Entry<String, JsonElement> e : va.entrySet()) {
                String id = e.getKey();
                int meta = id.contains("-") ? Integer.parseInt(id.substring(id.indexOf('-') + 1)) : 0;
                String n = meta >= 0 && meta < VANILLA_DYES.length ? VANILLA_DYES[meta] : Catalog.title(id);
                DyeEntry d = new DyeEntry(id, n + " (vanilla)", 0xFFAAAAAA, new int[]{hex(e.getValue().getAsString())});
                if (meta >= 0 && meta < VANILLA_DYE_ITEMS.length) d.vanillaItem = VANILLA_DYE_ITEMS[meta];
                dyes.put(id, d);
                order.add(d);
            }
        }

        for (DyeEntry d : order) d.texture = itemTextures.get(d.id);
        return new Parsed(label, items, skins, dyes, names, order, missing);
    }

    // ------------------------------------------------------------ items ---

    /** Reads and decodes every item file on a few threads; slot i holds paths[i] (null if unreadable). */
    private static ItemInfo[] readAll(RepoSource.Source src, List<String> paths) {
        ItemInfo[] out = new ItemInfo[paths.size()];
        AtomicInteger next = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        Runnable work = () -> {
            for (int i; (i = next.getAndIncrement()) < out.length; ) {
                try {
                    out[i] = item(new String(src.read(paths.get(i)), StandardCharsets.UTF_8));
                } catch (IOException | RuntimeException e) {
                    if (failed.getAndIncrement() < 5) SkyCosmetics.LOG.debug("Skipping {}: {}", paths.get(i), e.toString());
                }
            }
        };
        int threads = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
        List<Thread> helpers = new ArrayList<>();
        for (int t = 1; t < threads; t++) {
            Thread th = new Thread(work, "SkyCosmetics-repo-" + t);
            th.setDaemon(true);
            th.setPriority(Thread.NORM_PRIORITY - 1);
            th.start();
            helpers.add(th);
        }
        work.run();
        for (Thread th : helpers) {
            try {
                th.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        }
        if (failed.get() > 0) SkyCosmetics.LOG.debug("{} repo item files could not be read", failed.get());
        return out;
    }

    private static ItemInfo item(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String id = str(o, "internalname");
        String display = str(o, "displayname");
        if (id == null || display == null) return null;
        String name = strip(display).replace("[Lvl {LVL}] ", "").trim();
        int color = colorOf(display);

        String nbt = str(o, "nbttag");
        Matcher m = nbt == null ? null : TEXTURE.matcher(nbt);
        if (m == null || !m.find()) return new ItemInfo(id, name, color, null, SkinEntry.Kind.HEAD, false, SkinEntry.Use.HELMET);
        String texture = m.group(1);

        List<String> lore = new ArrayList<>();
        JsonElement le = o.get("lore");
        if (le != null && le.isJsonArray()) {
            for (JsonElement e : le.getAsJsonArray()) if (e.isJsonPrimitive()) lore.add(strip(e.getAsString()));
        }
        boolean skin = isSkin(name, lastLine(lore));
        SkinEntry.Kind kind = id.startsWith("PET_SKIN_") ? SkinEntry.Kind.PET_SKIN
            : skin ? SkinEntry.Kind.SKIN : SkinEntry.Kind.HEAD;
        boolean animatedLore = false;
        if (kind != SkinEntry.Kind.HEAD) {
            for (String l : lore) {
                if (l.toLowerCase(Locale.ROOT).contains("animated")) {
                    animatedLore = true;
                    break;
                }
            }
        }
        return new ItemInfo(id, name, color, texture, kind, animatedLore,
            kind == SkinEntry.Kind.SKIN ? useOf(lore) : SkinEntry.Use.HELMET);
    }

    /**
     * "This skin can be applied to Wither Goggles." Power orb and backpack skins
     * name those; minion, barn and greenhouse skins have no such line at all.
     */
    static SkinEntry.Use useOf(List<String> lore) {
        String text = String.join(" ", lore);
        int at = text.indexOf("can be applied to");
        if (at < 0) return SkinEntry.Use.OTHER;
        int end = text.indexOf('.', at);
        String target = text.substring(at, end < 0 ? text.length() : end);
        return target.contains("Power Orb") ? SkinEntry.Use.ORB
            : target.contains("Backpack") ? SkinEntry.Use.OTHER : SkinEntry.Use.HELMET;
    }

    /** An entry of dyes.json's "animated" section that is a real dye item (DYE_*), not an armour colour cycle. */
    static boolean isAnimatedDye(String id) {
        return id.startsWith("DYE_");
    }

    /** Last non-empty lore line, colour codes already stripped. */
    static String lastLine(List<String> lore) {
        for (int i = lore.size() - 1; i >= 0; i--) {
            String l = lore.get(i).trim();
            if (!l.isEmpty()) return l;
        }
        return "";
    }

    /** "&lt;RARITY&gt; COSMETIC" as the last lore line and a name ending in "Skin". */
    static boolean isSkin(String name, String lastLoreLine) {
        return COSMETIC.matcher(lastLoreLine).matches() && SKIN_NAME.matcher(name.trim()).find();
    }

    // ---------------------------------------------------------- helpers ---

    /** animatedskulls "uuid:base64" frames -> base64 values; null if there are none. */
    static String[] frames(JsonArray tex) {
        if (tex == null || tex.isEmpty()) return null;
        String[] frames = new String[tex.size()];
        for (int i = 0; i < frames.length; i++) {
            String s = tex.get(i).getAsString();
            int colon = s.indexOf(':');
            frames[i] = colon >= 0 ? s.substring(colon + 1) : s;
        }
        return frames;
    }

    /** Per-frame ticks: ticksPerTexture where given (blink skins), else the uniform "ticks". */
    static int[] ticks(JsonObject o, int count) {
        int[] ticks = new int[count];
        JsonArray per = o.get("ticksPerTexture") instanceof JsonArray a ? a : null;
        int base = tick(o.get("ticks"), 1);
        for (int i = 0; i < count; i++) ticks[i] = per != null && i < per.size() ? tick(per.get(i), base) : base;
        return ticks;
    }

    /** A tick count clamped to 1..{@link SkinEntry#MAX_FRAME_TICKS}, or {@code or} if {@code e} is not a number. */
    static int tick(JsonElement e, int or) {
        return e instanceof JsonPrimitive p && p.isNumber() ? Math.clamp(p.getAsLong(), 1, SkinEntry.MAX_FRAME_TICKS) : or;
    }

    private static void addSorted(List<DyeEntry> group, Map<String, DyeEntry> dyes, List<DyeEntry> order) {
        group.sort((x, y) -> x.name.compareToIgnoreCase(y.name));
        for (DyeEntry d : group) {
            dyes.put(d.id, d);
            order.add(d);
        }
        group.clear();
    }

    /** Longest skin id that {@code id} extends with "_SUFFIX": NECRON_DIAMOND_KNIGHT_BLACK -> NECRON_DIAMOND_KNIGHT. */
    static String parentOf(String id, Map<String, SkinEntry> skins) {
        int i = id.length();
        while ((i = id.lastIndexOf('_', i - 1)) > 0) {
            String p = id.substring(0, i);
            SkinEntry e = skins.get(p);
            if (e != null && e.kind != SkinEntry.Kind.HEAD) return p;
        }
        return null;
    }

    private static String text(RepoSource.Source src, String path) throws IOException {
        return new String(src.read(path), StandardCharsets.UTF_8);
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static int hex(String s) {
        return Integer.parseInt(s.startsWith("#") ? s.substring(1) : s, 16) & 0xFFFFFF;
    }

    /** Drops every "§x" colour/format code. */
    static String strip(String s) {
        int i = s.indexOf('§');
        if (i < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        int from = 0;
        for (; i >= 0; i = s.indexOf('§', from)) {
            b.append(s, from, i);
            from = Math.min(i + 2, s.length());
        }
        return b.append(s, from, s.length()).toString();
    }

    /** ARGB of the first colour code in a formatted string; white if none. */
    static int colorOf(String s) {
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.charAt(i) != '§') continue;
            ChatFormatting f = ChatFormatting.getByCode(s.charAt(i + 1));
            if (f != null && f.getColor() != null) return 0xFF000000 | f.getColor();
        }
        return 0xFFFFFFFF;
    }

    /** ARGB of Hypixel's rarity colour from a "RARITY COSMETIC" line, or 0 if unknown. */
    static int rarityColor(String lastLoreLine) {
        String r = lastLoreLine.replace(" COSMETIC", "").trim();
        ChatFormatting f = switch (r) {
            case "COMMON" -> ChatFormatting.WHITE;
            case "UNCOMMON" -> ChatFormatting.GREEN;
            case "RARE" -> ChatFormatting.BLUE;
            case "EPIC" -> ChatFormatting.DARK_PURPLE;
            case "LEGENDARY" -> ChatFormatting.GOLD;
            case "MYTHIC" -> ChatFormatting.LIGHT_PURPLE;
            case "DIVINE" -> ChatFormatting.AQUA;
            case "SPECIAL", "VERY SPECIAL" -> ChatFormatting.RED;
            case "ULTIMATE" -> ChatFormatting.DARK_RED;
            default -> null;
        };
        return f == null || f.getColor() == null ? 0 : 0xFF000000 | f.getColor();
    }
}
