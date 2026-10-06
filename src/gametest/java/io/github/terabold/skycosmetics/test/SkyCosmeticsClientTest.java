package io.github.terabold.skycosmetics.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.tree.CommandNode;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Textures;
import io.github.terabold.skycosmetics.data.Catalog;
import io.github.terabold.skycosmetics.data.PreviewRecorder;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import io.github.terabold.skycosmetics.gui.StudioScreen;
import io.github.terabold.skycosmetics.mixin.AbstractContainerScreenAccessor;
import io.github.terabold.skycosmetics.mixin.RecipeBookComponentAccessor;
import io.github.terabold.skycosmetics.mixin.RecipeBookScreenAccessor;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Real client, real renderer: fake SkyBlock items get looks applied, then
 * screenshots of the player model, hand, inventory and studio are written to
 * run/screenshots for inspection.
 */
public class SkyCosmeticsClientTest implements FabricClientGameTest {
    private static final String HEAD_SKIN = "NECRON_DIAMOND_KNIGHT_AURORA";
    private static final String SWORD_SKIN = "WITHER_GOGGLES_CELESTIAL";
    private static final String PET_SKIN = "PET_SKIN_GOLDEN_DRAGON_ANCIENT_BLUE";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.waitFor(mc -> !Repo.get().skins.isEmpty(), 20 * 120);
        ctx.runOnClient(mc -> {
            var c = Repo.get();
            System.out.println("[SkyCosmeticsTest] catalog: skins=" + c.skins.size() + " skinTab=" + c.skinTab.size()
                + " animated=" + c.animatedTab.size() + " pets=" + c.petSkinTab.size() + " heads=" + c.headTab.size()
                + " dyes=" + c.dyes.size());
            for (String id : List.of(HEAD_SKIN, SWORD_SKIN, PET_SKIN, "NECRON_DIAMOND_KNIGHT")) {
                SkinEntry e = c.skin(id);
                System.out.println("[SkyCosmeticsTest] " + id + " -> " + (e == null ? "MISSING"
                    : e.name + " frames=" + e.textures.length + " parent=" + e.parent));
            }
            System.out.println("[SkyCosmeticsTest] DYE_AURORA -> " + c.dye("DYE_AURORA").name + " colors=" + c.dye("DYE_AURORA").colors.length);
        });
        ctx.runOnClient(mc -> checkRepo());
        ctx.runOnClient(mc -> DyeWidgetsTest.checkData());
        ctx.runOnClient(mc -> StudioLayoutTest.checkData());
        ctx.runOnClient(mc -> NameGlintTest.checkNames());

        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientLevel().waitForChunksRender();
            sp.getServer().runCommand("time set noon");
            sp.getServer().runCommand("weather clear");
            sp.getServer().runCommand("item replace entity @a armor.head with minecraft:player_head[minecraft:custom_data={id:\"DIAMOND_NECRON_HEAD\",uuid:\"t-head\"}]");
            sp.getServer().runCommand("item replace entity @a armor.chest with minecraft:leather_chestplate[minecraft:custom_data={id:\"NECRON_CHESTPLATE\",uuid:\"t-chest\"}]");
            sp.getServer().runCommand("item replace entity @a armor.legs with minecraft:leather_leggings[minecraft:custom_data={id:\"NECRON_LEGGINGS\",uuid:\"t-legs\"}]");
            sp.getServer().runCommand("item replace entity @a armor.feet with minecraft:leather_boots[minecraft:custom_data={id:\"NECRON_BOOTS\",uuid:\"t-boots\"}]");
            sp.getServer().runCommand("item replace entity @a hotbar.0 with minecraft:diamond_sword[minecraft:custom_data={id:\"HYPERION\",uuid:\"t-sword\"}]");
            sp.getServer().runCommand("item replace entity @a hotbar.1 with minecraft:player_head[minecraft:custom_data={id:\"PET\",petInfo:'{\"type\":\"GOLDEN_DRAGON\",\"tier\":\"LEGENDARY\",\"uuid\":\"t-pet\"}'}]");
            sp.getServer().runCommand("item replace entity @a hotbar.2 with minecraft:leather_chestplate[minecraft:custom_data={id:\"NECRON_CHESTPLATE\",uuid:\"someone-elses\"}]");
            sp.getServer().runCommand("item replace entity @a hotbar.3 with minecraft:player_head[minecraft:custom_data={id:\"WITHER_GOGGLES\",uuid:\"t-recomb\"},minecraft:lore=[\"a LEGENDARY DUNGEON HELMET a\"]]");
            sp.getServer().runCommand("item replace entity @a hotbar.4 with minecraft:iron_chestplate[minecraft:custom_data={id:\"HELIANTHUS_CHESTPLATE\",uuid:\"t-iron\"}]");
            ctx.waitTicks(5);
            ctx.runOnClient(mc -> checkCommands());

            ctx.runOnClient(mc -> {
                Looks.put(false, "t-head", new Looks.Look(HEAD_SKIN, null, null, null, "test head"));
                Looks.put(true, "NECRON_CHESTPLATE", new Looks.Look(null, "DYE_AURORA", null, null, "all chests"));
                Looks.put(true, "NECRON_LEGGINGS", new Looks.Look(null, "DYE_AURORA", null, null, "all legs"));
                Looks.put(false, "t-boots", new Looks.Look(null, "DYE_AURORA", null, null, "boots"));
                Looks.put(false, "t-sword", new Looks.Look(SWORD_SKIN, null, null, null, "sword"));
                Looks.put(true, "PET:GOLDEN_DRAGON", new Looks.Look(PET_SKIN, null, null, null, "gdrag"));
                Looks.put(false, "t-iron", new Looks.Look(null, "DYE_WARDEN", null, null, "iron chest"));
                Looks.put(false, "t-chest", new Looks.Look(null, null, "&6Golden &lTunic", "off", "renamed chest"));
                mc.player.getInventory().setSelectedSlot(0);
            });

            // Wait for every frame of every skin used to finish downloading.
            ctx.waitFor(mc -> {
                boolean all = true;
                for (String id : List.of(HEAD_SKIN, SWORD_SKIN, PET_SKIN)) {
                    for (String t : Repo.get().skin(id).textures) all &= Textures.ready(t);
                }
                return all;
            }, 20 * 60);
            ctx.waitTicks(10);

            ctx.waitTicks(15);
            String renamed = ctx.computeOnClient(mc -> mc.player.getItemBySlot(EquipmentSlot.CHEST).getStyledHoverName().getString());
            String realName = ctx.computeOnClient(mc -> mc.player.getItemBySlot(EquipmentSlot.CHEST).getHoverName().getString());
            System.out.println("[SkyCosmeticsTest] renamed chest shows '" + renamed + "', mods still read '" + realName + "'");
            if (!renamed.equals("Golden Tunic") || renamed.equals(realName)) throw new AssertionError("rename not applied to display name only");
            boolean ironDyed = ctx.computeOnClient(mc -> {
                ItemStack shown = io.github.terabold.skycosmetics.Cosmetics.apply(mc.player.getInventory().getItem(4), true);
                return shown.is(net.minecraft.world.item.Items.LEATHER_CHESTPLATE) && shown.has(net.minecraft.core.component.DataComponents.DYED_COLOR);
            });
            System.out.println("[SkyCosmeticsTest] iron chestplate drawn as dyed leather: " + ironDyed);
            if (!ironDyed) throw new AssertionError("dye on iron armor not shown");
            boolean recomb = ctx.computeOnClient(mc -> io.github.terabold.skycosmetics.items.OwnedItems.list().stream()
                .anyMatch(o -> o.uuid.equals("t-recomb") && o.category == io.github.terabold.skycosmetics.items.OwnedItems.Category.HELMET));
            System.out.println("[SkyCosmeticsTest] recombobulated helmet in My Items: " + recomb);
            if (!recomb) throw new AssertionError("recombobulated helmet missing from My Items");
            ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
            ctx.waitTicks(10);
            ctx.takeScreenshot("skycosmetics-01-model-front");
            ctx.waitTicks(9);
            ctx.takeScreenshot("skycosmetics-02-model-front-later");

            ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
            ctx.waitTicks(5);
            ctx.takeScreenshot("skycosmetics-03-first-person-hotbar");

            ctx.setScreen(() -> new InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
            ctx.waitTicks(5);
            ctx.takeScreenshot("skycosmetics-04-inventory");

            // K over the helmet slot must open the studio for that item.
            double[] pos = ctx.computeOnClient(mc -> {
                InventoryScreen inv = (InventoryScreen) mc.screen;
                Slot helmet = inv.getMenu().slots.get(5);
                int left = (inv.width - 176) / 2, top = (inv.height - 166) / 2;
                double scale = mc.getWindow().getGuiScale();
                return new double[]{(left + helmet.x + 8) * scale, (top + helmet.y + 8) * scale};
            });
            ctx.getInput().setCursorPos(pos[0], pos[1]);
            ctx.waitTicks(2);
            // The open key ships unbound; bind it like a player would in Controls.
            ctx.runOnClient(mc -> {
                KeyMapping open = KeyMapping.get("key.skycosmetics.open");
                open.setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(org.lwjgl.glfw.GLFW.GLFW_KEY_K));
                KeyMapping.resetMapping();
            });
            KeyMapping k = ctx.computeOnClient(mc -> KeyMapping.get("key.skycosmetics.open"));
            recipeSearch(ctx, k);
            ctx.getInput().setCursorPos(pos[0], pos[1]);
            ctx.waitTicks(2);
            ctx.getInput().pressKey(k);
            ctx.waitForScreen(StudioScreen.class);
            ctx.runOnClient(mc -> mc.getToastManager().clear()); // recipe and advancement toasts hide the tabs
            ctx.waitTicks(5);
            ctx.takeScreenshot("skycosmetics-05-studio-from-K");
            StudioLayoutTest.checkOpenedOnHelmet(ctx);

            ctx.clickScreenButton("Dyes");
            ctx.waitTicks(3);
            ctx.takeScreenshot("skycosmetics-06-studio-dyes");

            ctx.clickScreenButton("Skins");
            ctx.waitTicks(2);
            ctx.getInput().typeChars("knight");
            ctx.waitTicks(40);
            ctx.takeScreenshot("skycosmetics-07-studio-search-knight");

            ctx.clickScreenButton("Saved");
            ctx.waitTicks(10);
            ctx.takeScreenshot("skycosmetics-08-studio-saved");

            ctx.clickScreenButton("Done");
            ctx.waitTicks(3);
            ctx.setScreen(() -> null);

            // The brush in the inventory opens the studio on your gear.
            ctx.setScreen(() -> new InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
            ctx.waitTicks(5);
            ctx.takeScreenshot("skycosmetics-08c-inventory-brush");
            // The helmet slot's tooltip opens over the brush: the brush must stay under it.
            double[] helmet = ctx.computeOnClient(mc -> {
                var a = (io.github.terabold.skycosmetics.mixin.AbstractContainerScreenAccessor) mc.screen;
                double scale = mc.getWindow().getGuiScale();
                return new double[]{(a.skycosmetics$leftPos() + 14) * scale, (a.skycosmetics$topPos() + 20) * scale};
            });
            ctx.getInput().setCursorPos(helmet[0], helmet[1]);
            ctx.waitTicks(3);
            ctx.takeScreenshot("skycosmetics-08c2-brush-under-tooltip");
            double[] brush = ctx.computeOnClient(mc -> {
                var a = (io.github.terabold.skycosmetics.mixin.AbstractContainerScreenAccessor) mc.screen;
                double scale = mc.getWindow().getGuiScale();
                return new double[]{(a.skycosmetics$leftPos() + 32) * scale, (a.skycosmetics$topPos() + 14) * scale};
            });
            ctx.getInput().setCursorPos(brush[0], brush[1]);
            ctx.waitTicks(2);
            ctx.getInput().pressMouse(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT);
            ctx.waitForScreen(StudioScreen.class);
            ctx.waitTicks(10);
            ctx.takeScreenshot("skycosmetics-08d-studio-from-brush");
            ctx.clickScreenButton("Dyes");
            ctx.waitTicks(20);
            ctx.takeScreenshot("skycosmetics-08e-studio-dye-icons");
            ctx.clickScreenButton("Custom dye...");
            ctx.waitTicks(5);
            ctx.takeScreenshot("skycosmetics-08e2-studio-custom-dye");
            int[] animated = ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).popup().toggleAt(true));
            double guiScale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
            ctx.getInput().setCursorPos(animated[0] * guiScale, animated[1] * guiScale);
            ctx.waitTicks(1);
            ctx.getInput().pressMouse(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT);
            ctx.waitTicks(10);
            ctx.takeScreenshot("skycosmetics-08e3-studio-custom-dye-animated");
            ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE);
            ctx.waitTicks(2);
            if (!ctx.tryClickScreenButton("Name & Glint")) ctx.clickScreenButton("Name");
            ctx.waitTicks(5);
            ctx.takeScreenshot("skycosmetics-08f-studio-name-glint");
            // Different screen sizes: the layout must adapt, never overlap or crash.
            for (int scale : new int[]{1, 3, 4}) {
                ctx.runOnClient(mc -> {
                    mc.options.guiScale().set(scale);
                    mc.resizeGui();
                });
                ctx.waitTicks(5);
                ctx.takeScreenshot("skycosmetics-08g-studio-guiscale-" + scale);
            }
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(0);
                mc.resizeGui();
            });
            ctx.waitTicks(3);
            ctx.clickScreenButton("Done");
            ctx.waitTicks(3);
            ctx.setScreen(() -> null);
            StudioLayoutTest.run(ctx, sp);
            NameGlintTest.run(ctx, sp);
            TypeLooksTest.run(ctx, sp);

            // Taking the look off must restore Hypixel's item instantly.
            ctx.runOnClient(mc -> Looks.put(false, "t-head", null));
            ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
            ctx.waitTicks(5);
            ctx.takeScreenshot("skycosmetics-09-head-look-removed");
            ctx.runOnClient(mc -> System.out.println("[SkyCosmeticsTest] head item now: "
                + mc.player.getItemBySlot(EquipmentSlot.HEAD)));

            DyeWidgetsTest.checkOnArmour(ctx, sp);
            DyeWidgetsTest.run(ctx);

            PetWorldTest.run(ctx, sp);
            OrbWorldTest.run(ctx, sp);
        }
    }

    /**
     * Only /skycosmetics (the settings), /skycosmetics edit (the studio) and /skycosmetics debug pet|orb. Every node
     * runs something, so no partial "/skycosmetics ..." is handed to the server as an unknown command.
     */
    private static void checkCommands() {
        CommandNode<?> root = ClientCommands.getActiveDispatcher().getRoot().getChild("skycosmetics");
        check(root != null && names(root).equals(List.of("debug", "edit")), "/skycosmetics has only debug and edit under it: "
            + (root == null ? null : names(root)));
        check(names(root.getChild("debug")).equals(List.of("orb", "pet")), "debug has pet and orb");
        List<CommandNode<?>> todo = new ArrayList<>(List.of(root));
        while (!todo.isEmpty()) {
            CommandNode<?> n = todo.removeLast();
            check(n.getCommand() != null, "'" + n.getName() + "' runs locally on its own");
            todo.addAll(n.getChildren());
        }
        System.out.println("[SkyCosmeticsTest] command checks passed");
    }

    private static List<String> names(CommandNode<?> n) {
        return n.getChildren().stream().map(CommandNode::getName).sorted().toList();
    }

    /** The open key types into the recipe book's search (Skyblocker's item list) instead of opening the studio. */
    private static void recipeSearch(ClientGameTestContext ctx, KeyMapping k) {
        ctx.runOnClient(mc -> {
            ((RecipeBookScreenAccessor) mc.screen).skycosmetics$recipeBook().toggleVisibility();
            mc.screen.resize(mc.screen.width, mc.screen.height);
        });
        ctx.waitTicks(2);
        double[] helmet = ctx.computeOnClient(mc -> {
            var a = (AbstractContainerScreenAccessor) mc.screen;
            Slot slot = ((InventoryScreen) mc.screen).getMenu().slots.get(5);
            double scale = mc.getWindow().getGuiScale();
            return new double[]{(a.skycosmetics$leftPos() + slot.x + 8) * scale, (a.skycosmetics$topPos() + slot.y + 8) * scale};
        });
        ctx.getInput().setCursorPos(helmet[0], helmet[1]);
        ctx.runOnClient(mc -> search(mc).setFocused(true));
        ctx.waitTicks(2);
        ctx.getInput().pressKey(k);
        ctx.waitTicks(3);
        ctx.takeScreenshot("skycosmetics-04b-recipe-search-typing");
        ctx.runOnClient(mc -> {
            check(mc.screen instanceof InventoryScreen, "typing in the recipe book search does not open the studio");
            EditBox search = search(mc);
            search.setValue("");
            search.setFocused(false);
            ((RecipeBookScreenAccessor) mc.screen).skycosmetics$recipeBook().toggleVisibility();
            mc.screen.resize(mc.screen.width, mc.screen.height);
        });
        ctx.waitTicks(2);
    }

    private static EditBox search(Minecraft mc) {
        return ((RecipeBookComponentAccessor) ((RecipeBookScreenAccessor) mc.screen).skycosmetics$recipeBook()).skycosmetics$searchBox();
    }

    /** Load time, skin classification, captured.json merge rules and preview timing. */
    private static void checkRepo() {
        Catalog c = Repo.get();
        long ms = Repo.loadMillis();
        System.out.println("[SkyCosmeticsTest] repo " + c.source + " parsed in " + ms + " ms, items=" + c.items);
        check(ms >= 0 && ms < 15000, "repo parse took " + ms + " ms");

        SkinEntry beach = c.skin("BEACH_BALL_FLUX");
        check(beach != null && beach.kind == SkinEntry.Kind.SKIN && beach.missingFrames, "BEACH_BALL_FLUX is a skin without frames");
        SkinEntry rune = c.skin("ANTLERS_RUNE;3");
        check(rune == null || rune.kind == SkinEntry.Kind.HEAD, "runes are not skins");

        String t1 = c.skin(HEAD_SKIN).textures[0];
        String t2 = c.skin(SWORD_SKIN).textures[0];
        JsonObject root = new JsonObject();
        JsonObject skins = new JsonObject();
        skins.add("SKYCOSMETICS_TEST_SKIN", still("Test Skin", t1));
        skins.add("NECRON_DIAMOND_KNIGHT", still("Impostor Skin", t2));
        root.add("skins", skins);
        JsonObject anims = new JsonObject();
        anims.add("BEACH_BALL_FLUX", anim(4, t1, t2));
        anims.add(HEAD_SKIN, anim(1, t2, t1));
        root.add("animated", anims);

        Catalog m = Repo.withLearned(root.toString());
        SkinEntry learned = m.skin("SKYCOSMETICS_TEST_SKIN");
        check(learned != null && learned.learned && m.skinTab.contains(learned), "a learned still is listed");
        check(m.skin("NECRON_DIAMOND_KNIGHT").name.equals(c.skin("NECRON_DIAMOND_KNIGHT").name), "the repo wins over a learned still");
        SkinEntry filled = m.skin("BEACH_BALL_FLUX");
        check(filled.animated() && filled.learned && filled.frameTicks[0] == 4, "a learned animation fills a repo gap");
        check(m.skin(HEAD_SKIN).textures[0].equals(c.skin(HEAD_SKIN).textures[0]) && !m.skin(HEAD_SKIN).learned,
            "the repo wins over a learned animation");
        check(m.learned == 2 && Repo.get().skin("SKYCOSMETICS_TEST_SKIN") == null, "merge counts and leaves the live catalog alone");

        // Spawn frame S, then A B C held 2, 2 and 6 ticks, seen twice.
        PreviewRecorder.Fit f = PreviewRecorder.fit(new long[]{0, 3, 5, 7, 13, 15, 17, 23},
            List.of("S", "A", "B", "C", "A", "B", "C", "A"), true);
        check(f != null && f.frames() == 3 && f.start() == 1 && Arrays.equals(f.ticksPerTexture(), new int[]{2, 2, 6}),
            "blinking preview timed per frame: " + f);
        // A B every 5 ticks with one tick of jitter is still uniform.
        f = PreviewRecorder.fit(new long[]{0, 1, 7, 11, 17, 21}, List.of("S", "A", "B", "A", "B", "A"), true);
        check(f != null && f.frames() == 2 && f.ticks() == 5 && f.ticksPerTexture() == null, "uniform preview: " + f);
        check(PreviewRecorder.fit(new long[]{0, 1, 2, 3}, List.of("S", "A", "B", "A"), true) == null, "one cycle is not enough");
        System.out.println("[SkyCosmeticsTest] repo checks passed");
    }

    private static JsonObject still(String name, String texture) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("color", "#FF55FF");
        o.addProperty("kind", "SKIN");
        o.addProperty("texture", texture);
        return o;
    }

    private static JsonObject anim(int ticks, String... textures) {
        JsonObject o = new JsonObject();
        o.addProperty("ticks", ticks);
        JsonArray a = new JsonArray();
        for (String t : textures) a.add("00000000-0000-0000-0000-000000000000:" + t);
        o.add("textures", a);
        return o;
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("[SkyCosmeticsTest] failed: " + what);
    }
}
