package io.github.terabold.skycosmetics.pet;

import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.HeadSwap;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.Textures;
import io.github.terabold.skycosmetics.data.Catalog;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reskins your own summoned pet in the world.
 *
 * On Hypixel a pet is a head floating under a "[Lv100] Hedgehog" nametag
 * stand. On 1.8 the head is an invisible armour stand's helmet; on 26.1 the
 * debug output shows an invisible marker stand holding it in its main
 * hand (Pig, Rabbit), or a plain stand's helmet beside an invisible baby
 * zombie (Armadillo). So a head is any skin-profile stack in any slot of any
 * mob, stand or NPC, or an item display's item ({@link HeadSwap#headOf}), and
 * only the slot it was found in is swapped. The server animates skins by resending the
 * head. Nothing names the owner, so every head nearby is scored: its texture
 * is one your summoned pet can show (its item, its repo heads, any frame or
 * variant of its skin), and/or it sits under a nametag with your pet's name
 * and level. The nametag alone is enough when Hypixel's world texture differs
 * from the item's. The best score wins, the nearest to you among equals, and
 * is locked by entity id. The lock is kept while the entity exists, so frames
 * missing from the repo don't lose it; only a strictly better match takes it
 * over.
 *
 * Matching runs on the client tick, only while a pet with a skin look is out
 * and "Show my skins on my pet and power orbs" is on: every 2 ticks for 3 s
 * after a summon or world change (both your pet and a lookalike may be near,
 * and the head can arrive after the stand), then every 10 ticks, and every 40
 * once nothing has turned up for 5 s more (Hypixel's "hide pets", or the pet
 * is far off). The render hooks are an id compare for every other entity.
 */
public final class WorldPet {
    static final double FIND_RANGE = 10;
    /** A pet nametag further than this from you is someone else's pet. */
    static final double TAG_RANGE = 8;
    /** How far the head may sit from its nametag stand: sideways, and below/above it. */
    private static final double TAG_SIDE_SQ = 1.5 * 1.5;
    private static final double TAG_BELOW = 3.0;
    private static final double TAG_ABOVE = 1.0;
    private static final double KEEP_RANGE_SQ = 32 * 32;
    private static final int SETTLE_TICKS = 60;
    private static final int SETTLE_EVERY = 2;
    private static final int EVERY = 10;
    /** After this many passes without a head to lock, look every {@link #IDLE_EVERY} ticks instead. */
    private static final int IDLE_AFTER = 10;
    private static final int IDLE_EVERY = 40;

    /** Score parts: the head's texture is in the set; under a nametag at the known level; at a higher level. */
    static final int TEXTURE = 4;
    static final int TAG = 2;
    static final int TAG_LATER = 1;

    /**
     * "[Lv100] Hedgehog ✦" once colours are stripped and trimmed; pets only level up, so a higher level may
     * still be yours. At most 4 digits, so an odd server nametag can never overflow the level parse, and no
     * trailing {@code \s*}: after the lazy name it made a long run of spaces take quadratic time.
     */
    static final Pattern NAMETAG = Pattern.compile("^\\[Lvl? ?(\\d{1,4})] (?:\\[\\d+[✦⚔]] )?([\\w '-]+?)(?: ✦)?$");

    /** Best score the locked entity has shown; a rival must beat it to take the lock. */
    private static int lockedScore;
    private static ClientLevel level;
    private static int settle;
    private static int every;
    private static int misses;
    private static int seenChanges = Integer.MIN_VALUE;
    private static Catalog setFor;
    private static Set<String> textures = Set.of();

    private static int lookVersion = -1;
    private static SkinEntry skin;
    private static final HeadSwap FRAMES = new HeadSwap();

    private WorldPet() {}

    /** A matching pet nametag: where it floats, and whether its level is exactly the one we know. */
    record Tag(Vec3 pos, boolean exact) {}

    /**
     * Called for every equipment read by an entity renderer ({@code slot}) and
     * every item model drawn for an entity ({@code slot} null). Return a
     * replacement stack if {@code entity} is the local player's pet and this is
     * its head, otherwise null.
     */
    public static ItemStack head(Entity entity, EquipmentSlot slot, ItemStack stack) {
        if (entity.getId() != FRAMES.id() || !Settings.worldReskin || entity.level() != level) {
            return null;
        }
        return FRAMES.head(skin, entity, slot, stack);
    }

    /** Entity id of the head being reskinned, or -1. */
    public static int lockedId() {
        return FRAMES.id();
    }

    /** The slot the locked head is in (null: an item display's item). */
    static EquipmentSlot lockedSlot() {
        return FRAMES.slot();
    }

    static int lockedScore() {
        return lockedScore;
    }

    static SkinEntry skin() {
        return skin;
    }

    static void tick(Minecraft mc) {
        if (!Settings.worldReskin) {
            if (level != null) {
                unlock();
                level = null; // turned back on: settle again
            }
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            unlock();
            level = null;
            return;
        }
        if (mc.level != level) {
            unlock();
            level = mc.level;
            settle = SETTLE_TICKS;
            misses = 0;
        }

        Pet pet = PetTracker.current();
        int changes = PetTracker.changes();
        if (changes != seenChanges) {
            seenChanges = changes;
            setFor = null;
            lookVersion = -1;
            unlock();
            settle = SETTLE_TICKS;
            misses = 0;
        }
        if (Looks.version() != lookVersion) {
            lookVersion = Looks.version();
            skin = skinFor(pet);
            if (skin != null) Textures.preload(skin);
        }
        if (skin == null) {
            unlock();
            return;
        }
        // A repo reload also bumps Looks.version, so the skin above is already current.
        Catalog c = Repo.get();
        if (setFor != c) {
            setFor = c;
            textures = PetTextures.of(pet, c);
        }

        if (FRAMES.id() >= 0) {
            Entity e = level.getEntity(FRAMES.id());
            if (e == null || e.isRemoved() || e.distanceToSqr(player) > KEEP_RANGE_SQ) {
                unlock(); // despawned or left behind (warps respawn the pet): look again from scratch
                settle = SETTLE_TICKS;
                misses = 0;
            }
        }
        boolean settling = settle > 0;
        if (settling) {
            if (settle-- % SETTLE_EVERY != 0) return;
        } else if (++every % (misses >= IDLE_AFTER ? IDLE_EVERY : EVERY) != 0) {
            return;
        }
        pick(player, pet, settle > 0);
        if (FRAMES.id() >= 0) misses = 0;
        else if (!settling && misses < IDLE_AFTER) misses++;
    }

    /**
     * One matching pass. While settling the best head simply wins; afterwards
     * the locked one is kept unless something scores strictly higher than it
     * ever did, so a lookalike can't steal the lock while your pet shows a
     * frame the repo doesn't have.
     */
    private static void pick(LocalPlayer player, Pet pet, boolean settling) {
        List<Entity> near = nearby(player);
        List<Tag> tags = tags(near, pet, player);
        Entity best = null;
        HeadSwap.Head bestHead = null;
        int bestScore = 0;
        double bestD = Double.MAX_VALUE;
        int lockedNow = 0;
        for (Entity e : near) {
            HeadSwap.Head h = HeadSwap.headOf(e);
            if (h == null) continue;
            int s = score(e, h.stack(), textures, tags);
            if (e.getId() == FRAMES.id()) {
                lockedNow = s;
                FRAMES.lock(e, h.slot()); // Hypixel may move the head to another slot
            }
            if (s == 0) continue;
            double d = e.distanceToSqr(player);
            if (s > bestScore || s == bestScore && d < bestD) {
                best = e;
                bestHead = h;
                bestScore = s;
                bestD = d;
            }
        }
        if (FRAMES.id() >= 0 && !settling) {
            lockedScore = Math.max(lockedScore, lockedNow);
            if (bestScore <= lockedScore) return;
        }
        if (best == null) return;
        FRAMES.lock(best, bestHead.slot());
        lockedScore = bestScore;
    }

    /** Every entity around you but you: Hypixel may put a pet's head or nametag on any of them. */
    static List<Entity> nearby(LocalPlayer player) {
        return player.level().getEntities(player, player.getBoundingBox().inflate(FIND_RANGE));
    }

    /** Nametags within {@link #TAG_RANGE} of you that name {@code pet}. */
    static List<Tag> tags(List<Entity> near, Pet pet, LocalPlayer player) {
        List<Tag> out = new ArrayList<>();
        if (pet == null || pet.name == null) return out;
        for (Entity e : near) {
            if (e.distanceToSqr(player) > TAG_RANGE * TAG_RANGE) continue;
            Matcher m = nametag(e);
            if (m == null || !pet.name.equalsIgnoreCase(m.group(2))) continue;
            int lv = Pet.level(m.group(1));
            if (pet.level <= 0 || lv == pet.level) out.add(new Tag(e.position(), true));
            else if (lv > pet.level) out.add(new Tag(e.position(), false));
        }
        return out;
    }

    /** The pet-nametag match for this entity's custom name, or null. */
    static Matcher nametag(Entity e) {
        Component name = e.getCustomName();
        if (name == null) return null;
        String plain = ChatFormatting.stripFormatting(name.getString());
        if (plain == null || plain.length() > Pet.MAX_TEXT || plain.indexOf('[') < 0) return null;
        Matcher m = NAMETAG.matcher(plain.trim());
        return m.matches() ? m : null;
    }

    /** How strongly this head looks like your pet's; 0 = not at all. */
    static int score(Entity e, ItemStack head, Set<String> textures, List<Tag> tags) {
        int s = textures.contains(PetTextures.hash(Pet.texture(head))) ? TEXTURE : 0;
        int tag = 0;
        Vec3 p = e.position();
        for (Tag t : tags) {
            double dx = p.x - t.pos.x, dz = p.z - t.pos.z, dy = p.y - t.pos.y;
            if (dx * dx + dz * dz > TAG_SIDE_SQ || dy < -TAG_BELOW || dy > TAG_ABOVE) continue;
            tag = Math.max(tag, t.exact ? TAG : TAG_LATER);
        }
        return s + tag;
    }

    /** The skin the summoned pet's look asks for (its uuid look over its type look), or null. */
    static SkinEntry skinFor(Pet pet) {
        if (pet == null || pet.type == null) return null;
        Looks.Look look = Cosmetics.lookFor(pet.ident(), true);
        return look == null ? null : Repo.get().skin(look.skin());
    }

    private static void unlock() {
        lockedScore = 0;
        FRAMES.unlock();
    }
}
