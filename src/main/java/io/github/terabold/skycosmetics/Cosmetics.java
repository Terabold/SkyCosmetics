package io.github.terabold.skycosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.terabold.skycosmetics.data.Catalog;
import io.github.terabold.skycosmetics.data.DyeEntry;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import io.github.terabold.skycosmetics.data.SkinLearner;
import io.github.terabold.skycosmetics.deploy.DeployedOrbs;
import io.github.terabold.skycosmetics.items.Mine;
import io.github.terabold.skycosmetics.mixin.CustomDataAccessor;
import io.github.terabold.skycosmetics.pet.WorldPet;
import io.github.terabold.skycosmetics.render.Glints;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.equipment.Equippable;

/**
 * The whole feature in one place: given a stack about to be drawn, return the
 * stack that should be drawn instead.
 *
 * Nothing on the real item is ever modified. Every renderer (GUI slots, hand,
 * dropped items, head and armour layers on the player model) is handed a copy,
 * so tooltips, other mods, and the server all keep seeing Hypixel's item. That
 * single choke point is why menus and the player model can never disagree.
 *
 * Cost: items without SkyBlock data return on the first null check. SkyBlock
 * items are identified once per stack object (cached on the stack itself) and
 * a copy is only rebuilt when the animation frame or dye colour changes.
 *
 * Item data comes from any server, so every hook catches its own failures: the
 * item is then drawn and named as sent, and nothing reaches Minecraft's
 * rendering or ticking.
 */
public final class Cosmetics {
    private static final Identifier HEAD_MODEL = Identifier.withDefaultNamespace("player_head");
    /** SkyBlock ids and UUIDs are short; a longer one from an odd server is ignored, so keys stay small. */
    private static final int MAX_ID = 128;

    /**
     * Marks a copy we produced, so a second pass through another hook returns it
     * as-is; it also carries the look's glint colour/speed to the renderers.
     */
    private record Output(Glints.Style glint) {}

    private static final Output PLAIN = new Output(null);

    private Cosmetics() {}

    public record Ident(String uuid, String type) {}

    private static final class Entry {
        CustomData data;
        int version;
        /** Whether "every item of this type" looks were used, and the {@link Mine#version} that was decided at. */
        boolean typeLooks;
        int mineVersion;
        /** The item's UUID, and whether its type has a look at all (if not, whose item it is never matters). */
        String uuid;
        boolean typeLook;
        SkinEntry skin;
        DyeEntry dye;
        /** Plain leather piece to draw a dyed non-leather item as (iron boots...), else null. */
        Item leather;
        Boolean glint;
        /** Glint colour/speed, or null for Minecraft's own. */
        Glints.Style glintStyle;
        Component name;
        int piece;
        ItemStack out;
        int outFrame = -2;
        int outRgb = -2;
    }

    // ---------------------------------------------------------- hooks ---

    /** Items drawn anywhere: GUI, hand, item frames, dropped items. */
    public static ItemStack forRender(ItemStack s, Object owner) {
        // Your pet's or orb's head may be an item display's item or held in a hand; the lock checks which.
        return owner instanceof Entity e ? forEntity(e, null, s) : apply(s, null);
    }

    /** Equipment read by the head and armour layers of an entity's model ({@code slot} null: an item model it draws). */
    public static ItemStack forEntity(Entity e, EquipmentSlot slot, ItemStack s) {
        try {
            ItemStack pet = WorldPet.head(e, slot, s);
            if (pet != null) return pet;
            ItemStack orb = DeployedOrbs.head(e, slot, s);
            if (orb != null) return orb;
        } catch (RuntimeException ex) {
            Io.failed("Reskinning a pet or power orb", ex);
        }
        // Another player's items never wear your "every item" looks; anything else wears them if it is yours.
        return apply(s, e instanceof Player && e != Minecraft.getInstance().player ? Boolean.FALSE : null);
    }

    /** Glint colour/speed of a stack about to be drawn (a copy made by {@link #apply}), or null. */
    public static Glints.Style glintOf(ItemStack drawn) {
        return drawn != null && ((StackCache) (Object) drawn).skycosmetics$entry() instanceof Output o ? o.glint() : null;
    }

    /** {@code copy} was copied from {@code from} by a renderer: if that was our copy, so is this one. */
    public static ItemStack copied(ItemStack from, ItemStack copy) {
        if (((StackCache) (Object) from).skycosmetics$entry() instanceof Output o) ((StackCache) (Object) copy).skycosmetics$entry(o);
        return copy;
    }

    /** The look that applies to this stack for you (UUID look merged over its type look if the item is yours), or null. */
    public static Looks.Look lookOf(ItemStack s) {
        Ident id = identify(s);
        return id == null ? null : lookFor(id, Mine.allows(s, id.uuid()));
    }

    // ----------------------------------------------------------- core ---

    /** The stack as drawn with its look; {@code typeLooks} true or false forces "every item" looks on or off. */
    public static ItemStack apply(ItemStack s, boolean typeLooks) {
        return apply(s, Boolean.valueOf(typeLooks));
    }

    /** {@code typeLooks} null: "every item" looks only if the item is yours ({@link Mine}). */
    private static ItemStack apply(ItemStack s, Boolean typeLooks) {
        try {
            return restyle(s, typeLooks);
        } catch (RuntimeException ex) {
            Io.failed("Drawing an item with its look", ex);
            plain(s);
            return s;
        }
    }

    private static ItemStack restyle(ItemStack s, Boolean typeLooks) {
        if (s == null || s.isEmpty()) return s;
        CustomData data = s.get(DataComponents.CUSTOM_DATA);
        if (data == null) return s;

        StackCache cache = (StackCache) (Object) s;
        Object raw = cache.skycosmetics$entry();
        if (raw instanceof Output) return s;
        Entry e = fresh(s, data, (Entry) raw, typeLooks);
        if (e != raw) cache.skycosmetics$entry(e);
        if (e.skin == null && e.dye == null && e.glint == null && e.glintStyle == null) return s;

        long tick = Util.getMillis() / 50;

        int frame = -1;
        if (e.skin != null) {
            Textures.keepWarm(e.skin);
            int want = e.skin.frameAt(tick);
            if (Textures.ready(e.skin.textures[want])) {
                frame = want;
            } else if (e.outFrame >= 0 && e.outFrame < e.skin.textures.length
                       && Textures.ready(e.skin.textures[e.outFrame])) {
                frame = e.outFrame; // hold the last good frame rather than flash Steve
            }
        }
        int rgb = e.dye != null ? e.dye.rgbAt(tick, e.piece) : -1;
        if (frame < 0 && rgb < 0 && e.glint == null && e.glintStyle == null) return s; // skin still downloading, nothing else to change

        if (e.out != null && e.outFrame == frame && e.outRgb == rgb) return e.out;

        ItemStack out;
        if (frame >= 0) {
            out = s.is(Items.PLAYER_HEAD) ? s.copy() : s.transmuteCopy(Items.PLAYER_HEAD);
            out.set(DataComponents.PROFILE, Textures.profile(e.skin.textures[frame]));
            // Hypixel sometimes points item_model elsewhere; a head must render as a head.
            out.set(DataComponents.ITEM_MODEL, HEAD_MODEL);
        } else if (rgb >= 0 && e.leather != null) {
            // Only leather takes a tint. Hypixel makes some armour from iron or gives it
            // its own model (Helianthus boots and chestplate), so draw it as plain leather.
            out = s.is(e.leather) ? s.copy() : s.transmuteCopy(e.leather);
            out.set(DataComponents.ITEM_MODEL, e.leather.components().get(DataComponents.ITEM_MODEL));
            out.set(DataComponents.EQUIPPABLE, e.leather.components().get(DataComponents.EQUIPPABLE));
        } else {
            out = s.copy();
        }
        if (rgb >= 0) out.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb));
        if (e.glint != null) out.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, e.glint);
        ((StackCache) (Object) out).skycosmetics$entry(e.glintStyle == null ? PLAIN : new Output(e.glintStyle));

        e.out = out;
        e.outFrame = frame;
        e.outRgb = rgb;
        return out;
    }

    /**
     * The cached entry if it still fits: same data, same looks and, when the item's type has a look, the same
     * answer to whose item it is (asked again only after {@link Mine#version} changes). Else a new one.
     */
    private static Entry fresh(ItemStack s, CustomData data, Entry e, Boolean typeLooks) {
        int version = Looks.version();
        if (e == null || e.data != data || e.version != version) return resolve(s, data, version, typeLooks);
        if (!e.typeLook) return e;
        boolean want;
        if (typeLooks != null) {
            want = typeLooks;
        } else if (e.mineVersion == Mine.version()) {
            want = e.typeLooks;
        } else {
            want = Mine.allows(s, e.uuid);
            e.mineVersion = Mine.version();
        }
        return want == e.typeLooks ? e : resolve(s, data, version, want);
    }

    /** The look for this stack; if anything about it fails, an empty entry (drawn as sent) that the caller caches. */
    private static Entry resolve(ItemStack s, CustomData data, int version, Boolean force) {
        Entry e = empty(data, version);
        try {
            Ident id = identify(data);
            if (id == null) return e;
            SkinLearner.seen(s, id.type());
            e.uuid = id.uuid();
            e.typeLook = Looks.byType(id.type()) != null;
            e.mineVersion = Mine.version();
            e.typeLooks = e.typeLook && (force != null ? force : Mine.allows(s, e.uuid));
            Looks.Look look = lookFor(id, e.typeLooks);
            if (look == null) return e;

            Catalog c = Repo.get();
            e.skin = c.skin(look.skin());
            e.dye = c.dye(look.dye());
            e.leather = e.dye != null ? leatherFor(s) : null;
            e.glint = "on".equals(look.glint()) ? Boolean.TRUE : "off".equals(look.glint()) ? Boolean.FALSE : null;
            e.glintStyle = e.glint == Boolean.FALSE ? null : Glints.style(look.glintColor(), look.glintSpeed(), look.glintStrength());
            e.name = look.name() != null ? Names.parse(look.name()) : null;
            e.piece = pieceIndex(s);
            if (e.skin != null) Textures.preload(e.skin);
            return e;
        } catch (RuntimeException ex) {
            Io.failed("Finding an item's look", ex);
            return empty(data, version); // nothing half filled in
        }
    }

    private static Entry empty(CustomData data, int version) {
        Entry e = new Entry();
        e.data = data;
        e.version = version;
        return e;
    }

    /** After a failure: the stack is drawn as sent until its data or the looks change, not retried every frame. */
    private static void plain(ItemStack s) {
        try {
            StackCache cache = (StackCache) (Object) s;
            if (s != null && !(cache.skycosmetics$entry() instanceof Output)) {
                cache.skycosmetics$entry(empty(s.get(DataComponents.CUSTOM_DATA), Looks.version()));
            }
        } catch (RuntimeException ignored) {
            // nothing cached: the next frame tries again, and Io.failed keeps the log quiet
        }
    }

    public static Looks.Look lookFor(Ident id, boolean typeLooks) {
        Looks.Look own = Looks.byUuid(id.uuid());
        Looks.Look type = typeLooks ? Looks.byType(id.type()) : null;
        if (own == null) return type;
        if (type == null) return own;
        return new Looks.Look(own.skin() != null ? own.skin() : type.skin(),
                              own.dye() != null ? own.dye() : type.dye(),
                              own.name() != null ? own.name() : type.name(),
                              own.glint() != null ? own.glint() : type.glint(),
                              own.glintColor() != null ? own.glintColor() : type.glintColor(),
                              own.glintSpeed() != null ? own.glintSpeed() : type.glintSpeed(),
                              own.glintStrength() != null ? own.glintStrength() : type.glintStrength(), own.label());
    }

    /**
     * Hypixel's name for this stack, never the custom one. Same as vanilla
     * {@code getHoverName}, read straight from the item so no mod's hook on it
     * (Skyblocker's custom names...) changes the answer.
     */
    public static Component originalName(ItemStack s) {
        Component custom = s.getCustomName();
        return custom != null ? custom : s.getItemName();
    }

    /**
     * Your custom name for this stack, or null. {@code getStyledHoverName}
     * (tooltips, held-item name) asks for it; {@code getHoverName} never does,
     * so other mods keep reading Hypixel's name. An "every item" name only on
     * your own items, decided the same way as when it is drawn.
     */
    public static Component customName(ItemStack s) {
        try {
            if (s == null || s.isEmpty()) return null;
            CustomData data = s.get(DataComponents.CUSTOM_DATA);
            if (data == null) return null;
            StackCache cache = (StackCache) (Object) s;
            Object raw = cache.skycosmetics$entry();
            if (raw instanceof Output) return null;
            Entry e = fresh(s, data, (Entry) raw, null);
            if (e != raw) cache.skycosmetics$entry(e);
            return e.name;
        } catch (RuntimeException ex) {
            Io.failed("Finding an item's custom name", ex);
            return null;
        }
    }

    /** The leather piece for an armour item's slot; null for heads and non-armour. */
    private static Item leatherFor(ItemStack s) {
        if (s.is(Items.PLAYER_HEAD)) return null;
        Equippable eq = s.get(DataComponents.EQUIPPABLE);
        if (eq == null) return null;
        return switch (eq.slot()) {
            case HEAD -> Items.LEATHER_HELMET;
            case CHEST -> Items.LEATHER_CHESTPLATE;
            case LEGS -> Items.LEATHER_LEGGINGS;
            case FEET -> Items.LEATHER_BOOTS;
            default -> null;
        };
    }

    /**
     * Boots 0, legs 1, chest 2, helmet 3: how far along the animated-dye cycle
     * this piece runs. Taken from the item's own equippable slot, not where it
     * is worn, and 0 for anything that is not armour (as SkyOcean does).
     */
    private static int pieceIndex(ItemStack s) {
        Equippable eq = s.get(DataComponents.EQUIPPABLE);
        if (eq == null) return 0;
        return switch (eq.slot()) {
            case FEET -> 0;
            case LEGS -> 1;
            case CHEST -> 2;
            default -> 3;
        };
    }

    // ------------------------------------------------------- identity ---

    public static Ident identify(ItemStack s) {
        CustomData data = s == null ? null : s.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : identify(data);
    }

    /**
     * SkyBlock id and UUID. Pets are typed "PET:<TYPE>" and keyed by the pet's own UUID.
     * Null for anything else, including data not shaped like Hypixel's.
     */
    public static Ident identify(CustomData data) {
        try {
            CompoundTag t = ((CustomDataAccessor) (Object) data).skycosmetics$tag();
            String id = t.getStringOr("id", "");
            if (id.isEmpty() || id.length() > MAX_ID) return null;
            String uuid = t.getStringOr("uuid", "");
            String type = id;
            if (id.equals("PET")) {
                JsonObject info = petInfo(t.get("petInfo"));
                String petType = str(info, "type");
                if (petType != null && petType.length() <= MAX_ID) type = "PET:" + petType;
                String petUuid = str(info, "uuid");
                if (petUuid == null) petUuid = str(info, "uniqueId");
                if (petUuid != null) uuid = petUuid;
            }
            return new Ident(uuid.isEmpty() || uuid.length() > MAX_ID ? null : uuid, type);
        } catch (RuntimeException ex) {
            Io.failed("Reading an item's SkyBlock id", ex);
            return null;
        }
    }

    /** A non-empty string field, else null: a server may send null, a number, an object or an array. */
    public static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        if (e == null || !e.isJsonPrimitive()) return null;
        String v = e.getAsString();
        return v.isEmpty() ? null : v;
    }

    private static JsonObject petInfo(Tag tag) {
        if (tag == null) return null;
        try {
            String json = tag.asString().orElse(null);
            if (json != null) return JsonParser.parseString(json).getAsJsonObject();
            if (tag instanceof CompoundTag ct) {
                JsonObject o = new JsonObject();
                ct.getString("type").ifPresent(v -> o.addProperty("type", v));
                ct.getString("uuid").ifPresent(v -> o.addProperty("uuid", v));
                ct.getString("uniqueId").ifPresent(v -> o.addProperty("uniqueId", v));
                return o;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }
}
