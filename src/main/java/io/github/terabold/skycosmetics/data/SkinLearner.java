package io.github.terabold.skycosmetics.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Learns skins the NEU repo does not have yet from what Hypixel already shows
 * the client: any skin item drawn anywhere gives its id, name, rarity and still
 * texture, and clicking a "preview" item hands over to {@link PreviewRecorder}
 * for the animation. Results go to captured.json and straight into the catalog.
 *
 * Read only: nothing here sends a packet, clicks, or changes a slot. Any server
 * can show such items, so only Mojang skin textures under SkyBlock-shaped ids
 * are learned, and {@link Repo} caps how many.
 */
public final class SkinLearner {
    /** Shaped like a SkyBlock id; anything else is not a skin's id. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_;:.-]{1,128}");
    private static final Pattern SKIN_URL = Pattern.compile("https?://textures\\.minecraft\\.net/texture/[0-9a-fA-F]{32,128}");
    /** Real texture values are a few hundred characters. */
    private static final int MAX_TEXTURE = 2048;
    private static final int MAX_NAME = 64;

    /** SkyBlock ids already looked at and found not to be a new skin. Bounded; cleared when full. */
    private static final Set<String> CHECKED = ConcurrentHashMap.newKeySet();
    private static long ticks;

    private SkinLearner() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            ticks++;
            try { PreviewRecorder.tick(mc); }
            catch (RuntimeException e) { Io.failed("Updating a skin preview recording", e); }
        });
        TimingLearner.init();
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> acs)) return;
            ScreenMouseEvents.beforeMouseClick(screen).register((s, click) -> {
                try {
                    Slot slot = ((AbstractContainerScreenAccessor) acs).skycosmetics$hoveredSlot();
                    if (slot != null && slot.hasItem()) PreviewRecorder.clicked(slot.getItem(), click.button());
                } catch (RuntimeException e) { Io.failed("Starting a skin preview recording", e); }
            });
        });
    }

    /** Client ticks since start: the clock preview frames are timed with. */
    static long ticks() {
        return ticks;
    }

    /** Incoming menu entries may never be rendered, especially behind scrolling lists from other mods. */
    public static void observe(ItemStack stack) {
        if (!io.github.terabold.skycosmetics.Settings.learnSkins || stack.isEmpty()
            || stack.get(DataComponents.PROFILE) == null) return;
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return;
        var id = io.github.terabold.skycosmetics.Cosmetics.identify(data);
        if (id == null) return;
        seen(stack, id.type());
        variant(stack, ((io.github.terabold.skycosmetics.mixin.CustomDataAccessor)(Object)data).skycosmetics$tag(), id.type());
    }

    /**
     * Called by Cosmetics once per stack it identifies. Two map lookups for any
     * item the repo knows; the lore is only read for an id it has never seen.
     */
    public static void seen(ItemStack s, String type) {
        if (!io.github.terabold.skycosmetics.Settings.learnSkins) return;
        Catalog c = Repo.get();
        SkinEntry known = c.skins.get(type);
        if (known != null && known.swappable > 0 && swatch(s, known)) return;
        if (c.items == 0 || type.startsWith("PET:") || known != null && (!known.learned || known.swappable > 0)
            || c.itemNames.containsKey(type)
            || CHECKED.contains(type)) return;
        GameProfile profile = profile(s);
        String texture = profile == null ? null : texture(profile);
        if (texture == null) return; // not a head, or not resolved yet: look again next time
        if (CHECKED.size() > 4096) CHECKED.clear();
        if (!validId(type) || !mojangTexture(texture)) return;

        List<String> lore = lore(s);
        String name = clip(RepoParser.strip(io.github.terabold.skycosmetics.Cosmetics.originalName(s).getString()).trim());
        String rarity = cosmeticLine(lore);
        boolean named = RepoParser.SKIN_NAME.matcher(name).find();
        boolean pet = type.startsWith("PET_SKIN_");
        // A shop's colour swatch shares its skin's id but not its look; the recorder keys those.
        boolean swatch = !named && lore.stream().anyMatch(PreviewRecorder::isPreview);
        if (swatch) return; // the skin item with this id may be drawn after its preview swatches
        CHECKED.add(type);
        if (!(named && rarity != null || pet && (named || rarity != null))) return;

        int color = rarity != null ? RepoParser.rarityColor(rarity) : 0;
        if (color == 0) color = nameColor(io.github.terabold.skycosmetics.Cosmetics.originalName(s));
        SkinEntry.Kind kind = pet ? SkinEntry.Kind.PET_SKIN : SkinEntry.Kind.SKIN;
        Repo.learn(type, new Captured.Still(name, color, kind, texture, System.currentTimeMillis(),
                pet ? SkinEntry.Use.HELMET : RepoParser.useOf(lore), RepoParser.swappable(lore)),
            Component.literal("Learned a skin the repo does not have yet: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(name).withColor(color & 0xFFFFFF)));
    }

    /**
     * A variant of a swappable skin the repo lacks, from an item wearing it. Hypixel keeps the picked variant's
     * number next to the skin ({@code skin: "KITSUNE_TIKI_MASK", favorite_kitsune: 3}; a pet keeps it in its
     * petInfo) and draws the item with that variant's texture, so a texture the skin's family does not have yet is
     * learned as {@code KITSUNE_TIKI_MASK_VARIANT_4}, listed with the skin. Only for skins whose lore says they are
     * swappable. Called with {@link #seen}; a few map lookups unless the item wears such a skin.
     */
    public static void variant(ItemStack s, CompoundTag tag, String type) {
        if (!io.github.terabold.skycosmetics.Settings.learnSkins) return;
        String skin;
        int index;
        if (type.startsWith("PET:")) {
            JsonObject info = petInfo(tag);
            String petSkin = info == null ? null : io.github.terabold.skycosmetics.Cosmetics.str(info, "skin");
            if (petSkin == null) return;
            skin = "PET_SKIN_" + petSkin;
            index = info.get("extraData") instanceof JsonObject extra ? favorite(extra) : -1;
            if (index < 0) index = favorite(info);
        } else {
            skin = tag.getStringOr("skin", "");
            if (skin.isEmpty()) return;
            index = favorite(tag);
        }
        if (index < 0 || index > 999) return;
        Catalog c = Repo.get();
        SkinEntry root = c.skins.get(skin);
        if (root == null || root.swappable <= index || root.parent != null) return;
        String key = skin + "_VARIANT_" + (index + 1);
        if (c.skins.containsKey(key) || CHECKED.contains(key)) return;
        GameProfile profile = profile(s);
        String texture = profile == null ? null : texture(profile);
        if (texture == null) return; // not resolved yet: look again next time
        if (CHECKED.size() > 4096) CHECKED.clear();
        if (!validId(key) || !mojangTexture(texture)) return;
        String hash = textureHash(texture);
        if (hash == null || c.familyHashes(root).contains(hash)) return; // the default, or a variant the repo has
        CHECKED.add(key); // a default or unresolved texture must not block a later actual variant
        String name = root.name + " (Captured style)";
        Repo.learn(key, new Captured.Still(name, root.color, SkinEntry.Kind.VARIANT, texture, System.currentTimeMillis()),
            Component.literal("Learned a skin variant the repo does not have yet: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(name).withColor(root.color & 0xFFFFFF)));
    }

    /** Named preview choices carry the family id, so their real names can be learned without clicking them. */
    static boolean swatch(ItemStack s, SkinEntry root) {
        String name = clip(RepoParser.strip(io.github.terabold.skycosmetics.Cosmetics.originalName(s).getString()).trim());
        if (name.isEmpty() || name.equalsIgnoreCase(root.name) || name.equals("FIRE SALE!")
            || !lore(s).stream().anyMatch(SkinLearner::choice)) return false;
        String key = PreviewRecorder.key(root.id, name, Repo.get());
        if (key.equals(root.id) || !validId(key) || CHECKED.contains(key) || Repo.get().skins.containsKey(key)) return true;
        GameProfile profile = profile(s);
        String value = profile == null ? null : texture(profile);
        if (value == null || !mojangTexture(value)) return true;
        if (CHECKED.size() > 4096) CHECKED.clear();
        CHECKED.add(key);
        Repo.learn(key, new Captured.Still(root.name + " (" + name + ")", root.color, SkinEntry.Kind.VARIANT,
            value, System.currentTimeMillis()), null);
        return true;
    }

    private static boolean choice(String last) {
        return PreviewRecorder.isPreview(last) || last.equals("Click to select!") || last.equals("Click to equip!");
    }

    /** Stable identity of a known skin on an item, for passive recording of missing animation frames. */
    static String wornSkin(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = ((io.github.terabold.skycosmetics.mixin.CustomDataAccessor)(Object)data).skycosmetics$tag();
        String skin = tag.getStringOr("skin", "");
        int index = favorite(tag);
        if (skin.isEmpty()) {
            JsonObject info = petInfo(tag);
            String petSkin = info == null ? null : io.github.terabold.skycosmetics.Cosmetics.str(info, "skin");
            if (petSkin == null) return null;
            skin = "PET_SKIN_" + petSkin;
            index = info.get("extraData") instanceof JsonObject extra ? favorite(extra) : favorite(info);
        }
        if (skin.length() > 128) return null;
        SkinEntry root = Repo.get().skin(skin);
        if (root == null || root.parent != null || (root.kind != SkinEntry.Kind.SKIN && root.kind != SkinEntry.Kind.PET_SKIN)) return null;
        if (root.swappable > 0) {
            if (index < 0 || index >= root.swappable || index > 999) return null;
            skin += "_VARIANT_" + (index + 1);
        }
        SkinEntry existing = Repo.get().skin(skin);
        return existing != null && existing.animated() ? null : skin;
    }

    /** The one {@code favorite_...} number on an item (Hypixel's picked variant), or -1 without exactly one. */
    static int favorite(CompoundTag tag) {
        int found = -1;
        for (String k : tag.keySet()) {
            if (!k.startsWith("favorite_")) continue;
            int v = tag.getIntOr(k, -1);
            if (v < 0 || found >= 0) return -1;
            found = v;
        }
        return found;
    }

    private static int favorite(JsonObject o) {
        int found = -1;
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (!e.getKey().startsWith("favorite_")) continue;
            if (!(e.getValue() instanceof JsonPrimitive p) || !p.isNumber() || found >= 0) return -1;
            found = p.getAsInt();
        }
        return found;
    }

    private static JsonObject petInfo(CompoundTag tag) {
        String json = tag.getStringOr("petInfo", "");
        if (json.isEmpty() || json.length() > 8192 || !json.contains("favorite_")) return null; // most pets: no parse
        try {
            return JsonParser.parseString(json) instanceof JsonObject o ? o : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ helpers ---

    /**
     * The hash at the end of a head texture's skin URL ({@code textures.minecraft.net/texture/<hash>}), lower case;
     * null if there is none. Raw values differ between the repo and the server (timestamps, profile ids); the hash
     * names the picture.
     */
    public static String textureHash(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_TEXTURE) return null;
        try {
            String json = new String(Base64.getMimeDecoder().decode(value), StandardCharsets.UTF_8);
            int i = json.indexOf("/texture/");
            if (i < 0) return null;
            int start = i + 9, end = start;
            while (end < json.length() && Character.digit(json.charAt(end), 16) >= 0) end++;
            return end - start >= 32 ? json.substring(start, end).toLowerCase(java.util.Locale.ROOT) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A SkyBlock-shaped id that a learned skin may be saved under. */
    static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /**
     * True for a head texture value whose skin is a Mojang texture hash
     * ({@code textures.minecraft.net/texture/<hex>}), at most 2048 characters:
     * the only kind of texture that is learned or read back from captured.json.
     */
    public static boolean mojangTexture(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_TEXTURE) return false;
        try {
            String json = new String(Base64.getMimeDecoder().decode(value), StandardCharsets.UTF_8);
            if (!(JsonParser.parseString(json) instanceof JsonObject o && o.get("textures") instanceof JsonObject t
                && t.get("SKIN") instanceof JsonObject skin)) return false;
            JsonElement url = skin.get("url");
            return url != null && url.isJsonPrimitive() && SKIN_URL.matcher(url.getAsString()).matches();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A name as learned: at most 64 characters. */
    static String clip(String name) {
        return name.length() <= MAX_NAME ? name : name.substring(0, MAX_NAME);
    }

    static GameProfile profile(ItemStack s) {
        ResolvableProfile p = s.get(DataComponents.PROFILE);
        return p == null ? null : p.partialProfile();
    }

    /** The base64 "textures" property value, or null. */
    static String texture(GameProfile profile) {
        for (Property p : profile.properties().get("textures")) {
            if (p.value() != null && !p.value().isEmpty()) return p.value();
        }
        return null;
    }

    /** Lore lines as plain text. */
    static List<String> lore(ItemStack s) {
        ItemLore l = s.get(DataComponents.LORE);
        if (l == null || l.lines().isEmpty()) return List.of();
        List<String> out = new ArrayList<>(l.lines().size());
        for (Component line : l.lines()) out.add(RepoParser.strip(line.getString()).trim());
        return out;
    }

    /** The "EPIC COSMETIC" line, searched from the bottom: menus add price and click hints below it. */
    static String cosmeticLine(List<String> lore) {
        for (int i = lore.size() - 1; i >= 0; i--) {
            if (RepoParser.COSMETIC.matcher(lore.get(i)).matches()) return lore.get(i);
        }
        return null;
    }

    /** ARGB of the first coloured text in a component; white if none. */
    static int nameColor(Component name) {
        return name.visit((style, text) -> text.isBlank() || style.getColor() == null ? Optional.<Integer>empty()
            : Optional.of(0xFF000000 | style.getColor().getValue()), Style.EMPTY).orElse(0xFFFFFFFF);
    }
}
