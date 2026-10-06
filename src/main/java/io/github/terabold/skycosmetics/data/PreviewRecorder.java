package io.github.terabold.skycosmetics.data;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import io.github.terabold.skycosmetics.Cosmetics;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Records an animated skin from Hypixel's preview armour stand, the way
 * Firmament's SkinPreviews does, but timed per frame: each head change is
 * stamped with the client tick it arrived on, so the result has real
 * per-frame durations instead of one wall-clock average that also counts the
 * stand's spawn delay.
 *
 * Starts when you click an item whose last lore line is "Right-click to
 * preview!" / "Click to preview!" (Elizabeth, fire sales), follows the stand's
 * equipment packets (read only, via ClientPacketListenerMixin) and stops once
 * the frame cycle has repeated twice, the head stops changing, or after 60 s.
 * Main thread only.
 */
public final class PreviewRecorder {
    /** Elizabeth's preview stand in the Hub. */
    private static final Vec3 STAND = new Vec3(-1.0, 72.0, -101.25);
    /** Elsewhere, the closest marker stand to get a head after the click is taken instead. */
    private static final double NEAR_SQ = 10 * 10;
    private static final int APPEAR_TICKS = 20 * 10;
    private static final int STALE_TICKS = 20 * 8;
    private static final int MAX_TICKS = 20 * 60;
    private static final int MAX_FRAMES = 400;

    private static String key;
    private static String label;
    private static int color;
    private static boolean announce;
    private static ClientLevel level;
    private static long startTick;
    private static long lastTick;
    private static int target = -1;
    private static boolean targetAtStand;
    /** Head textures in arrival order ("uuid:base64" for the file, base64 alone for comparing). */
    private static final List<String> FRAMES = new ArrayList<>();
    private static final List<String> VALUES = new ArrayList<>();
    private static final long[] AT = new long[MAX_FRAMES];

    private PreviewRecorder() {}

    static boolean isPreview(String lastLoreLine) {
        return lastLoreLine.equals("Right-click to preview!") || lastLoreLine.equals("Click to preview!");
    }

    // ------------------------------------------------------------- events ---

    static void clicked(ItemStack stack, int button) {
        if (!io.github.terabold.skycosmetics.Settings.learnSkins) return;
        String last = RepoParser.lastLine(SkinLearner.lore(stack));
        if (!last.equals("Click to preview!") && !(last.equals("Right-click to preview!") && button == 1)) return;
        Cosmetics.Ident id = Cosmetics.identify(stack);
        if (id == null || id.type().startsWith("PET:")) return;
        Catalog c = Repo.get();
        String type = id.type();
        String name = SkinLearner.clip(RepoParser.strip(io.github.terabold.skycosmetics.Cosmetics.originalName(stack).getString()).trim());
        String k = key(type, name, c);
        if (!SkinLearner.validId(k)) return;
        SkinEntry known = c.skins.get(k);
        if (known != null && known.animated()) return; // the repo (or an earlier recording) has it

        String base = c.itemNames.getOrDefault(type, c.skins.containsKey(type) ? c.skins.get(type).name : Catalog.title(type));
        reset();
        key = k;
        label = k.equals(type) ? base : base + " (" + name + ")";
        String rarity = SkinLearner.cosmeticLine(SkinLearner.lore(stack));
        int rc = rarity != null ? RepoParser.rarityColor(rarity) : 0;
        color = rc != 0 ? rc : known != null ? known.color : c.skins.containsKey(type) ? c.skins.get(type).color : 0;
        announce = known == null || known.missingFrames;
        level = Minecraft.getInstance().level;
        startTick = lastTick = SkinLearner.ticks();
        if (announce) say(Component.literal("Watching the preview of " + label + " to learn its animation...")
            .withStyle(ChatFormatting.GRAY));
    }

    /**
     * Fire Sale items are named "FIRE SALE!" and carry the skin's own id; any
     * other name is a colour variant of that id (SENTINEL_WARDEN + "Red").
     */
    static String key(String type, String name, Catalog c) {
        if (name.equals("FIRE SALE!") || name.equalsIgnoreCase(c.itemNames.getOrDefault(type, ""))
            || !c.itemNames.containsKey(type) && RepoParser.SKIN_NAME.matcher(name).find()) return type;
        String v = name.toUpperCase(Locale.ROOT).trim().replaceAll("\\s+", "_").replaceAll("[^A-Z0-9_]", "");
        return v.isEmpty() ? type : type + "_" + v;
    }

    /** From ClientPacketListenerMixin after the packet was applied. Returns at once unless recording. */
    public static void onEquipment(ClientboundSetEquipmentPacket packet) {
        if (!io.github.terabold.skycosmetics.Settings.learnSkins) return;
        if (key == null) return;
        int id = packet.getEntity();
        if (id != target && target != -1 && targetAtStand) return;
        ItemStack head = null;
        for (Pair<EquipmentSlot, ItemStack> p : packet.getSlots()) {
            if (p.getFirst() == EquipmentSlot.HEAD) head = p.getSecond();
        }
        if (head == null) return;
        if (id != target) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return;
            Entity e = mc.level.getEntity(id);
            if (!(e instanceof ArmorStand stand)) return;
            boolean atStand = e.position().distanceToSqr(STAND) < 1.0;
            if (!atStand && (target != -1 || !stand.isMarker() || e.distanceToSqr(mc.player) > NEAR_SQ)) return;
            // The first candidate, or the real preview spot taking over from a nearby stand.
            target = id;
            targetAtStand = atStand;
            FRAMES.clear();
            VALUES.clear();
        }
        GameProfile profile = SkinLearner.profile(head);
        String value = profile == null ? null : SkinLearner.texture(profile);
        if (value == null || !VALUES.isEmpty() && VALUES.getLast().equals(value) || !SkinLearner.mojangTexture(value)) return;
        long now = SkinLearner.ticks();
        AT[VALUES.size()] = now;
        VALUES.add(value);
        FRAMES.add(profile.id() + ":" + value);
        lastTick = now;
        Fit f = fit(AT, VALUES, true);
        if (f != null || VALUES.size() >= MAX_FRAMES) finish(f != null ? f : fit(AT, VALUES, false));
    }

    static void tick(Minecraft mc) {
        if (key == null) return;
        long now = SkinLearner.ticks();
        if (mc.level != level || VALUES.isEmpty() && now - startTick > APPEAR_TICKS) {
            reset(); // left the world, or no preview showed up
            return;
        }
        if (VALUES.isEmpty() || now - lastTick < STALE_TICKS && now - startTick < MAX_TICKS) return;
        Fit f = fit(AT, VALUES, false);
        // A head that never changes is a still variant; only trusted from the real preview spot.
        if (f == null && VALUES.size() <= 2 && targetAtStand) f = new Fit(1, 1, null, VALUES.size() - 1);
        if (f == null && announce) say(Component.literal("Could not find a repeating cycle in the preview of "
            + label + " (" + VALUES.size() + " frames)").withStyle(ChatFormatting.YELLOW));
        finish(f);
    }

    private static void finish(Fit f) {
        if (f == null) {
            reset();
            return;
        }
        String[] textures = FRAMES.subList(f.start, f.start + f.frames).toArray(String[]::new);
        Captured.Anim anim = new Captured.Anim(label, color, f.ticks, f.ticksPerTexture, textures, System.currentTimeMillis());
        String timing = f.frames == 1 ? "a still"
            : f.frames + " frames, " + (f.ticksPerTexture == null ? f.ticks + " ticks each" : "ticks " + Arrays.toString(f.ticksPerTexture));
        Component news = Component.literal("Learned " + (f.frames == 1 ? "the look" : "the animation") + " of ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(label).withColor((color != 0 ? color : 0xFFFF55FF) & 0xFFFFFF))
            .append(Component.literal(" (" + timing + ")").withStyle(ChatFormatting.GRAY));
        Repo.learn(key, anim, news);
        reset();
    }

    private static void reset() {
        key = null;
        target = -1;
        targetAtStand = false;
        FRAMES.clear();
        VALUES.clear();
        level = null; // do not keep a left world alive
    }

    private static void say(Component msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(io.github.terabold.skycosmetics.SkyCosmetics.prefix().append(msg));
    }

    // ------------------------------------------------------------ fitting ---

    /**
     * A recording reduced to one cycle: {@code frames} textures starting at
     * index {@code start}, with a uniform {@code ticks} or, when frames differ,
     * {@code ticksPerTexture} (the animatedskulls.json format).
     */
    public record Fit(int frames, int ticks, int[] ticksPerTexture, int start) {}

    /**
     * Finds the shortest period in {@code values} and times it from {@code at}.
     * The first texture is skipped: it is whatever the stand spawned with, held
     * for an unknown part of its frame. {@code complete} wants the cycle seen
     * twice plus one frame; otherwise one cycle plus one frame will do. A single
     * duration may be off by a tick (packets land between client ticks), so the
     * uniform tick count comes from whole cycles, which do not accumulate error.
     * Null when no cycle fits. Pure, for the gametest too.
     */
    public static Fit fit(long[] at, List<String> values, boolean complete) {
        int n = values.size();
        int m = n - 1;
        for (int p = 2; m >= (complete ? 2 * p + 1 : p + 2); p++) {
            boolean periodic = true;
            for (int i = 1 + p; i < n && periodic; i++) periodic = values.get(i).equals(values.get(i - p));
            if (!periodic) continue;

            long[] sum = new long[p];
            int[] count = new int[p];
            for (int j = 1; j + 1 < n; j++) {
                sum[(j - 1) % p] += at[j + 1] - at[j];
                count[(j - 1) % p]++;
            }
            int cycles = (n - 2) / p;
            int ticks = Math.max(1, Math.round((float) (at[1 + cycles * p] - at[1]) / (cycles * p)));
            int[] per = new int[p];
            boolean uniform = true;
            for (int k = 0; k < p; k++) {
                per[k] = Math.max(1, Math.round((float) sum[k] / Math.max(1, count[k])));
                uniform &= Math.abs(per[k] - ticks) <= Math.max(1, ticks / 5);
            }
            if (uniform) return new Fit(p, ticks, null, 1);
            int[] sorted = per.clone();
            Arrays.sort(sorted);
            return new Fit(p, sorted[p / 2], per, 1);
        }
        return null;
    }
}
