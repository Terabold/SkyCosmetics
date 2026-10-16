package io.github.terabold.skycosmetics.slots;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.mixin.CustomDataAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What the slot features need to know about a stack: its rarity, whether it is recombobulated, a SkyBlock item or a
 * pet. Read once per stack and kept on it ({@link Holder}); read again only when the stack's lore or SkyBlock data
 * or the original name/tooltip style is replaced, so drawing an unchanged menu never parses anything.
 *
 * @param lore the lore component the facts were read from (by identity)
 * @param data the custom data component the facts were read from (by identity)
 * @param name the original custom name component (by identity)
 * @param tooltipStyle the server's tooltip style (by identity)
 * @param rarity null when no item rarity can be determined (ordinary menu buttons, vanilla items)
 * @param loadoutColor a saved setup button's name color, used only in the Loadouts selection area
 * @param helmet the rarity line names a helmet ("LEGENDARY HELMET", "MYTHIC DUNGEON HELMET")
 */
public record ItemFacts(Object lore, Object data, Object name, Object tooltipStyle, Rarity rarity,
                        boolean recombobulated, boolean skyblock, boolean pet, Rarity loadoutColor, boolean helmet) {
    /** Mixed into ItemStack: the facts last read for it. */
    public interface Holder {
        ItemFacts skycosmetics$facts();

        void skycosmetics$facts(ItemFacts facts);
    }

    private static final String TIER_BOOST = "PET_ITEM_TIER_BOOST";
    /** "[Lvl 100] Name", and the level range shops show: "[Lvl 1➡100] Name". */
    private static final Pattern PET_NAME = Pattern.compile("^(?:⭐ )?\\[Lvl [0-9,]{1,5}(?: ?[➡→~-] ?[0-9,]{1,5})?] (?:\\[[0-9]+[✦⚔]] )?([\\w '-]+?)(?: ✦)?$");
    /** Stacks read since start, for tests: drawing the same menu again must read none. */
    private static long reads;

    /** The stack's facts; read now only if a relevant component changed since the last call. */
    public static ItemFacts of(ItemStack s) {
        ItemLore lore = s.get(DataComponents.LORE);
        CustomData data = s.get(DataComponents.CUSTOM_DATA);
        Component name = s.getCustomName();
        Identifier tooltipStyle = s.get(DataComponents.TOOLTIP_STYLE);
        Holder h = (Holder) (Object) s;
        ItemFacts f = h.skycosmetics$facts();
        if (f != null && f.lore == lore && f.data == data && f.name == name && f.tooltipStyle == tooltipStyle) return f;
        f = read(lore, data, name, tooltipStyle, s.is(Items.PLAYER_HEAD));
        h.skycosmetics$facts(f);
        return f;
    }

    public static long reads() {
        return reads;
    }

    static ItemFacts read(ItemLore lore, CustomData data, Component name, Identifier tooltipStyle, boolean head) {
        reads++;
        CompoundTag tag = null;
        String id = "";
        try {
            tag = data == null ? null : ((CustomDataAccessor) (Object) data).skycosmetics$tag();
            if (tag != null) id = tag.getStringOr("id", "");
        } catch (RuntimeException e) {
            Io.failed("Reading an item's SkyBlock data", e);
        }
        boolean pet = id.equals("PET");
        Line line = lore == null ? null : rarityLine(lore.lines());
        Rarity rarity = line == null ? null : line.rarity;
        Rarity namedPet = head ? nameRarity(name, true) : null;
        pet |= namedPet != null;
        if (pet) {
            Rarity tier = petTier(tag);
            if (tier != null) rarity = tier;
        }
        Rarity styled = tooltipStyle != null && tooltipStyle.getNamespace().equals("hypixel_skyblock")
            ? Rarity.named(tooltipStyle.getPath()) : null;
        if (rarity == null) rarity = styled != null ? styled : namedPet;
        Rarity loadoutColor = null;
        if (head && lore != null && lore.lines().stream().anyMatch(c -> {
            String raw = c.getString();
            String text = raw.length() <= 128 ? ChatFormatting.stripFormatting(raw) : null;
            return "Left-click to equip!".equals(text) || "Right-click to edit".equals(text);
        })) loadoutColor = nameRarity(name, false);
        boolean recomb = line != null && line.recombobulated || tag != null && tag.getIntOr("rarity_upgrades", 0) > 0;
        boolean helmet = lore != null && helmetLine(lore.lines());
        return new ItemFacts(lore, data, name, tooltipStyle, rarity, recomb, !id.isEmpty() || styled != null || namedPet != null, pet, loadoutColor, helmet);
    }

    /** Whether the rarity line (the lowest line that reads like one) ends in HELMET. */
    private static boolean helmetLine(List<Component> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String raw = lines.get(i).getString();
            if (parse(raw) == null) continue;
            String t = ChatFormatting.stripFormatting(raw);
            if (t == null) return false;
            String[] w = t.trim().split(" +");
            // "a LEGENDARY HELMET a" when recombobulated: the word before the obfuscated letter.
            int last = w.length - 1;
            if (last > 0 && w[last].length() == 1) last--;
            return w[last].equals("HELMET");
        }
        return false;
    }

    /** Pet shop heads have no rarity footer. Read the pet name's color, not the gray level prefix. */
    private static Rarity nameRarity(Component name, boolean pet) {
        if (name == null || name.getString().length() > 256) return null;
        StringBuilder text = new StringBuilder();
        int[] colors = new int[128];
        net.minecraft.util.StringDecomposer.iterateFormatted(name, net.minecraft.network.chat.Style.EMPTY, (index, style, cp) -> {
            int size = Character.charCount(cp);
            if (text.length() + size > colors.length) return false;
            int color = style.getColor() == null ? -1 : style.getColor().getValue();
            for (int i = 0; i < size; i++) colors[text.length() + i] = color;
            text.appendCodePoint(cp);
            return true;
        });
        if (text.isEmpty() || text.length() == colors.length) return null;
        int at = 0;
        if (pet) {
            var match = PET_NAME.matcher(text);
            if (!match.matches()) return null;
            at = match.start(1);
        }
        int color = colors[at];
        for (Rarity rarity : FORMATTED_RARITIES) {
            if (pet && rarity.ordinal() > Rarity.MYTHIC.ordinal()) break;
            if (rarity.hypixel == color) return rarity;
        }
        return null;
    }

    /** A rarity line, and whether Hypixel wrapped it in the obfuscated letters of a recombobulated item. */
    public record Line(Rarity rarity, boolean recombobulated) {}

    /**
     * Hypixel's rarity line: "LEGENDARY DUNGEON HELMET", "VERY SPECIAL", "SHINY MYTHIC", or "a EPIC BOW a" when
     * recombobulated. It is the last line of the item's own lore; menus such as the auction house add lines under
     * it, so the lowest line that reads like one wins. A line with a lowercase letter (other than the obfuscated
     * ones) is never a rarity line.
     */
    public static Line rarityLine(List<Component> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            Line l = parse(lines.get(i).getString());
            if (l != null) return l;
        }
        // Reward buttons can put a bracket inside a sentence. Require Hypixel's bold rarity color,
        // and only fall back after looking for an item's normal rarity line.
        for (int i = lines.size() - 1; i >= 0; i--) {
            Rarity rarity = formattedRarity(lines.get(i));
            if (rarity != null) return new Line(rarity, false);
        }
        return null;
    }

    private static Rarity formattedRarity(Component line) {
        if (line.getString().length() > 512) return null;
        StringBuilder run = new StringBuilder();
        int[] color = {-1};
        Rarity[] found = {null};
        net.minecraft.util.StringDecomposer.iterateFormatted(line, net.minecraft.network.chat.Style.EMPTY, (index, style, cp) -> {
            int next = style.isBold() && style.getColor() != null ? style.getColor().getValue() : -1;
            if (next != color[0]) {
                found[0] = rarityInRun(run, color[0]);
                if (found[0] != null) return false;
                run.setLength(0); color[0] = next;
            }
            if (next != -1) run.appendCodePoint(cp);
            return true;
        });
        return found[0] != null ? found[0] : rarityInRun(run, color[0]);
    }

    private static final Rarity[] FORMATTED_RARITIES = Rarity.values();
    private static Rarity rarityInRun(StringBuilder run, int color) {
        if (run.isEmpty() || color < 0) return null;
        String text = run.toString();
        // Check VERY SPECIAL before SPECIAL, which shares its color.
        for (int i = FORMATTED_RARITIES.length - 1; i >= 0; i--) {
            Rarity rarity = FORMATTED_RARITIES[i];
            if (rarity.hypixel != color) continue;
            String name = rarity.name().replace('_', ' ');
            int from = 0, at;
            while ((at = text.indexOf(name, from)) >= 0) {
                int end = at + name.length();
                if ((at == 0 || !word(text.charAt(at - 1))) && (end == text.length() || !word(text.charAt(end)))) return rarity;
                from = end;
            }
        }
        return null;
    }

    private static boolean word(char c) { return Character.isLetterOrDigit(c) || c == '_'; }

    static Line parse(String raw) {
        String t = ChatFormatting.stripFormatting(raw);
        if (t == null) return null;
        t = t.trim();
        if (t.length() < 3 || t.length() > 64) return null;
        String[] w = t.split(" +");
        int from = 0, to = w.length;
        boolean recomb = false;
        // Recombobulated: one obfuscated letter on each side.
        if (to >= 2 && w[0].length() == 1 && !Character.isDigit(w[0].charAt(0))) {
            from = 1;
            recomb = true;
            if (w[to - 1].length() == 1) to--;
        }
        if (from < to && w[from].equals("SHINY")) from++; // "SHINY LEGENDARY", "a SHINY MYTHIC a"
        if (from >= to) return null;
        Rarity r;
        int next;
        if (w[from].equals("VERY") && from + 1 < to && w[from + 1].equals("SPECIAL")) {
            r = Rarity.VERY_SPECIAL;
            next = from + 2;
        } else {
            r = w[from].equals("VERY_SPECIAL") ? null : Rarity.named(w[from]);
            next = from + 1;
            if (r != null && !w[from].equals(r.name()) && !w[from].equals("SUPREME")) r = null; // exact, uppercase
        }
        if (r == null) return null;
        for (int i = next; i < to; i++) {
            for (int c = 0; c < w[i].length(); c++) if (Character.isLowerCase(w[i].charAt(c))) return null;
        }
        return new Line(r, recomb);
    }

    /** A pet's tier from its petInfo, one up with a Tier Boost held. */
    private static Rarity petTier(CompoundTag tag) {
        if (tag == null) return null;
        try {
            Tag info = tag.get("petInfo");
            String tier = null, held = null;
            if (info instanceof CompoundTag c) {
                tier = c.getStringOr("tier", null);
                held = c.getStringOr("heldItem", null);
            } else if (info != null) {
                String json = info.asString().orElse(null);
                if (json == null) return null;
                JsonElement e = JsonParser.parseString(json);
                if (!(e instanceof JsonObject o)) return null;
                tier = str(o, "tier");
                held = str(o, "heldItem");
            }
            Rarity r = Rarity.named(tier);
            return r != null && TIER_BOOST.equals(held) ? r.boosted() : r;
        } catch (RuntimeException e) {
            return null; // not JSON: the lore's rarity line still counts
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
