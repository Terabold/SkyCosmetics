package io.github.terabold.skycosmetics.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.github.terabold.skycosmetics.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
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
            PreviewRecorder.tick(mc);
        });
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> acs)) return;
            ScreenMouseEvents.beforeMouseClick(screen).register((s, click) -> {
                Slot slot = ((AbstractContainerScreenAccessor) acs).skycosmetics$hoveredSlot();
                if (slot != null && slot.hasItem()) PreviewRecorder.clicked(slot.getItem(), click.button());
            });
        });
    }

    /** Client ticks since start: the clock preview frames are timed with. */
    static long ticks() {
        return ticks;
    }

    /**
     * Called by Cosmetics once per stack it identifies. Two map lookups for any
     * item the repo knows; the lore is only read for an id it has never seen.
     */
    public static void seen(ItemStack s, String type) {
        if (!io.github.terabold.skycosmetics.Settings.learnSkins) return;
        Catalog c = Repo.get();
        if (c.items == 0 || type.startsWith("PET:") || c.skins.containsKey(type) || c.itemNames.containsKey(type)
            || CHECKED.contains(type)) return;
        GameProfile profile = profile(s);
        String texture = profile == null ? null : texture(profile);
        if (texture == null) return; // not a head, or not resolved yet: look again next time
        if (CHECKED.size() > 4096) CHECKED.clear();
        CHECKED.add(type);
        if (!validId(type) || !mojangTexture(texture)) return;

        List<String> lore = lore(s);
        String name = clip(RepoParser.strip(io.github.terabold.skycosmetics.Cosmetics.originalName(s).getString()).trim());
        String rarity = cosmeticLine(lore);
        boolean named = RepoParser.SKIN_NAME.matcher(name).find();
        boolean pet = type.startsWith("PET_SKIN_");
        // A shop's colour swatch shares its skin's id but not its look; the recorder keys those.
        boolean swatch = !named && PreviewRecorder.isPreview(RepoParser.lastLine(lore));
        if (swatch || !(named && rarity != null || pet && (named || rarity != null))) return;

        int color = rarity != null ? RepoParser.rarityColor(rarity) : 0;
        if (color == 0) color = nameColor(io.github.terabold.skycosmetics.Cosmetics.originalName(s));
        SkinEntry.Kind kind = pet ? SkinEntry.Kind.PET_SKIN : SkinEntry.Kind.SKIN;
        Repo.learn(type, new Captured.Still(name, color, kind, texture, System.currentTimeMillis()),
            Component.literal("Learned a skin the repo does not have yet: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(name).withColor(color & 0xFFFFFF)));
    }

    // ------------------------------------------------------------ helpers ---

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
