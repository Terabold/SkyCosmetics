package io.github.terabold.skycosmetics.test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Textures;
import io.github.terabold.skycosmetics.data.Catalog;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import io.github.terabold.skycosmetics.data.SkinData;
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
 * compared with animatedskulls.json itself.
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
        for (var prop : p.partialProfile().properties().get("textures")) if (mine.contains(SkinData.textureHash(prop.value()))) return true;
        return false;
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
        for (String t : e.textures) out.add(SkinData.textureHash(t));
        return out;
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("[SkyCosmeticsTest] failed: " + what);
    }
}
