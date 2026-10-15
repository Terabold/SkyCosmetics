package io.github.terabold.skycosmetics.data;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import io.github.terabold.skycosmetics.Cosmetics;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Records manually selected cosmetic previews, including Taylor's ID-less
 * variant choices, but timed per frame: each texture change is
 * stamped with the client tick it arrived on, so the result has real
 * per-frame durations instead of one wall-clock average that also counts the
 * stand's spawn delay.
 *
 * Starts when you click an item with "Right-click to preview!" / "Click to preview!", follows matching
 * equipment packets (read only, via ClientPacketListenerMixin) and stops once
 * three complete consistent cycles, the head stops changing, or after 60 s.
 * Main thread only.
 */
public final class PreviewRecorder {
    /** A manually selected preview must first show its menu thumbnail's texture, near the player. */
    private static final double NEAR_SQ = 16 * 16;
    private static final int CONTEXT_TICKS = 20 * 120, MENU_OPEN_TICKS = 20 * 10;
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
    private static long firstArrival, lastArrival;
    private static Fit pendingCycle;
    private static int target = -1;
    private static String anchor;
    private static String result = "Preview: waiting for a manual preview click";
    /** Parent skin retained only across the manually opened preview chooser, not arbitrary later menus. */
    private static String family;
    private static ClientLevel familyLevel;
    private static long familyAt;
    private static int familyMenu = -1, pendingMenu = -1;
    /** Head textures in arrival order ("uuid:base64" for the file, canonical hash for comparing). */
    private static final List<String> FRAMES = new ArrayList<>();
    private static final List<String> VALUES = new ArrayList<>();
    private static final long[] AT = new long[MAX_FRAMES];

    private PreviewRecorder() {}

    /** Client-thread status only; no texture parsing or disk work. */
    public static String status() {
        return key == null ? result
            : "Preview: " + label + " (" + VALUES.size() + " frame changes)";
    }

    static boolean isPreview(String lastLoreLine) {
        return lastLoreLine.equals("Right-click to preview!") || lastLoreLine.equals("Click to preview!");
    }

    // ------------------------------------------------------------- events ---

    static void clicked(ItemStack stack, int button) {
        if (!io.github.terabold.skycosmetics.Settings.learnSkins) return;
        List<String> lore = SkinLearner.lore(stack);
        if (!lore.contains("Click to preview!") && !(lore.contains("Right-click to preview!") && button == 1)) return;
        Minecraft mc = Minecraft.getInstance();
        Cosmetics.Ident id = Cosmetics.identify(stack);
        Catalog c = Repo.get();
        SkinEntry identified = id == null ? null : c.skin(id.type());
        SkinEntry root = id == null ? context(mc, currentMenu(mc)) : identified == null ? null : c.root(identified);
        if (root == null || root.kind != SkinEntry.Kind.SKIN && root.kind != SkinEntry.Kind.PET_SKIN) {
            result = "Preview: could not identify the parent skin";
            reset(); return;
        }
        String type = root.id;
        family = type; familyLevel = mc.level; familyAt = SkinLearner.ticks();
        if (id != null) familyMenu = currentMenu(mc);
        String name = SkinLearner.clip(RepoParser.strip(io.github.terabold.skycosmetics.Cosmetics.originalName(stack).getString()).trim());
        String k = identified != null && identified.parent != null ? identified.id : key(type, name, c);
        if (!SkinLearner.validId(k)) return;
        SkinEntry known = c.skins.get(k);
        String base = c.itemNames.getOrDefault(type, c.skins.containsKey(type) ? c.skins.get(type).name : Catalog.title(type));
        String next = k.equals(type) ? base : base + " (" + name + ")";
        // Another preview while one is being watched: that one stops; say so, or it just goes quiet.
        if (key != null && announce && !k.equals(key)) say(Component.literal("Stopped watching " + label + ": another preview was opened")
            .withStyle(ChatFormatting.GRAY));
        if (known != null && known.animated()) {
            result = "Preview: animation already recorded for " + name;
            say(Component.literal(next + ": its animation is already known, nothing to learn").withStyle(ChatFormatting.GRAY));
            reset(); return;
        }

        reset();
        key = k;
        label = next;
        String rarity = SkinLearner.cosmeticLine(SkinLearner.lore(stack));
        int rc = rarity != null ? RepoParser.rarityColor(rarity) : 0;
        color = rc != 0 ? rc : known != null ? known.color : c.skins.containsKey(type) ? c.skins.get(type).color : 0;
        announce = known == null || known.missingFrames;
        level = Minecraft.getInstance().level;
        GameProfile profile = SkinLearner.profile(stack);
        String thumbnail = profile == null ? null : SkinLearner.texture(profile);
        anchor = SkinLearner.mojangTexture(thumbnail) ? SkinLearner.textureHash(thumbnail) : null;
        if (anchor == null) { result = "Preview: selected item has no usable thumbnail"; reset(); return; }
        startTick = lastTick = SkinLearner.ticks();
        if (announce) say(Component.literal("Watching the preview of " + label + " to learn its animation (up to a minute)...")
            .withStyle(ChatFormatting.GRAY));
    }

    private static int currentMenu(Minecraft mc) {
        return mc.player == null ? -1 : mc.player.containerMenu.containerId;
    }

    private static SkinEntry context(Minecraft mc, int menu) {
        if (family == null || familyLevel != mc.level || SkinLearner.ticks() - familyAt > CONTEXT_TICKS || menu < 0 || menu != familyMenu) return null;
        return Repo.get().skin(family);
    }

    public static void onOpenScreen(ClientboundOpenScreenPacket packet) {
        familyMenu = -1; pendingMenu = -1;
        if (family != null && familyLevel == Minecraft.getInstance().level && SkinLearner.ticks() - familyAt <= MENU_OPEN_TICKS)
            pendingMenu = packet.getContainerId();
        else clearContext();
    }

    /** The chooser's bare heads belong to the skin just previewed, only after validating this menu's choices. */
    public static void onContent(ClientboundContainerSetContentPacket packet) {
        if (!io.github.terabold.skycosmetics.Settings.learnSkins || family == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (packet.containerId() == pendingMenu) {
            SkinEntry root = Repo.get().skin(family);
            int choices = 0;
            if (root == null || mc.level != familyLevel) { clearContext(); return; }
            for (ItemStack stack : packet.items().subList(0, Math.min(512, packet.items().size()))) {
                if (SkinLearner.profile(stack) == null || !SkinLearner.lore(stack).stream().anyMatch(PreviewRecorder::isPreview)) continue;
                if (!SkinLearner.mojangTexture(SkinLearner.texture(SkinLearner.profile(stack)))) continue;
                var id = Cosmetics.identify(stack);
                if (!belongsTo(id, root)) { clearContext(); return; }
                choices++;
            }
            // Do not carry a shop family into unrelated inventories or another cosmetic's chooser.
            if (choices < 2) {
                result = "Preview: could not recognize the variant chooser for " + root.name;
                clearContext(); return;
            }
            familyMenu = pendingMenu; pendingMenu = -1;
        }
        SkinEntry root = context(mc, packet.containerId());
        if (root == null) return;
        for (ItemStack stack : packet.items().subList(0, Math.min(512, packet.items().size()))) observeChoice(stack, root);
    }

    public static void onSlot(ClientboundContainerSetSlotPacket packet) {
        if (!io.github.terabold.skycosmetics.Settings.learnSkins) return;
        SkinEntry root = context(Minecraft.getInstance(), packet.getContainerId());
        if (root != null) observeChoice(packet.getItem(), root);
    }

    private static void observeChoice(ItemStack stack, SkinEntry root) {
        var id = Cosmetics.identify(stack);
        if (belongsTo(id, root)) SkinLearner.swatch(stack, root);
    }

    private static boolean belongsTo(Cosmetics.Ident id, SkinEntry root) {
        if (id == null || id.type().equals(root.id)) return true;
        SkinEntry entry = Repo.get().skin(id.type());
        return entry != null && Repo.get().root(entry).id.equals(root.id);
    }

    private static void clearContext() {
        family = null; familyLevel = null; familyMenu = pendingMenu = -1;
    }

    /**
     * Fire Sale items are named "FIRE SALE!" and carry the skin's own id; any
     * other name is a colour variant of that id (SENTINEL_WARDEN + "Red").
     */
    static String key(String type, String name, Catalog c) {
        SkinEntry root = c.skins.get(type);
        if (name.equals("FIRE SALE!") || name.equalsIgnoreCase(c.itemNames.getOrDefault(type, ""))
            || root != null && name.equalsIgnoreCase(root.name)
            || root == null && !c.itemNames.containsKey(type) && RepoParser.SKIN_NAME.matcher(name).find()) return type;
        String v = name.toUpperCase(Locale.ROOT).trim().replaceAll("\\s+", "_").replaceAll("[^A-Z0-9_]", "");
        return v.isEmpty() ? type : type + "_" + v;
    }

    /** From ClientPacketListenerMixin after the packet was applied. Returns at once unless recording. */
    public static void onEquipment(ClientboundSetEquipmentPacket packet) {
        if (!io.github.terabold.skycosmetics.Settings.learnSkins) return;
        if (key == null) return;
        ItemStack head = null;
        for (Pair<EquipmentSlot, ItemStack> p : packet.getSlots()) {
            if (p.getFirst() == EquipmentSlot.HEAD) head = p.getSecond();
        }
        if (head == null) return;
        observe(packet.getEntity(), head, false);
    }

    public static void onEntityData(ClientboundSetEntityDataPacket packet) {
        if (!io.github.terabold.skycosmetics.Settings.learnSkins || key == null) return;
        for (var data : packet.packedItems()) if (data.value() instanceof ItemStack stack) observe(packet.id(), stack, true);
    }

    private static void observe(int id, ItemStack head, boolean display) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level != level || mc.player == null || target != -1 && id != target) return;
        Entity entity = mc.level.getEntity(id);
        if (display ? !(entity instanceof Display.ItemDisplay) : !(entity instanceof ArmorStand)) return;
        if (entity.distanceToSqr(mc.player) > NEAR_SQ) return;
        GameProfile profile = SkinLearner.profile(head);
        String value = profile == null ? null : SkinLearner.texture(profile);
        if (value == null || !SkinLearner.mojangTexture(value)) return;
        String hash = SkinLearner.textureHash(value);
        if (hash == null || !VALUES.isEmpty() && VALUES.getLast().equals(hash)) return;
        if (target == -1) {
            if (!hash.equals(anchor)) return;
            target = id;
            firstArrival = System.nanoTime();
        }
        long now = SkinLearner.ticks();
        AT[VALUES.size()] = now;
        VALUES.add(hash);
        FRAMES.add(profile.id() + ":" + value);
        lastTick = now;
        lastArrival = System.nanoTime();
        Fit f = captureFit(true);
        pendingCycle = f;
        if (f != null && TimingLearner.clean(firstArrival, lastArrival)) finish(f, true);
        else if (VALUES.size() >= MAX_FRAMES) finish(captureFit(false), false);
    }

    /** Wait for the next server clock update before certifying a cycle's most recent durations. */
    public static void onTime() {
        if (io.github.terabold.skycosmetics.Settings.learnSkins && Minecraft.getInstance().level == level
            && key != null && pendingCycle != null && TimingLearner.clean(firstArrival, lastArrival)) finish(pendingCycle, true);
    }

    static void tick(Minecraft mc) {
        if (!io.github.terabold.skycosmetics.Settings.learnSkins || familyLevel != mc.level || SkinLearner.ticks() - familyAt > CONTEXT_TICKS) clearContext();
        if (key == null) return;
        long now = SkinLearner.ticks();
        if (!io.github.terabold.skycosmetics.Settings.learnSkins || mc.level != level || VALUES.isEmpty() && now - startTick > APPEAR_TICKS) {
            result = "Preview: " + (mc.level != level ? "recording stopped after leaving the world" : !io.github.terabold.skycosmetics.Settings.learnSkins ? "skin learning is off" : "no matching preview appeared for " + label);
            if (announce && mc.level == level && io.github.terabold.skycosmetics.Settings.learnSkins) {
                say(Component.literal("No preview of " + label + " showed up near you, so nothing was learned").withStyle(ChatFormatting.YELLOW));
            }
            reset(); // left the world, or no preview showed up
            return;
        }
        if (VALUES.isEmpty() || now - lastTick < STALE_TICKS && now - startTick < MAX_TICKS) return;
        Fit f = captureFit(false);
        // A head that never changes is a still variant from the matched preview entity.
        if (f == null && VALUES.size() == 1) f = new Fit(1, 1, null, 0);
        if (f == null) result = "Preview: incomplete cycle for " + label + " (" + VALUES.size() + " changes)";
        if (f == null && announce) say(Component.literal("Could not find a repeating cycle in the preview of "
            + label + " (" + VALUES.size() + " frames)").withStyle(ChatFormatting.YELLOW));
        finish(f, false);
    }

    /** Keep packet bursts or disagreeing repeats provisional instead of certifying their timing. */
    private static Fit captureFit(boolean repeated) {
        Fit f = fit(AT, VALUES, repeated);
        if (f == null || f.frames > Captured.MAX_FRAMES) return null;
        int rounds = (VALUES.size() - 2) / f.frames;
        if (repeated && rounds < 3) return null;
        for (int i = 1; i + 1 < VALUES.size(); i++) {
            long duration = AT[i + 1] - AT[i];
            if (duration <= 0 || duration > Timings.MAX_TICKS) return null;
            if (repeated && i > f.frames) {
                long previous = AT[i + 1 - f.frames] - AT[i - f.frames];
                if (Math.abs(duration - previous) > Math.max(1, Math.min(duration, previous) / 10)) return null;
            }
        }
        return f;
    }

    private static void finish(Fit f, boolean repeated) {
        if (f == null) {
            reset();
            return;
        }
        String[] textures = FRAMES.subList(f.start, f.start + f.frames).toArray(String[]::new);
        Captured.Anim anim = new Captured.Anim(label, color, f.ticks, f.ticksPerTexture, textures, System.currentTimeMillis(),
            repeated ? Captured.Evidence.REPEATED_PREVIEW : Captured.Evidence.PREVIEW);
        String timing = f.frames == 1 ? "a still"
            : f.frames + " frames, " + (f.ticksPerTexture == null ? f.ticks + " ticks each" : "ticks " + Arrays.toString(f.ticksPerTexture));
        Component news = Component.literal("Learned " + (f.frames == 1 ? "the look" : "the animation") + " of ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(label).withColor((color != 0 ? color : 0xFFFF55FF) & 0xFFFFFF))
            .append(Component.literal(" (" + timing + ")").withStyle(ChatFormatting.GRAY));
        Component same = announce ? Component.literal("Recorded " + label + ": it matches what the mod already had")
            .withStyle(ChatFormatting.GRAY) : null;
        Repo.learn(key, anim, news, same);
        result = "Preview: recorded " + label + " (" + timing + ")";
        reset();
    }

    private static void reset() {
        key = null;
        target = -1;
        anchor = null;
        pendingCycle = null;
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
