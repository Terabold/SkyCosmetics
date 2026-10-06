package io.github.terabold.skycosmetics.test;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.gui.StudioScreen;
import io.github.terabold.skycosmetics.render.Glints;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.SharedConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.util.ARGB;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Per-item glint colour, speed and strength: a renamed, glinting sword with a
 * red glint at 3x speed, and a worn (Hypixel-enchanted) chestplate with a green
 * glint. Checks the render copies carry the styles, that the tinted textures
 * really are red/green and shared between speeds, that our types were drawn in
 * the GUI, hand and armour layer (and never without a colour/speed/strength),
 * the cache bounds, and - from the screenshots - that the hotbar slot turns red
 * compared with Minecraft's purple glint on the same scene, and that on armour
 * stands a speed-1 style looks exactly like Minecraft's glint, a green one is
 * green, strength 3 is brighter, 0.25 dimmer, and 0.5 matches Minecraft's Glint
 * Strength setting at 0.5. A resource pack that hides the armour glint must stay
 * hidden for a colour, but strength 3 brightens Minecraft's own glint.
 */
public class GlintRenderTest implements FabricClientGameTest {
    private static final String SWORD = "g-sword";
    private static final String CHEST = "g-chest";
    private static final String HIDING_PACK = "skycosmetics-test-hidden-glint";
    private static final Looks.Look SWORD_LOOK = new Looks.Look(null, null, "&cGlint Blade", "on", "#FF2020", 3f, null, "glint sword");
    private static final Looks.Look CHEST_LOOK = new Looks.Look(null, null, null, null, "#20FF20", null, null, "glint chest");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientLevel().waitForChunksRender();
            sp.getServer().runCommand("time set noon");
            sp.getServer().runCommand("weather clear");
            // Hypixel's enchanted armour glints by itself; the sword only through the look's "on".
            sp.getServer().runCommand("item replace entity @a armor.chest with minecraft:netherite_chestplate[minecraft:custom_data="
                + "{id:\"GLINT_TEST_CHESTPLATE\",uuid:\"" + CHEST + "\"},minecraft:enchantment_glint_override=true]");
            sp.getServer().runCommand("item replace entity @a hotbar.0 with minecraft:diamond_sword[minecraft:custom_data="
                + "{id:\"GLINT_TEST_SWORD\",uuid:\"" + SWORD + "\"}]");
            sp.getServer().runCommand("item replace entity @a hotbar.1 with minecraft:golden_sword[minecraft:enchantment_glint_override=true]");
            ctx.runOnClient(mc -> {
                mc.player.setYRot(0);
                mc.player.setXRot(0);
                mc.player.getInventory().setSelectedSlot(0);
            });
            ctx.waitTicks(5);
            // The same sword in an item display beside you: the world item path.
            double[] at = ctx.computeOnClient(mc -> new double[]{mc.player.getX() - 1.1, mc.player.getY() + 1.2, mc.player.getZ() + 1.6});
            sp.getServer().runCommand(String.format(Locale.ROOT, "summon minecraft:item_display %.2f %.2f %.2f "
                + "{item:{id:\"minecraft:diamond_sword\",components:{\"minecraft:custom_data\":{id:\"GLINT_TEST_SWORD\",uuid:\""
                + SWORD + "\"}}},Tags:[\"ssglint\"]}", at[0], at[1], at[2]));

            ctx.runOnClient(mc -> {
                Looks.put(false, SWORD, SWORD_LOOK);
                Looks.put(false, CHEST, CHEST_LOOK);
                mc.options.setCameraType(CameraType.FIRST_PERSON);
            });
            ctx.waitTicks(10);

            ctx.runOnClient(GlintRenderTest::checkStyles);
            Path red = ctx.takeScreenshot("skycosmetics-20-glint-hotbar-hand");
            int[] afterHand = ctx.computeOnClient(mc -> Glints.stats());
            check(afterHand[0] > 0, "the red glint was drawn with our item type (draws=" + afterHand[0] + ")");
            ctx.runOnClient(mc -> {
                Glints.Style s = Cosmetics.glintOf(Cosmetics.apply(sword(mc), true));
                checkTint(mc, Glints.texture(s, Glints.Kind.ITEM), 0, "the sword's glint texture is red");
            });

            ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
            ctx.waitTicks(10);
            ctx.takeScreenshot("skycosmetics-21-glint-model");
            int[] afterModel = ctx.computeOnClient(mc -> Glints.stats());
            check(afterModel[1] > 0, "the chestplate was given our armor glint type (uses=" + afterModel[1] + ")");
            ctx.runOnClient(mc -> {
                Glints.Style s = Cosmetics.glintOf(Cosmetics.apply(mc.player.getItemBySlot(EquipmentSlot.CHEST), true));
                check(Glints.typeOf(s, Glints.Kind.ARMOR) != null, "the worn chestplate made its armor glint type");
                checkTint(mc, Glints.texture(s, Glints.Kind.ARMOR), 1, "the chestplate's armor glint texture is green");
                String armour = Glints.typeOf(s, Glints.Kind.ARMOR).toString();
                check(armour.contains("armor_entity_glint_texturing") && armour.contains("skycosmetics:glint/armor_20ff20"),
                    "no speed set: Minecraft's armor glint motion with the green texture");
                String item = Glints.typeOf(Cosmetics.glintOf(Cosmetics.apply(sword(mc), true)), Glints.Kind.ITEM).toString();
                check(item.contains("skycosmetics_glint_texturing") && item.contains("skycosmetics:glint/item_ff2020"),
                    "speed 3: the sword's type runs our texture transform with the red texture");

                int tints = Glints.stats()[4];
                Glints.Style fast = Glints.style("#20FF20", 2f);
                check(Glints.armour(fast) != Glints.armour(s), "another speed is another armor type");
                check(Glints.texture(fast, Glints.Kind.ARMOR).equals(Glints.texture(s, Glints.Kind.ARMOR))
                    && Glints.stats()[4] == tints, "the same color at another speed reuses its texture (" + tints + " textures)");
            });
            checkCap(ctx);

            ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
            ctx.waitTicks(5);
            ctx.takeScreenshot("skycosmetics-22-glint-inventory");
            ctx.setScreen(() -> new StudioScreen(null, Minecraft.getInstance().player.getMainHandItem().copy()));
            ctx.waitTicks(10);
            ctx.takeScreenshot("skycosmetics-23-glint-studio");
            ctx.setScreen(() -> null);

            // Same scenes with Minecraft's own glint: the colour must be what changed.
            ctx.runOnClient(mc -> {
                Looks.put(false, SWORD, SWORD_LOOK.withGlintColor(null).withGlintSpeed(null));
                Looks.put(false, CHEST, null);
                mc.options.setCameraType(CameraType.FIRST_PERSON);
            });
            ctx.waitTicks(5);
            ctx.runOnClient(mc -> {
                check(Cosmetics.glintOf(Cosmetics.apply(sword(mc), true)) == null, "no color/speed: no glint style");
                check(Cosmetics.glintOf(Cosmetics.apply(mc.player.getItemBySlot(EquipmentSlot.CHEST), true)) == null,
                    "look removed: no glint style");
            });
            int[] before = ctx.computeOnClient(mc -> Glints.stats());
            Path purple = ctx.takeScreenshot("skycosmetics-24-glint-hotbar-vanilla");
            ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
            ctx.waitTicks(10);
            ctx.takeScreenshot("skycosmetics-25-glint-model-vanilla");
            int[] after = ctx.computeOnClient(mc -> Glints.stats());
            check(after[0] == before[0] && after[1] == before[1], "no color/speed anywhere: hand, hotbar, item display "
                + "and armor all draw Minecraft's own glint (our draws stay " + before[0] + "/" + before[1] + ")");

            double[] slot = ctx.computeOnClient(GlintRenderTest::hotbarSlot0);
            double redSlot = redness(red, slot), purpleSlot = redness(purple, slot);
            System.out.printf(Locale.ROOT, "[SkyCosmeticsTest] hotbar sword red-blue: red glint %.1f, vanilla %.1f%n", redSlot, purpleSlot);
            check(redSlot > purpleSlot + 10, "the hotbar sword's glint is red, not purple");

            // Strength 3 alone brightens the same sword's own (purple) glint.
            ctx.runOnClient(mc -> {
                Looks.put(false, SWORD, SWORD_LOOK.withGlintColor(null).withGlintSpeed(null).withGlintStrength(3f));
                mc.options.setCameraType(CameraType.FIRST_PERSON);
            });
            ctx.waitTicks(5);
            Path strong = ctx.takeScreenshot("skycosmetics-24b-glint-hotbar-strength");
            ctx.runOnClient(mc -> check(Glints.texture(Cosmetics.glintOf(Cosmetics.apply(sword(mc), true)), Glints.Kind.ITEM) != null,
                "strength 3 made the sword's item glint texture"));
            double strongSlot = brightness(strong, slot), plainSlot = brightness(purple, slot);
            System.out.printf(Locale.ROOT, "[SkyCosmeticsTest] hotbar sword r+g+b: strength 3 %.1f, vanilla %.1f%n", strongSlot, plainSlot);
            check(strongSlot > plainSlot + 20, "strength 3 makes the hotbar sword's glint brighter");
            checkStands(ctx, sp);

            // A resource reload drops every glint texture and buffer; the next frames make them again. It also
            // turns on a pack that hides the armour glint (checked below, then turned off with another reload).
            ctx.runOnClient(mc -> {
                Looks.put(false, SWORD, SWORD_LOOK);
                setHidingPack(mc, true);
                mc.reloadResourcePacks();
            });
            ctx.waitFor(mc -> mc.getOverlay() == null, 20 * 60);
            ctx.waitTicks(5);
            ctx.runOnClient(mc -> {
                int[] st = Glints.stats();
                check(st[2] == st[3] && st[4] <= st[2], "after reload, buffers and textures match live glints ("
                    + st[2] + " live, " + st[3] + " buffers, " + st[4] + " textures)");
                Glints.Style s = Cosmetics.glintOf(Cosmetics.apply(sword(mc), true));
                checkTint(mc, Glints.texture(s, Glints.Kind.ITEM), 0, "after reload the sword's glint is red again");
            });
            ctx.takeScreenshot("skycosmetics-26-glint-after-reload");
            checkHidingPack(ctx);

            ctx.runOnClient(mc -> {
                Looks.put(false, SWORD, null);
                mc.options.setCameraType(CameraType.FIRST_PERSON);
            });
            sp.getServer().runCommand("kill @e[tag=ssglint]");
            System.out.println("[SkyCosmeticsTest] glint color and speed: OK");

            checkBrokenItems(ctx, sp);
        }
    }

    /**
     * Three armour stands with Minecraft's Glint Speed setting at 0 (and full strength), so the glint holds
     * still and two screenshots can be compared: all three with Minecraft's glint, then a speed-1 style on the
     * middle one (our render type with Minecraft's texture must draw exactly the same, and still, since speed
     * is a multiple of the setting) and a green style on the right one. The left one shows the scene itself
     * did not change. Chest regions are fractions of the 854x480 test window.
     */
    private static void checkStands(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String[] ids = {"g-stand-vanilla", "g-stand-speed", "g-stand-green"};
        sp.getServer().runCommand("kill @e[tag=ssglint]"); // the sword display would stand in front of them
        double[] p = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        for (int i = 0; i < 3; i++) {
            sp.getServer().runCommand(String.format(Locale.ROOT, "summon minecraft:armor_stand %.2f %.2f %.2f "
                + "{Tags:[\"ssglint\",\"%s\"],NoGravity:1b,Rotation:[180f,0f]}", p[0] + 1.3 - 1.3 * i, p[1], p[2] + 3, ids[i]));
            sp.getServer().runCommand("item replace entity @e[tag=" + ids[i] + ",limit=1] armor.chest with "
                + "minecraft:netherite_chestplate[minecraft:custom_data={id:\"GLINT_TEST_CHESTPLATE\",uuid:\"" + ids[i]
                + "\"},minecraft:enchantment_glint_override=true]");
        }
        double[] options = ctx.computeOnClient(mc -> new double[]{mc.options.glintSpeed().get(), mc.options.glintStrength().get()});
        ctx.runOnClient(mc -> {
            mc.options.glintSpeed().set(0.0);
            mc.options.glintStrength().set(1.0);
            mc.player.setYRot(0);
            mc.player.setXRot(0);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.hideGui = true; // no hand or held sword in front of the stands
        });
        ctx.waitTicks(10);
        Path plain = ctx.takeScreenshot("skycosmetics-25b-glint-stands-minecraft");
        int uses = ctx.computeOnClient(mc -> Glints.stats()[1]);
        ctx.runOnClient(mc -> {
            Looks.put(false, ids[1], new Looks.Look(null, null, null, null, null, 1f, null, "glint speed 1"));
            Looks.put(false, ids[2], new Looks.Look(null, null, null, null, "#20FF20", null, null, "glint green"));
        });
        ctx.waitTicks(5);
        Path styled = ctx.takeScreenshot("skycosmetics-25c-glint-stands-styled");
        check(ctx.computeOnClient(mc -> Glints.stats()[1]) > uses, "the styled stands' armor gets our glint types");
        double[][] a = chests(plain), b = chests(styled);
        System.out.printf(Locale.ROOT, "[SkyCosmeticsTest] glint stands (r/g/b before -> after): %s | %s | %s%n",
            rgb(a[0], b[0]), rgb(a[1], b[1]), rgb(a[2], b[2]));
        // A still glint is faint, but the scene is pixel-exact between the shots: any shift is the style's.
        check(diff(a[0], b[0]) < 0.5, "the scene held still: the left stand's glint is unchanged (" + rgb(a[0], b[0]) + ")");
        check(diff(a[1], b[1]) < 0.5, "a speed-1 style draws exactly Minecraft's armor glint (" + rgb(a[1], b[1]) + ")");
        check(b[2][1] - b[2][2] > a[2][1] - a[2][2] + 1.5, "a green style turns the armor glint green (" + rgb(a[2], b[2]) + ")");
        checkStrength(ctx, ids, a);
        ctx.runOnClient(mc -> {
            for (String id : ids) Looks.put(false, id, null);
            mc.options.glintSpeed().set(options[0]);
            mc.options.glintStrength().set(options[1]);
            mc.options.hideGui = false;
        });
        sp.getServer().runCommand("kill @e[tag=ssglint]");
    }

    /**
     * Strength 0.5, 3 and 0.25 on the three stands, then no styles with Minecraft's Glint Strength setting
     * at 0.5. Strength is a multiple of that setting, so the left stand must look the same in both shots.
     * {@code plain} is the stands' chests with Minecraft's glint at full strength.
     */
    private static void checkStrength(ClientGameTestContext ctx, String[] ids, double[][] plain) {
        float[] strengths = {0.5f, 3f, 0.25f};
        ctx.runOnClient(mc -> {
            for (int i = 0; i < 3; i++) {
                Looks.put(false, ids[i], new Looks.Look(null, null, null, null, null, null, strengths[i], "strength " + strengths[i]));
            }
        });
        ctx.waitTicks(5);
        Path strong = ctx.takeScreenshot("skycosmetics-25d-glint-stands-strength");
        ctx.runOnClient(mc -> {
            check(Glints.texture(Glints.style(null, null, 3f), Glints.Kind.ARMOR) != null, "strength 3 made its armor texture");
            for (String id : ids) Looks.put(false, id, null);
            mc.options.glintStrength().set(0.5);
        });
        ctx.waitTicks(5);
        Path half = ctx.takeScreenshot("skycosmetics-25e-glint-stands-setting-half");
        double[][] c = chests(strong), d = chests(half);
        System.out.printf(Locale.ROOT, "[SkyCosmeticsTest] glint strength (r/g/b strength -> setting 0.5; full strength sum): "
            + "%s | %s | %s; %.1f %.1f %.1f%n", rgb(c[0], d[0]), rgb(c[1], d[1]), rgb(c[2], d[2]),
            sum(plain[0]), sum(plain[1]), sum(plain[2]));
        check(sum(d[0]) < sum(plain[0]) - 1, "the Glint Strength setting at 0.5 dims the glint (" + rgb(plain[0], d[0]) + ")");
        check(diff(c[0], d[0]) < 1, "strength 0.5 draws like Minecraft's Glint Strength at 0.5 (" + rgb(c[0], d[0]) + ")");
        check(sum(c[1]) > sum(plain[1]) + 10, "strength 3 is brighter than Minecraft's glint (" + rgb(plain[1], c[1]) + ")");
        // A still glint adds little light here, but the scene is pixel-exact between the shots.
        check(sum(c[2]) < sum(plain[2]) - 1 && sum(c[2]) < sum(d[2]) - 0.2, "strength 0.25 is dimmer than Minecraft's glint "
            + "and than the setting at 0.5 (" + rgb(plain[2], c[2]) + ", setting 0.5 " + rgb(d[2], c[2]) + ")");
    }

    private static double sum(double[] c) {
        return c[0] + c[1] + c[2];
    }

    /**
     * Adds (or removes) a resource pack that hides the armour glint: a small, fully transparent texture.
     * Takes effect with the next resource reload.
     */
    private static void setHidingPack(Minecraft mc, boolean on) {
        Path dir = mc.getResourcePackDirectory().resolve(HIDING_PACK);
        String id = "file/" + dir.getFileName();
        try {
            if (on) {
                Path png = dir.resolve("assets/minecraft/textures/misc/enchanted_glint_armor.png");
                Files.createDirectories(png.getParent());
                int format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major();
                Files.writeString(dir.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"SkyCosmetics test: hidden armor glint\","
                    + "\"min_format\":" + format + ",\"max_format\":" + format + "}}");
                try (NativeImage img = new NativeImage(16, 16, true)) {
                    img.writeToFile(png);
                }
            }
            PackRepository repo = mc.getResourcePackRepository();
            repo.reload();
            List<String> selected = new ArrayList<>(repo.getSelectedIds());
            selected.remove(id);
            if (on) {
                check(repo.isAvailable(id), "the test pack that hides the armor glint is available");
                selected.add(id);
            }
            repo.setSelected(selected);
        } catch (java.io.IOException e) {
            throw new AssertionError("could not write the test pack", e);
        }
    }

    /**
     * With the pack that hides the armour glint: a colour or a dimmer strength keeps the pack's (hidden)
     * texture, strength 3 brightens Minecraft's own. Then the pack is turned off again.
     */
    private static void checkHidingPack(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            Glints.Style bright = Glints.style(null, null, 3f), dim = Glints.style(null, null, 0.25f);
            Glints.Style green = Glints.style("#20FF20", null);
            for (Glints.Style s : new Glints.Style[]{bright, dim, green}) Glints.armour(s);
            check(size(mc, Glints.texture(green, Glints.Kind.ARMOR)) == 16
                && size(mc, Glints.texture(dim, Glints.Kind.ARMOR)) == 16, "a color or strength 0.25 keeps the pack's hidden glint");
            check(size(mc, Glints.texture(bright, Glints.Kind.ARMOR)) == 128, "strength 3 on a hidden glint brightens Minecraft's own");
            int[] top = brightest(mc, Glints.texture(bright, Glints.Kind.ARMOR));
            check(top[2] == 255 && top[0] > 100, String.format(Locale.ROOT,
                "strength 3 on a hidden glint is Minecraft's purple at full brightness (brightest texel %d,%d,%d)", top[0], top[1], top[2]));
            Looks.put(false, CHEST, new Looks.Look(null, null, null, null, null, null, 3f, "strength on hidden glint"));
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        });
        ctx.waitTicks(10);
        ctx.takeScreenshot("skycosmetics-26b-glint-hidden-pack-strength");
        ctx.runOnClient(mc -> {
            Looks.put(false, CHEST, null);
            setHidingPack(mc, false);
            mc.reloadResourcePacks();
        });
        ctx.waitFor(mc -> mc.getOverlay() == null, 20 * 60);
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> {
            Glints.Style green = Glints.style("#20FF20", null);
            Glints.armour(green);
            check(size(mc, Glints.texture(green, Glints.Kind.ARMOR)) == 128, "pack off: a color tints the normal armor glint again");
            deleteTree(mc.getResourcePackDirectory().resolve(HIDING_PACK));
        });
    }

    private static void deleteTree(Path dir) {
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        } catch (java.io.IOException e) {
            System.out.println("[SkyCosmeticsTest] could not delete " + dir + ": " + e);
        }
    }

    private static int size(Minecraft mc, Identifier id) {
        return id != null && mc.getTextureManager().getTexture(id) instanceof DynamicTexture t ? t.getPixels().getWidth() : -1;
    }

    /** Mean red, green and blue over each stand's chestplate. */
    private static double[][] chests(Path shot) {
        double[][] c = new double[3][];
        for (int i = 0; i < 3; i++) {
            double x = 0.316 + 0.181 * i;
            double[] r = {x - 0.026, 0.583, x + 0.026, 0.698};
            c[i] = new double[]{mean(shot, r, ARGB::red), mean(shot, r, ARGB::green), mean(shot, r, ARGB::blue)};
        }
        return c;
    }

    private static double diff(double[] a, double[] b) {
        return Math.max(Math.abs(a[0] - b[0]), Math.max(Math.abs(a[1] - b[1]), Math.abs(a[2] - b[2])));
    }

    private static String rgb(double[] a, double[] b) {
        return String.format(Locale.ROOT, "%.1f/%.1f/%.1f -> %.1f/%.1f/%.1f", a[0], a[1], a[2], b[0], b[1], b[2]);
    }

    /**
     * Any server can send pet items whose petInfo has the wrong shape. Worn on a
     * stand's head and chest, held in its hand, shown by an item display and
     * right-clicked, they must keep Minecraft's own look and never crash the
     * game, whatever SkyCosmetics' lookup makes of them.
     */
    private static void checkBrokenItems(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String data = "minecraft:custom_data={id:\"PET\",uuid:\"g-broken\",petInfo:'{\"type\":null,\"uuid\":{}}'}";
        double[] at = ctx.computeOnClient(mc -> new double[]{mc.player.getX() + 1.2, mc.player.getY(), mc.player.getZ() + 2.2});
        sp.getServer().runCommand(String.format(Locale.ROOT, "summon minecraft:armor_stand %.2f %.2f %.2f "
            + "{Tags:[\"ssglint\"],NoGravity:1b,ShowArms:1b,Rotation:[180f,0f]}", at[0], at[1], at[2]));
        String stand = "item replace entity @e[type=armor_stand,tag=ssglint,limit=1] ";
        sp.getServer().runCommand(stand + "armor.head with minecraft:player_head[" + data + "]");
        sp.getServer().runCommand(stand + "armor.chest with minecraft:leather_chestplate[" + data + "]");
        sp.getServer().runCommand(stand + "weapon.mainhand with minecraft:player_head[" + data + "]");
        sp.getServer().runCommand(String.format(Locale.ROOT, "summon minecraft:item_display %.2f %.2f %.2f "
            + "{Tags:[\"ssglint\"],item:{id:\"minecraft:player_head\",components:{\"minecraft:custom_data\":"
            + "{id:\"PET\",petInfo:'{\"type\":[]}'}}}}", at[0] - 2.4, at[1] + 1.4, at[2]));
        ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
        ctx.waitTicks(10);
        ctx.takeScreenshot("skycosmetics-27-broken-pet-data");
        ctx.runOnClient(mc -> {
            check(Glints.current() == null, "no glint style is left over outside item models");
            // The orb tracker looks at every item you right-click. Held for this one call only, so the
            // inventory scans (not part of this test) never see it.
            CompoundTag t = new CompoundTag();
            t.putString("id", "PET");
            t.putString("petInfo", "{\"type\":{}}");
            ItemStack broken = new ItemStack(Items.PLAYER_HEAD);
            broken.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
            ItemStack held = mc.player.getMainHandItem();
            mc.player.setItemInHand(InteractionHand.MAIN_HAND, broken);
            InteractionResult r = UseItemCallback.EVENT.invoker().interact(mc.player, mc.level, InteractionHand.MAIN_HAND);
            mc.player.setItemInHand(InteractionHand.MAIN_HAND, held);
            check(r == InteractionResult.PASS, "right-clicking a broken pet item passes");
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        });
        sp.getServer().runCommand("kill @e[tag=ssglint]");
        System.out.println("[SkyCosmeticsTest] items with broken pet data keep Minecraft's look: OK");
    }

    /**
     * Many glint colours in one frame: kept while in use, but never more than
     * twice the cap (the rest draw Minecraft's glint). A second later the next
     * new one trims the cache to the cap, and the dropped ones' buffers and
     * textures are freed on the next tick.
     */
    private static void checkCap(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            int made = 0;
            for (int i = 0; i < 70; i++) {
                if (Glints.armour(Glints.style(String.format(Locale.ROOT, "#%06X", 0x102030 + i), null)) != null) made++;
            }
            int[] st = Glints.stats();
            check(made >= 40 && made < 70 && st[2] == 64, "a burst of 70 colors stops at twice the cap ("
                + made + " made, " + st[2] + " live)");
        });
        ctx.waitTicks(30);
        ctx.runOnClient(mc -> Glints.armour(Glints.style("#123456", null)));
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            int[] st = Glints.stats();
            check(st[2] <= 32, "a second later the cache is trimmed to the cap (" + st[2] + " live)");
            check(st[3] == st[2], "dropped glints' buffers are freed (" + st[3] + " buffers for " + st[2] + " live)");
            check(st[4] <= st[2], "dropped glints' textures are freed (" + st[4] + " textures for " + st[2] + " live)");
            Glints.Style s = Cosmetics.glintOf(Cosmetics.apply(sword(mc), true));
            check(Glints.typeOf(s, Glints.Kind.ITEM) != null, "the sword drawn every frame keeps its glint");
        });
    }

    private static ItemStack sword(Minecraft mc) {
        return mc.player.getInventory().getItem(0);
    }

    private static void checkStyles(Minecraft mc) {
        Glints.Style s = Cosmetics.glintOf(Cosmetics.apply(sword(mc), true));
        check(s != null && s.rgb == 0xFF2020 && s.speed == 3f, "the sword's render copy carries red x3: " + s);
        Glints.Style c = Cosmetics.glintOf(Cosmetics.apply(mc.player.getItemBySlot(EquipmentSlot.CHEST), true));
        check(c != null && c.rgb == 0x20FF20 && c.speed == 0, "the chestplate's render copy carries green: " + c);
        check(Glints.style("#ff2020", 3.02f) == s, "styles are interned (and speeds rounded to 0.05)");
        check(Glints.style(null, null) == null && Glints.style("purple", null) == null, "no color/speed: no style");
        check(Glints.style(null, null, 1f) == null && Glints.style(null, null, 1.02f) == null, "strength 1: no style");
        Glints.Style strong = Glints.style(null, null, 3f);
        check(strong != null && strong.rgb < 0 && strong.speed == 0 && strong.strength == 3f
            && Glints.style(null, null, 2.99f) == strong, "a strength alone is a style: " + strong);
        ItemStack plain = mc.player.getInventory().getItem(1);
        check(Cosmetics.glintOf(Cosmetics.apply(plain, true)) == null, "an item without a look keeps vanilla's glint");
        check(Glints.typeOf(s, Glints.Kind.ITEM) != null || Glints.typeOf(s, Glints.Kind.ITEM_TRANSLUCENT) != null,
            "the drawn sword made its item glint type");
        check(!"Glint Blade".equals(Cosmetics.originalName(sword(mc)).getString())
                && "Glint Blade".equals(sword(mc).getStyledHoverName().getString()), "the sword is renamed");
    }

    /** The brightest texel of a tinted glint texture is dominated by channel {@code ch} (0 red, 1 green). */
    private static void checkTint(Minecraft mc, Identifier id, int ch, String what) {
        check(id != null && mc.getTextureManager().getTexture(id) instanceof DynamicTexture, what + ": texture registered");
        int[] c = brightest(mc, id);
        check(c[ch] > 100 && c[ch] > 3 * c[(ch + 1) % 3] && c[ch] > 3 * c[(ch + 2) % 3],
            what + String.format(Locale.ROOT, " (brightest texel %d,%d,%d)", c[0], c[1], c[2]));
    }

    /** Red, green and blue of the brightest texel of one of our glint textures. */
    private static int[] brightest(Minecraft mc, Identifier id) {
        NativeImage img = ((DynamicTexture) mc.getTextureManager().getTexture(id)).getPixels();
        int best = 0, bestV = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getPixel(x, y);
                int v = ARGB.red(p) + ARGB.green(p) + ARGB.blue(p);
                if (v > bestV) {
                    bestV = v;
                    best = p;
                }
            }
        }
        return new int[]{ARGB.red(best), ARGB.green(best), ARGB.blue(best)};
    }

    /** Hotbar slot 0's item square as fractions of the screen: x0, y0, x1, y1. */
    private static double[] hotbarSlot0(Minecraft mc) {
        double w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        double x = w / 2 - 91 + 3, y = h - 22 + 3;
        return new double[]{x / w, y / h, (x + 16) / w, (y + 16) / h};
    }

    /** Mean red + green + blue over a screen region. */
    private static double brightness(Path png, double[] r) {
        return mean(png, r, p -> ARGB.red(p) + ARGB.green(p) + ARGB.blue(p));
    }

    /** Mean red minus blue over a screen region. */
    private static double redness(Path png, double[] r) {
        return mean(png, r, p -> ARGB.red(p) - ARGB.blue(p));
    }

    private static double mean(Path png, double[] r, java.util.function.IntToDoubleFunction f) {
        try (InputStream in = Files.newInputStream(png); NativeImage img = NativeImage.read(in)) {
            int x0 = (int) (r[0] * img.getWidth()), y0 = (int) (r[1] * img.getHeight());
            int x1 = (int) (r[2] * img.getWidth()), y1 = (int) (r[3] * img.getHeight());
            double sum = 0;
            for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) sum += f.applyAsDouble(img.getPixel(x, y));
            return sum / Math.max(1, (x1 - x0) * (y1 - y0));
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + png, e);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("[SkyCosmeticsTest] glint: " + what);
        System.out.println("[SkyCosmeticsTest] ok: " + what);
    }
}
