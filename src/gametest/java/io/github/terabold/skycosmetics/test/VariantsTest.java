package io.github.terabold.skycosmetics.test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.Textures;
import io.github.terabold.skycosmetics.data.Catalog;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import io.github.terabold.skycosmetics.data.SkinLearner;
import io.github.terabold.skycosmetics.gui.StudioScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.ItemLore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Skins with many variants, against the real NEU repo: every variant is listed with its skin (right after it, in
 * the skin's tab), named apart, with a look of its own that an item wearing it shows. Families of every shape: colors,
 * day and night, seasons, a variant name that extends another (BLACK_ICE after BLACK), the Harvester Helmet's
 * crop-and-color sheet, and pet skins. With the repo checkout at hand ({@code skycosmetics.repo}), the counts are
 * compared with animatedskulls.json itself. Then a swappable skin the repo has no variants for learns one from an
 * item wearing it, the way Hypixel marks the picked variant ({@code favorite_...}).
 */
public class VariantsTest implements FabricClientGameTest {
    private static final List<String> FAMILIES = List.of("NECRON_DIAMOND_KNIGHT", "FERMENTO_ULTIMATE", "SENTINEL_WARDEN",
        "WITHER_AWAKENED", "PET_SKIN_GOLDEN_DRAGON_SWAP_PLUSHIE", "PET_SKIN_ENDER_DRAGON_SWAP_PLUSHIE",
        "PET_SKIN_FOUR_SEASONS_GRIFFIN", "PET_SKIN_WOLF_SPIRIT", "PET_SKIN_BLACK_CAT_CATGIRL");
    /** Variants whose id extends another variant's: listed with the skin, named by their whole suffix. */
    private static final Map<String, String> NESTED = Map.of(
        "NECRON_DIAMOND_KNIGHT_BLACK_ICE", "(Black Ice)",
        "PET_SKIN_ENDER_DRAGON_SWAP_PLUSHIE_BLACK_ICE", "(Black Ice)",
        "PET_SKIN_GOLDEN_DRAGON_SWAP_PLUSHIE_BLUE_PASTEL", "(Blue Pastel)",
        "FERMENTO_ULTIMATE_CACTUS_RED", "(Cactus Red)");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.waitFor(mc -> !Repo.get().skins.isEmpty(), 20 * 120);
        ctx.runOnClient(mc -> everyVariantListed(Repo.get()));
        ctx.runOnClient(mc -> families(Repo.get()));
        ctx.runOnClient(mc -> againstRepo(Repo.get()));
        wearing(ctx);
        learned(ctx);
        newSkin(ctx);
        screenshot(ctx);
        System.out.println("[SkyCosmeticsTest] variant checks passed");
    }

    // ================================================================ the catalog

    /** No variant is left out of the lists, none hangs under another variant, and each sits with its skin. */
    private static void everyVariantListed(Catalog c) {
        Set<SkinEntry> inTabs = new HashSet<>(c.skinTab);
        inTabs.addAll(c.petSkinTab);
        int variants = 0;
        for (SkinEntry e : c.skins.values()) {
            if (!e.listed || e.kind == SkinEntry.Kind.HEAD || e.kind == SkinEntry.Kind.CUSTOM) continue;
            check(inTabs.contains(e), "listed in the Skins or Pets tab: " + e.id + " (parent " + e.parent + ")");
            if (e.parent == null) continue;
            variants++;
            int rgb = e.color & 0xFFFFFF;
            check(rgb != 0x000000 && rgb != 0x0000AA, "a name color that reads on the dark cards: " + e.id);
            SkinEntry p = c.skins.get(e.parent);
            check(p != null && (p.kind == SkinEntry.Kind.SKIN || p.kind == SkinEntry.Kind.PET_SKIN),
                "a variant's parent is the skin itself, not another variant: " + e.id + " -> " + e.parent);
        }
        for (List<SkinEntry> tab : List.of(c.skinTab, c.petSkinTab, c.helmetSkinTab)) {
            SkinEntry family = null;
            for (SkinEntry e : tab) {
                if (e.parent == null) family = e;
                else check(family != null && c.root(e) == family, "a variant follows its skin: " + e.id + " after "
                    + (family == null ? "nothing" : family.id));
            }
        }
        System.out.println("[SkyCosmeticsTest] variants: all " + variants + " listed with their skin");
    }

    /** Real families: every variant listed with the skin, named apart, each its own look. */
    private static void families(Catalog c) {
        for (String id : FAMILIES) {
            SkinEntry root = c.skin(id);
            check(root != null && root.parent == null, "the repo has " + id);
            List<SkinEntry> kids = new ArrayList<>();
            for (SkinEntry e : c.skins.values()) if (e != root && c.root(e) == root) kids.add(e);
            check(kids.size() >= 2 && kids.size() == c.variantCount(root), id + " has its variants: " + kids.size());
            List<SkinEntry> tab = root.kind == SkinEntry.Kind.PET_SKIN ? c.petSkinTab : c.skinTab;
            int at = tab.indexOf(root);
            check(at >= 0 && new HashSet<>(tab.subList(at + 1, Math.min(tab.size(), at + 1 + kids.size()))).containsAll(kids),
                id + ": every variant right after the skin");
            Set<String> names = new HashSet<>();
            Map<List<String>, String> looks = new HashMap<>();
            for (SkinEntry v : kids) {
                check(v.kind == SkinEntry.Kind.VARIANT && id.equals(v.parent), v.id + " is a variant of " + id);
                check(v.name.startsWith(root.name + " (") && names.add(v.name), "a name of its own: " + v.name);
                String other = looks.put(hashes(v), v.id);
                check(other == null, v.id + " and " + other + " look the same");
            }
        }
        for (Map.Entry<String, String> n : NESTED.entrySet()) {
            SkinEntry e = c.skin(n.getKey());
            check(e != null && e.name.endsWith(n.getValue()) && c.root(e).id.equals(e.parent), n.getKey() + ": " + (e == null ? null : e.name));
        }
        check(c.helmetSkinTab.contains(c.skin("NECRON_DIAMOND_KNIGHT_BLACK_ICE"))
            && c.helmetSkinTab.contains(c.skin("FERMENTO_ULTIMATE_WHEAT_YELLOW")), "nested variants show under Helmet too");
        check(c.variantCount(c.skin("FERMENTO_ULTIMATE")) >= 90, "every Harvester Helmet crop and color");
        Set<Integer> colors = new HashSet<>();
        for (SkinEntry e : c.skins.values()) if ("SENTINEL_WARDEN".equals(e.parent)) colors.add(e.color);
        check(colors.size() >= 4, "variants named in their own colors: " + colors.size());
        System.out.println("[SkyCosmeticsTest] variants: " + FAMILIES.size() + " real families complete and distinct");
    }

    /** With the repo checkout: every animatedskulls.json variant of the families, and pet_skin_variant, is in the catalog. */
    private static void againstRepo(Catalog c) {
        String repo = System.getProperty("skycosmetics.repo");
        Path file = repo == null ? null : Path.of(repo, "constants", "animatedskulls.json");
        if (file == null || !Files.isRegularFile(file)) {
            System.out.println("[SkyCosmeticsTest] variants: no repo checkout given, animatedskulls.json not compared");
            return;
        }
        JsonObject json;
        try {
            json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        JsonObject skins = json.getAsJsonObject("skins");
        int compared = 0;
        for (String id : FAMILIES) {
            int inRepo = 0;
            for (String k : skins.keySet()) if (k.startsWith(id + "_")) inRepo++;
            check(c.variantCount(c.skin(id)) >= inRepo, id + ": " + c.variantCount(c.skin(id)) + " of the repo's " + inRepo);
            compared++;
        }
        if (json.get("pet_skin_variant") instanceof JsonObject pets) {
            for (Map.Entry<String, JsonElement> e : pets.entrySet()) {
                for (JsonElement v : e.getValue().getAsJsonArray()) {
                    SkinEntry s = c.skin(v.getAsString());
                    check(s != null && e.getKey().equals(s.parent) && c.petSkinTab.contains(s), "pet skin variant " + v.getAsString());
                }
                compared++;
            }
        }
        System.out.println("[SkyCosmeticsTest] variants: " + compared + " families match animatedskulls.json");
    }

    // ================================================================ wearing one

    /** An item wearing a variant shows that variant's own texture, not the skin's. */
    private static void wearing(ClientGameTestContext ctx) {
        boolean ci = System.getenv("CI") != null;
        for (String id : List.of("NECRON_DIAMOND_KNIGHT_BLACK_ICE", "FERMENTO_ULTIMATE_CACTUS_RED", "SENTINEL_WARDEN_TEAL")) {
            ItemStack helm = ctx.computeOnClient(mc -> {
                ItemStack s = new ItemStack(Items.PLAYER_HEAD);
                CompoundTag t = new CompoundTag();
                t.putString("id", "TEST_VARIANT_HELMET");
                t.putString("uuid", "t-variant");
                s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
                Looks.putItem("t-variant", "TEST_VARIANT_HELMET", new Looks.Look(id, null, null, null, null, null, null, null));
                Textures.preload(Repo.get().skin(id));
                return s;
            });
            AtomicBoolean shown = new AtomicBoolean();
            for (int i = 0; i < 20 * 30 && !shown.get(); i++) {
                ctx.waitTicks(1);
                shown.set(ctx.computeOnClient(mc -> wears(Cosmetics.forRender(helm, null), Repo.get().skin(id))));
            }
            if (!shown.get() && ci) {
                System.out.println("[SkyCosmeticsTest] variants: " + id + " not downloaded in time on CI, not checked");
            } else {
                check(shown.get(), "an item wearing " + id + " shows its texture");
            }
            ctx.runOnClient(mc -> {
                SkinEntry v = Repo.get().skin(id);
                for (SkinEntry other : Repo.get().skins.values()) {
                    if (other != v && v.parent.equals(other.parent)) check(!hashes(other).equals(hashes(v)), id + " looks like " + other.id);
                }
            });
        }
        ctx.runOnClient(mc -> Looks.putItem("t-variant", null, null));
        System.out.println("[SkyCosmeticsTest] variants: items wear each variant's own texture");
    }

    private static boolean wears(ItemStack shown, SkinEntry e) {
        ResolvableProfile p = shown.get(DataComponents.PROFILE);
        if (p == null) return false;
        Set<String> mine = new HashSet<>(hashes(e));
        for (var prop : p.partialProfile().properties().get("textures")) if (mine.contains(SkinLearner.textureHash(prop.value()))) return true;
        return false;
    }

    // ================================================================ learning a missing one

    /**
     * A swappable skin the repo has no variants for: an item wearing variant 4 (favorite_... = 3, a texture the
     * family doesn't have) adds "(Variant 4)" under the skin; its default look and an item without the number don't.
     */
    private static void learned(ClientGameTestContext ctx) {
        SkinEntry root = ctx.computeOnClient(mc -> {
            Catalog c = Repo.get();
            for (SkinEntry e : c.skinTab) {
                if (e.parent == null && e.kind == SkinEntry.Kind.SKIN && e.swappable > 1 + c.variantCount(e)) return e;
            }
            return null;
        });
        if (root == null) {
            System.out.println("[SkyCosmeticsTest] variants: the repo has every swappable skin's variants, nothing to learn");
            return;
        }
        Path captured = FabricLoader.getInstance().getConfigDir().resolve("skycosmetics/captured.json");
        String had = readOrNull(captured);
        boolean learn = Settings.learnSkins;
        String key = root.id + "_VARIANT_4";
        String texture = value(0x4a11);
        try {
            Settings.learnSkins = true;
            int before = ctx.computeOnClient(mc -> Repo.get().variantCount(root));
            ctx.runOnClient(mc -> {
                Cosmetics.forRender(wearing(root.id, "favorite_test", 0, root.textures[0], "t-swap-0"), null); // its default look
                Cosmetics.forRender(wearing(root.id, null, 0, value(0x4a12), "t-swap-none"), null); // no variant number
                Cosmetics.forRender(wearing(root.id, "favorite_test", 3, root.textures[0], "t-swap-pending"), null);
                Cosmetics.forRender(wearing(root.id, "favorite_test", 3, texture, "t-swap-3"), null);
            });
            ctx.waitFor(mc -> Repo.get().skin(key) != null, 20 * 30);
            ctx.waitTicks(5);
            ctx.runOnClient(mc -> {
                Catalog c = Repo.get();
                SkinEntry v = c.skin(key);
                check(v.kind == SkinEntry.Kind.VARIANT && root.id.equals(v.parent) && v.learned, "learned as a variant: " + v.parent);
                check(v.name.equals(root.name + " (Captured style)"), "unknown style names are clearly marked: " + v.name);
                check(SkinLearner.textureHash(v.textures[0]).equals(SkinLearner.textureHash(texture)), "with the item's texture");
                check(c.variantCount(c.skin(root.id)) == before + 1, "the only one learned: " + c.variantCount(c.skin(root.id)));
                List<SkinEntry> tab = c.skinTab;
                int at = tab.indexOf(c.skin(root.id));
                check(at >= 0 && tab.subList(at + 1, at + 2 + before).contains(v), "listed with the skin");
            });
            ctx.waitFor(mc -> String.valueOf(readOrNull(captured)).contains(key), 20 * 10); // kept in captured.json
            // Read back from captured.json alone, it is still a variant of the skin.
            reload(ctx);
            check(ctx.computeOnClient(mc -> root.id.equals(Repo.get().skin(key).parent)), "a variant again after a reload");
            missingAnimation(ctx, root, key, texture);
            System.out.println("[SkyCosmeticsTest] variants: learned " + key + " from an item wearing it");
        } finally {
            Settings.learnSkins = learn;
            restore(captured, had);
            reload(ctx);
        }
        check(ctx.computeOnClient(mc -> Repo.get().skin(key) == null), "the learned variant is gone again");
    }

    /** Missing frames are learned from passive updates, then merged with a real server preview name. */
    private static void missingAnimation(ClientGameTestContext ctx, SkinEntry root, String key, String first) {
        boolean enabled = io.github.terabold.skycosmetics.data.TimingLearner.enabled;
        long[] now = {System.nanoTime()};
        try {
            ctx.runOnClient(mc -> {
                io.github.terabold.skycosmetics.data.TimingLearner.enabled = true;
                io.github.terabold.skycosmetics.data.TimingLearner.useClock(() -> now[0]);
                String[] textures = {first, value(0x4a13), value(0x4a14)};
                int[] ticks = {2, 4, 8};
                for (int i = 0; i < 8; i++) {
                    var stack = wearing(root.id, "favorite_test", 3, textures[i % 3], "t-cycle");
                    io.github.terabold.skycosmetics.data.TimingLearner.onSlot(
                        new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(0, 0, 5, stack));
                    now[0] += ticks[i % 3] * 50_000_000L;
                }
            });
            ctx.waitFor(mc -> Repo.get().skin(key).animated(), 20 * 20);
            ctx.runOnClient(mc -> {
                var animation = Repo.get().skin(key);
                check(animation.textures.length == 3 && java.util.Arrays.equals(animation.frameTicks, new int[]{4, 8, 2}),
                    "two complete passive cycles preserve each frame's actual duration");
                var swatch = new ItemStack(Items.PLAYER_HEAD);
                var tag = new CompoundTag(); tag.putString("id", root.id);
                swatch.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                swatch.set(DataComponents.CUSTOM_NAME, Component.literal("Test Aqua Skin"));
                swatch.set(DataComponents.PROFILE, Textures.profile(first));
                swatch.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Click to select!"))));
                io.github.terabold.skycosmetics.data.SkinLearner.observe(swatch);
            });
            String named = root.id + "_TEST_AQUA_SKIN";
            ctx.waitFor(mc -> Repo.get().skin(named) != null, 20 * 20);
            ctx.runOnClient(mc -> {
                check(Repo.get().skin(named).name.equals(root.name + " (Test Aqua Skin)"), "a new family's named skin choice does not overwrite its parent");
                check(Repo.get().skin(named).animated(), "the named style inherits its recorded frames and timing");
                check(!Repo.get().skin(key).listed && Repo.get().skin(key).animated(), "old saved ids remain usable without a duplicate unnamed card");
            });
        } finally {
            ctx.runOnClient(mc -> { io.github.terabold.skycosmetics.data.TimingLearner.enabled = enabled;
                io.github.terabold.skycosmetics.data.TimingLearner.useClock(null); });
        }
    }

    /** Newly discovered skins must retain the lore that permits learning their alternate looks, even after reload. */
    private static void newSkin(ClientGameTestContext ctx) {
        Path captured = FabricLoader.getInstance().getConfigDir().resolve("skycosmetics/captured.json");
        String had = readOrNull(captured);
        boolean learn = Settings.learnSkins;
        String id = "SSTEST_NEW_SWAPPABLE", variant = id + "_VARIANT_4";
        try {
            Settings.learnSkins = true;
            ctx.runOnClient(mc -> {
                ItemStack skin = new ItemStack(Items.PLAYER_HEAD);
                CompoundTag tag = new CompoundTag(); tag.putString("id", id);
                skin.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                skin.set(DataComponents.PROFILE, Textures.profile(value(0x4b11)));
                skin.set(DataComponents.CUSTOM_NAME, Component.literal("Red"));
                skin.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Click to preview!"))));
                Cosmetics.forRender(skin, null); // a swatch must not negative-cache the shared skin id
                skin = skin.copy(); // a distinct server stack is resolved again
                skin.set(DataComponents.CUSTOM_NAME, Component.literal("Test New Skin"));
                skin.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("This skin can be applied to Spirit Mask."),
                    Component.literal("This skin is swappable between"), Component.literal("21 different variants!"),
                    Component.literal("LEGENDARY COSMETIC"))));
                Cosmetics.forRender(skin, null);
            });
            ctx.waitFor(mc -> Repo.get().skin(id) != null, 600);
            String named = id + "_BLUE_TEST_NEW_SKIN";
            ctx.runOnClient(mc -> {
                var choice = new ItemStack(Items.PLAYER_HEAD);
                var tag = new CompoundTag(); tag.putString("id", id);
                choice.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                choice.set(DataComponents.PROFILE, Textures.profile(value(0x4b13)));
                choice.set(DataComponents.CUSTOM_NAME, Component.literal("Blue Test New Skin"));
                choice.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Click to select!"))));
                io.github.terabold.skycosmetics.data.SkinLearner.observe(choice);
            });
            ctx.waitFor(mc -> Repo.get().skin(named) != null, 600);
            check(ctx.computeOnClient(mc -> Repo.get().skin(id).name.equals("Test New Skin")
                && Repo.get().skin(named).parent.equals(id)), "new-family names ending in Skin remain separate selectable styles");
            ctx.runOnClient(mc -> check(Repo.get().skin(id).swappable == 21, "new skin retains wrapped variant-count lore"));
            reload(ctx);
            ctx.runOnClient(mc -> {
                SkinEntry root = Repo.get().skin(id);
                check(root.swappable == 21 && root.use == SkinEntry.Use.HELMET, "new skin metadata survives disk reload");
                Cosmetics.forRender(wearing(id, "favorite_test", 3, root.textures[0], "t-new-pending"), null);
                Cosmetics.forRender(wearing(id, "favorite_test", 3, value(0x4b12), "t-new-variant"), null);
            });
            ctx.waitFor(mc -> Repo.get().skin(variant) != null, 600);
            reload(ctx);
            ctx.runOnClient(mc -> check(id.equals(Repo.get().skin(variant).parent)
                && Repo.get().helmetSkinTab.contains(Repo.get().skin(variant)), "new variant persists in its helmet family"));
        } finally {
            Settings.learnSkins = learn;
            restore(captured, had);
            reload(ctx);
        }
    }

    /** An item wearing {@code skin}, with Hypixel's variant number under {@code field} (none when null). */
    private static ItemStack wearing(String skin, String field, int index, String texture, String uuid) {
        CompoundTag t = new CompoundTag();
        t.putString("id", "TEST_SWAP_HELMET");
        t.putString("uuid", uuid);
        t.putString("skin", skin);
        if (field != null) t.putInt(field, index);
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        s.set(DataComponents.PROFILE, Textures.profile(texture));
        return s;
    }

    // ================================================================ the studio

    /** The Skins tab searched for "knight": the Knight skin and its colors together, Black Ice among them. */
    private static void screenshot(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientLevel().waitForChunksRender();
            ctx.setScreen(() -> new StudioScreen(null, ItemStack.EMPTY));
            ctx.waitForScreen(StudioScreen.class);
            ctx.clickScreenButton("Skins");
            ctx.clickScreenButton("All");
            ctx.waitTicks(2);
            ctx.getInput().typeChars("knight skin");
            ctx.waitTicks(40);
            ctx.takeScreenshot("skycosmetics-98-variants-knight");
            // A swappable skin the repo has one look of: its tooltip says how many Hypixel has.
            String swappable = ctx.computeOnClient(mc -> {
                Catalog c = Repo.get();
                for (SkinEntry e : c.skinTab) if (e.parent == null && e.swappable > 1 + c.variantCount(e)) return e.name;
                return null;
            });
            if (swappable != null) {
                ctx.setScreen(() -> new StudioScreen(null, ItemStack.EMPTY));
                ctx.waitForScreen(StudioScreen.class);
                ctx.clickScreenButton("Skins");
                ctx.clickScreenButton("All");
                ctx.waitTicks(2);
                ctx.getInput().typeChars(swappable.toLowerCase(java.util.Locale.ROOT));
                ctx.waitTicks(5);
                double[] at = ctx.computeOnClient(mc -> {
                    double scale = mc.getWindow().getGuiScale();
                    return new double[]{(field(mc.screen, "midX") + 12) * scale, (field(mc.screen, "gridY") + 12) * scale};
                });
                ctx.getInput().setCursorPos(at[0], at[1]);
                ctx.waitTicks(30);
                ctx.takeScreenshot("skycosmetics-98-variants-swappable-tooltip");
            }
            ctx.setScreen(() -> null);
        }
    }

    // ================================================================ helpers

    private static int field(Object o, String name) {
        try {
            java.lang.reflect.Field f = StudioScreen.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(o);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static List<String> hashes(SkinEntry e) {
        List<String> out = new ArrayList<>();
        for (String t : e.textures) out.add(SkinLearner.textureHash(t));
        return out;
    }

    /** A texture value with a made-up skin hash. */
    private static String value(int n) {
        String hash = String.format("5ca1ab1e%056x", n);
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static void reload(ClientGameTestContext ctx) {
        AtomicBoolean done = new AtomicBoolean();
        Repo.reload(() -> done.set(true));
        ctx.waitFor(mc -> done.get(), 20 * 120);
        ctx.waitTicks(2);
    }

    private static String readOrNull(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readString(p) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void restore(Path p, String content) {
        try {
            if (content == null) Files.deleteIfExists(p);
            else Files.writeString(p, content);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("[SkyCosmeticsTest] failed: " + what);
    }
}
