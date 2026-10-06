package io.github.terabold.skycosmetics.deploy;

import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.HeadSwap;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.Textures;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import io.github.terabold.skycosmetics.pet.PetDebug;
import io.github.terabold.skycosmetics.pet.PetTextures;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reskins the power orb you deploy (Radiant, Mana Flux, Overflux, Plasmaflux).
 *
 * A deployed orb is a head Hypixel spawns where you used the item, under a
 * "Radiant 30s" nametag stand. 26.1 mods (SkyblockAddons, Devonian) find it as
 * an invisible armour stand's helmet, but any head {@link HeadSwap#headOf}
 * knows is accepted. Nothing on it names its owner, so ownership comes from
 * your own click: right-clicking an orb whose look has a skin is remembered,
 * and the head that appears within 5 blocks of you in the next 4 s is your
 * orb (clicking again in that time keeps what already loaded). One showing
 * the orb item's own texture wins, then one under an orb nametag ("Mana Flux
 * 30s") that loaded with it, then a stand's helmet or an item display over a
 * mob that happened to load, then the nearest. It is locked by entity id and
 * wears the skin (orb skins are the repo's *_FLUX items) until it despawns or
 * your next orb appears. If it only leaves view (you walked away), the same
 * entity is locked again when it comes back. Other players' orbs are never
 * touched: no click of yours, no lock.
 *
 * The click callbacks only observe and always pass; nothing is sent, cancelled
 * or spawned. With "Show my skins on my pet and power orbs" off nothing runs.
 * While a click is pending each tick looks only at the few entities that
 * loaded since it; renders cost an id compare.
 */
public final class DeployedOrbs {
    private static final long WINDOW_MS = 4_000;
    /** An orb that left view is looked for again this long (orbs last up to a minute). */
    private static final long LOST_MS = 90_000;
    private static final Pattern ORB_TAG = Pattern.compile("^(?:Radiant|Mana Flux|Overflux|Plasmaflux) \\d{1,3}s$");
    /** Rank parts: the orb item's own texture; under an orb nametag; a stand's helmet or an item display. */
    private static final int TEXTURE = 4;
    private static final int TAG = 2;
    private static final int USUAL = 1;
    private static final double FIND_RANGE_SQ = 5 * 5;
    /** Entities loading further than this from the click are never the orb; don't even remember them. */
    private static final double LOAD_RANGE_SQ = 8 * 8;
    private static final int MAX_FRESH = 64;
    private static final Set<String> ORBS = Set.of(
        "RADIANT_POWER_ORB", "MANA_FLUX_POWER_ORB", "OVERFLUX_POWER_ORB", "PLASMAFLUX_POWER_ORB");
    private static final double DEBUG_NEAR_YOU = 4;
    private static final double DEBUG_NEAR_CLICK = 5;

    /** The pending deploy: when, where, which item's look and its head texture; clickAt < 0 when none. */
    private static long clickAt = -1;
    private static Vec3 clickPos;
    private static Cosmetics.Ident clickIdent;
    private static String clickHash;
    private static final List<Entity> FRESH = new ArrayList<>();
    /** The last orb click, with or without a look, kept for "/skycosmetics debug orb". */
    private static long lastClickAt = -1;
    private static Vec3 lastClickPos;
    private static String lastClickHash;

    private static ClientLevel level;
    /** Your orb's entity while it is out of view, so it is locked again when it loads; -1 if none. */
    private static int lostId = -1;
    private static long lostUntil;
    private static int relook;
    private static Cosmetics.Ident ident;
    private static int lookVersion;
    private static SkinEntry skin;
    private static final HeadSwap FRAMES = new HeadSwap();

    private DeployedOrbs() {}

    public static void init() {
        // Fired on the client before the click is sent (and again on the integrated
        // server in singleplayer, which is ignored). Observe only: always PASS.
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClientSide()) observe(player, player.getItemInHand(hand));
            return InteractionResult.PASS;
        });
        // Aiming at a block sends a block click instead; Hypixel deploys on that too.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide()) observe(player, player.getItemInHand(hand));
            return InteractionResult.PASS;
        });
        // Runs inside the server's spawn packet: a throw here would disconnect you.
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            try {
                if (clickAt >= 0 && world == level && FRESH.size() < MAX_FRESH && HeadSwap.canShow(entity)
                    && entity.position().distanceToSqr(clickPos) <= LOAD_RANGE_SQ) {
                    FRESH.add(entity);
                }
            } catch (RuntimeException e) {
                Io.failed("Watching for your power orb", e);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                tick(mc);
            } catch (RuntimeException e) {
                Io.failed("Tracking your power orb", e);
                unlock();
                clearClick();
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
            ClientCommands.literal("skycosmetics").then(ClientCommands.literal("debug").then(
                ClientCommands.literal("orb").executes(ctx -> PetDebug.print(ctx.getSource(), report()))))));
    }

    /** A click callback must never fail the click: a broken item is simply not an orb. */
    private static void observe(Player player, ItemStack stack) {
        try {
            used(player, stack);
        } catch (RuntimeException e) {
            Io.failed("Reading a power orb click", e);
        }
    }

    /**
     * Your own right-click with {@code stack}: remembered when it is a power orb
     * (or another deployable) whose look has a skin. Public so tests can stand
     * in for the click.
     */
    public static void used(Player player, ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        if (!Settings.worldReskin || player != mc.player || mc.level == null || !isOrb(stack)) return;
        String hash = PetTextures.hashOf(stack);
        lastClickAt = Util.getMillis();
        lastClickPos = player.position();
        lastClickHash = hash;
        Cosmetics.Ident id = Cosmetics.identify(stack);
        SkinEntry e = skinFor(id);
        if (e == null) return;
        Textures.preload(e); // the orb is a moment away: start the download now
        if (mc.level != level) {
            level = mc.level;
            unlock();
            clearClick();
        }
        // A second click while the first is pending keeps what loaded since the first.
        if (clickAt < 0) FRESH.clear();
        clickAt = lastClickAt;
        clickPos = lastClickPos;
        clickIdent = id;
        clickHash = hash;
    }

    /**
     * Replacement head for your deployed orb, or null for every other entity (an
     * id compare). {@code slot} is the equipment slot a renderer read, or null
     * for an item model drawn for the entity.
     */
    public static ItemStack head(Entity entity, EquipmentSlot slot, ItemStack stack) {
        if (entity.getId() != FRAMES.id() || !Settings.worldReskin || entity.level() != level) {
            return null;
        }
        return FRAMES.head(skin, entity, slot, stack);
    }

    /** Entity id of the orb being reskinned, or -1. */
    public static int lockedId() {
        return FRAMES.id();
    }

    /** The shape 26.1 mods know an orb has (a stand's helmet), or an item display: preferred over a mob's head. */
    private static boolean usual(Entity e, EquipmentSlot slot) {
        return slot == null || slot == EquipmentSlot.HEAD && e instanceof ArmorStand;
    }

    /** A power orb by SkyBlock id, or any item whose rarity line says DEPLOYABLE. */
    static boolean isOrb(ItemStack s) {
        Cosmetics.Ident id = Cosmetics.identify(s);
        if (id == null) return false;
        if (ORBS.contains(id.type()) || id.type().endsWith("_POWER_ORB")) return true;
        ItemLore lore = s.get(DataComponents.LORE);
        if (lore == null) return false;
        List<Component> lines = lore.lines();
        for (int i = lines.size() - 1; i >= 0; i--) {
            String t = ChatFormatting.stripFormatting(lines.get(i).getString());
            if (t == null || t.isBlank()) continue;
            // The rarity line is the last one; recombobulated items wrap it in obfuscated "a".
            return t.contains(" DEPLOYABLE");
        }
        return false;
    }

    private static void tick(Minecraft mc) {
        if (!Settings.worldReskin) {
            if (level != null) {
                unlock();
                clearClick();
                level = null;
            }
            return;
        }
        if (mc.level != level) {
            level = mc.level;
            unlock();
            clearClick();
            lastClickAt = -1;
            lastClickPos = null;
        }
        if (level == null) return;
        if (FRAMES.id() >= 0) {
            Entity e = level.getEntity(FRAMES.id());
            if (e == null || e.isRemoved()) {
                // Out of view or despawned; ids are never reused, so if it loads again it is still yours.
                lostId = FRAMES.id();
                lostUntil = Util.getMillis() + LOST_MS;
                FRAMES.unlock();
            } else if (Looks.version() != lookVersion) {
                lookVersion = Looks.version();
                skin = skinFor(ident); // look edited while the orb is out (or the repo reloaded)
            }
        } else if (lostId >= 0 && ++relook % 10 == 0) {
            refind();
        }
        if (clickAt < 0) return;
        if (Util.getMillis() - clickAt > WINDOW_MS) {
            clearClick(); // no orb came: the click was something else (cooldown, a menu, a block)
            return;
        }
        List<Vec3> tags = null;
        for (Entity e : FRESH) {
            if (!e.isRemoved() && isOrbTag(e)) {
                if (tags == null) tags = new ArrayList<>(2);
                tags.add(e.position());
            }
        }
        Entity found = null;
        HeadSwap.Head head = null;
        int bestRank = -1;
        double bestD = Double.MAX_VALUE;
        for (Entity e : FRESH) {
            if (e.isRemoved() || e.level() != level) continue;
            HeadSwap.Head h = HeadSwap.headOf(e);
            double d = e.position().distanceToSqr(clickPos);
            if (h == null || d > FIND_RANGE_SQ) continue;
            boolean m = clickHash != null && clickHash.equals(PetTextures.hashOf(h.stack()));
            int rank = (m ? TEXTURE : 0) + (under(e, tags) ? TAG : 0) + (usual(e, h.slot()) ? USUAL : 0);
            if (rank > bestRank || rank == bestRank && d < bestD) {
                found = e;
                head = h;
                bestRank = rank;
                bestD = d;
            }
        }
        if (found == null) return;
        if (found.getId() != FRAMES.id()) {
            ident = clickIdent;
            lookVersion = Looks.version();
            skin = skinFor(ident);
        }
        FRAMES.lock(found, head.slot());
        lostId = -1;
        // The orb item's own texture settles it; otherwise a head showing it may still load in the window.
        if (bestRank >= TEXTURE || clickHash == null) clearClick();
    }

    /** Your orb's entity loaded again after leaving view: wear the skin again. */
    private static void refind() {
        if (Util.getMillis() > lostUntil) {
            unlock();
            return;
        }
        Entity e = level.getEntity(lostId);
        HeadSwap.Head h = e == null || e.isRemoved() ? null : HeadSwap.headOf(e);
        if (h == null) return;
        FRAMES.lock(e, h.slot());
        lostId = -1;
    }

    /** An orb's timer nametag, "Mana Flux 30s". */
    private static boolean isOrbTag(Entity e) {
        Component name = e.getCustomName();
        if (name == null) return false;
        String plain = ChatFormatting.stripFormatting(name.getString());
        return plain != null && plain.length() <= 32 && ORB_TAG.matcher(plain.trim()).matches();
    }

    /** Is {@code e} right under (or wearing) one of the orb nametags? */
    private static boolean under(Entity e, List<Vec3> tags) {
        if (tags == null) return false;
        Vec3 p = e.position();
        for (Vec3 t : tags) {
            double dx = p.x - t.x, dz = p.z - t.z, up = t.y - p.y;
            if (dx * dx + dz * dz <= 1 && up >= -0.5 && up <= 3.5) return true;
        }
        return false;
    }

    private static SkinEntry skinFor(Cosmetics.Ident id) {
        if (id == null) return null;
        Looks.Look look = Cosmetics.lookFor(id, true);
        return look == null ? null : Repo.get().skin(look.skin());
    }

    private static void clearClick() {
        clickAt = -1;
        clickPos = null;
        clickIdent = null;
        clickHash = null;
        FRESH.clear();
    }

    private static void unlock() {
        ident = null;
        skin = null;
        lostId = -1;
        FRAMES.unlock();
    }

    /**
     * "/skycosmetics debug orb": the pending click and the lock, then every entity
     * within 4 blocks of you or 5 of your last orb click with everything it
     * shows, so even an orb the matcher misses shows what Hypixel draws it with.
     */
    public static List<String> report() {
        List<String> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        long now = Util.getMillis();
        out.add(clickAt < 0 ? "Orb: no deploy click pending"
            : String.format(Locale.ROOT, "Orb: deploy click %d ms ago, %d entities loaded since", now - clickAt, FRESH.size()));
        out.add(lastClickAt < 0 ? "Last orb click: none" : String.format(Locale.ROOT,
            "Last orb click: %.1f s ago at %.1f %.1f %.1f, item skin=%s", (now - lastClickAt) / 1000.0,
            lastClickPos.x, lastClickPos.y, lastClickPos.z, lastClickHash == null ? "none" : lastClickHash));
        out.add(FRAMES.id() >= 0 ? "Locked: #" + FRAMES.id() + " " + PetDebug.slotName(FRAMES.slot())
            + " wearing " + (skin == null ? "no skin" : skin.id)
            : lostId >= 0 ? "Locked: nothing; your orb #" + lostId + " is out of view and gets the skin again if it comes back"
            : "Locked: nothing");
        if (mc.player == null || mc.level == null) return out;

        List<Vec3> click = lastClickPos == null ? List.of() : List.of(lastClickPos);
        List<Entity> near = PetDebug.around(mc.player, mc.player.position(), DEBUG_NEAR_YOU, click, DEBUG_NEAR_CLICK);
        Set<String> set = lastClickHash == null ? Set.of() : Set.of(lastClickHash);
        out.add(String.format(Locale.ROOT, "%d entities within %d blocks of you or %d of your last orb click:",
            near.size(), (int) DEBUG_NEAR_YOU, (int) DEBUG_NEAR_CLICK));
        for (Entity e : near) {
            StringBuilder b = PetDebug.describe(e, mc.player.position(), set);
            if (FRESH.contains(e)) b.append(" (loaded after the click)");
            if (e.getId() == FRAMES.id()) b.append(" LOCKED");
            out.add(b.toString());
        }
        return out;
    }
}
