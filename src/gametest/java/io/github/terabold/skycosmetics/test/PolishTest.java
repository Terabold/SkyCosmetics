package io.github.terabold.skycosmetics.test;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import io.github.terabold.skycosmetics.*;
import io.github.terabold.skycosmetics.compat.OtherLooks;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.gui.StudioScreen;
import io.github.terabold.skycosmetics.gui.hub.SearchBox;
import io.github.terabold.skycosmetics.items.OwnedItems;
import io.github.terabold.skycosmetics.render.*;
import io.github.terabold.skycosmetics.test.fake.FakeSkyblocker;
import io.github.terabold.skycosmetics.test.fake.FakeSkyOcean;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import static io.github.terabold.skycosmetics.test.StudioLayoutTest.check;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.window;

/** Real studio selection, trim copies and GPU glints; not only settings serialization. */
public class PolishTest implements FabricClientGameTest {
    private static final String UUID = "cf9e76b0-0722-4cdb-9334-00000000000";

    @Override public void runTest(ClientGameTestContext ctx) {
        ctx.waitFor(mc -> !Repo.get().skins.isEmpty(), 2400);
        JsonObject settings = ctx.computeOnClient(mc -> Chroma.write());
        double glintSpeed = ctx.computeOnClient(mc -> mc.options.glintSpeed().get());
        try (var world = ctx.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            String[] items = {"player_head", "player_head", "leather_boots", "leather_chestplate", "player_head", "diamond_sword"};
            String[] types = {"POLISH_HELMET", "POLISH_OTHER_HELMET", "POLISH_BOOTS", "POLISH_CHESTPLATE", "PET", "POLISH_SWORD"};
            for (int i = 0; i < items.length; i++) {
                String pet = i == 4 ? ",petInfo:'{\"type\":\"BEE\",\"tier\":\"LEGENDARY\",\"uuid\":\"" + UUID + i + "\"}'" : "";
                world.getServer().runCommand("item replace entity @a hotbar." + i + " with minecraft:" + items[i]
                    + "[minecraft:custom_data={id:\"" + types[i] + "\",uuid:\"" + UUID + i + "\"" + pet + "},minecraft:lore=[\"LEGENDARY "
                    + (i < 2 ? "HELMET" : i == 2 ? "BOOTS" : i == 3 ? "CHESTPLATE" : "SWORD") + "\"]]");
            }
            ctx.waitTicks(12);
            window(ctx, 1280, 720, 2);
            ctx.setScreen(() -> new StudioScreen(null, ItemStack.EMPTY));
            ctx.waitTicks(3);
            ctx.runOnClient(mc -> { studio(mc).pick("inv:0"); search(mc).setValue("knight"); studio(mc).pick("inv:1");
                check(search(mc).getValue().equals("knight"), "helmet-to-helmet keeps the skin search");
                studio(mc).pick("inv:2"); check(search(mc).getValue().isEmpty(), "switching to dyes clears the skin query");
                search(mc).setValue("aurora"); studio(mc).pick("inv:3");
                check(search(mc).getValue().equals("aurora"), "boots-to-chestplate keeps the dye search");
                viewToolbar(mc);
                studio(mc).pick("inv:4"); check(search(mc).getValue().isEmpty(), "switching to pets clears the dye query");
                studio(mc).pick("inv:0"); search(mc).setValue("knight");
                viewToolbar(mc);
            });
            ctx.getInput().setCursorPos(0, 0); ctx.waitTicks(12);
            ctx.takeScreenshot("skycosmetics-110-names-grid");
            ctx.clickScreenButton("View: Grid"); ctx.getInput().setCursorPos(0, 0); ctx.waitTicks(3);
            ctx.takeScreenshot("skycosmetics-111-names-list");
            ctx.runOnClient(mc -> { studio(mc).pick("inv:4"); search(mc).setValue("animated"); });
            ctx.waitTicks(8); ctx.takeScreenshot("skycosmetics-112-gif-badge-list");
            ctx.clickScreenButton("View: List"); ctx.getInput().setCursorPos(0, 0); ctx.waitTicks(3);
            ctx.takeScreenshot("skycosmetics-113-gif-badge-grid");
            trims(ctx);
            compatibility(ctx);
            chroma(ctx);
        } finally {
            ctx.setScreen(() -> null);
            ctx.runOnClient(mc -> {
                Chroma.read(settings); mc.options.glintSpeed().set(glintSpeed);
                for (int i = 0; i < 7; i++) { Looks.put(false, UUID + i, null); OwnedItems.forget(UUID + i); }
                FakeSkyblocker.reset(cfg -> {}); Settings.save();
            });
        }
        System.out.println("[SkyCosmeticsTest] studio polish, trim and chroma checks passed");
    }

    private static StudioScreen studio(Minecraft mc) { return (StudioScreen) mc.screen; }
    private static void viewToolbar(Minecraft mc) {
        SearchBox search = search(mc);
        var view = (net.minecraft.client.gui.components.AbstractWidget) studio(mc).children().stream()
            .filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w
                && w.getMessage().getString().startsWith("View: ")).findFirst().orElseThrow();
        check(view.getY() == search.getY() && view.getX() >= search.getX() + search.getWidth() + 4,
            "view switch shares the search toolbar without overlapping it");
        studio(mc).children().stream().filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w
            && w.getMessage().getString().equals("Custom Dye…")).forEach(c -> {
                var custom = (net.minecraft.client.gui.components.AbstractWidget) c;
                check(custom.getY() != view.getY() || custom.getX() + custom.getWidth() + 4 <= view.getX(),
                    "custom dye does not overlap the view switch");
            });
    }
    private static SearchBox search(Minecraft mc) {
        return (SearchBox) studio(mc).children().stream().filter(c -> c instanceof SearchBox b && b.getMessage().getString().equals("Search")).findFirst().orElseThrow();
    }

    private static void trims(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            ItemStack original = mc.player.getInventory().getItem(2);
            check(original.get(DataComponents.TRIM) == null, "the server boots have no trim");
            Looks.put(false, UUID + 2, Looks.Look.NONE.withTrim("minecraft:sentry|minecraft:gold"));
            ItemStack copy = Cosmetics.apply(original, true);
            check(copy != original && copy.get(DataComponents.TRIM) != null && original.get(DataComponents.TRIM) == null,
                "trims affect only the cached render copy");
            check(Cosmetics.apply(original, true) == copy, "an unchanged trim reuses the copy");
            Looks.put(false, UUID + 2, Looks.Look.NONE.withTrim("off"));
            check(Cosmetics.apply(original, true).get(DataComponents.TRIM) == null, "No Trim removes the render-copy component");
            check(Trims.valid("bad|bad|bad") == null, "invalid trim syntax is ignored");
            studio(mc).pick("inv:2");
        });
        ctx.clickScreenButton("Model & Trims"); ctx.waitTicks(3);
        ctx.clickScreenButton("Armor Trims"); ctx.waitForScreen(StudioScreen.class); ctx.waitTicks(3);
        ctx.clickScreenButton("Eye Armor Trim"); ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> Looks.byUuid(UUID + 2).trim().equals("minecraft:eye|minecraft:gold")), "the actual pattern picker applies its selection");
        ctx.takeScreenshot("skycosmetics-114-trim-editor");
        ctx.runOnClient(mc -> {
            Looks.put(false, UUID + 2, Looks.byUuid(UUID + 2).withDye("#33CCFF"));
            studio(mc).pick("inv:3"); studio(mc).pick("inv:2");
        }); ctx.waitTicks(3);
        ctx.takeScreenshot("skycosmetics-trims-dyed-boots");
        ctx.runOnClient(mc -> studio(mc).pick("inv:3")); ctx.waitTicks(3);
        ctx.takeScreenshot("skycosmetics-trims-chestplate");
        var originalHead = ctx.computeOnClient(mc -> mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD));
        try {
            ctx.runOnClient(mc -> {
                var helmet = new ItemStack(net.minecraft.world.item.Items.LEATHER_HELMET);
                var tag = new net.minecraft.nbt.CompoundTag(); tag.putString("id", "POLISH_ARMOR_HELMET"); tag.putString("uuid", UUID + 6);
                helmet.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
                helmet.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(Component.literal("LEGENDARY HELMET"))));
                helmet.set(DataComponents.DYED_COLOR, new net.minecraft.world.item.component.DyedItemColor(0xAADD33));
                mc.player.getInventory().setItem(6, helmet);
                mc.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(net.minecraft.world.item.Items.PLAYER_HEAD));
                var state = (net.minecraft.client.renderer.entity.state.AvatarRenderState)mc.getEntityRenderDispatcher()
                    .getRenderer(mc.player).createRenderState(mc.player, 1);
                check(state.wornHeadType != null, "the preview fixture has a separate native skull layer");
            });
            ctx.waitFor(mc -> studio(mc).listedRows().contains("inv:6"), 200);
            ctx.runOnClient(mc -> studio(mc).pick("inv:6")); ctx.waitTicks(3);
            ctx.takeScreenshot("skycosmetics-trims-helmet-over-worn-skull");
            check(ctx.computeOnClient(mc -> mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
                .is(net.minecraft.world.item.Items.PLAYER_HEAD)), "helmet previews never replace the actual worn skull");
        } finally { ctx.runOnClient(mc -> mc.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, originalHead)); }
        ctx.setScreen(() -> null);
        ctx.runOnClient(mc -> {
            Looks.put(false, UUID + 2, Looks.Look.NONE.withTrim("minecraft:eye|minecraft:diamond"));
        });
        ctx.waitFor(mc -> {
            try {
                return java.nio.file.Files.readString(mc.gameDirectory.toPath().resolve("config/skycosmetics/looks.json"))
                    .contains("minecraft:eye|minecraft:diamond");
            } catch (java.io.IOException e) { return false; }
        });
        ctx.runOnClient(mc -> {
            Looks.load();
            check(Looks.byUuid(UUID + 2).trim().equals("minecraft:eye|minecraft:diamond"), "trim selection survives save/load");
            check(Looks.byUuid(UUID + 2).withName("Example").trim().equals("minecraft:eye|minecraft:diamond"), "editing a name preserves its trim");
        });
    }

    private static void compatibility(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            FakeSkyblocker.reset(cfg -> {});
            FakeSkyblocker.update(cfg -> {
                var g = cfg.general;
                g.customItemNames.put(UUID + 2, Component.literal("Imported boots"));
                g.customDyeColors.put(UUID + 2, 0x33CC77);
                g.customArmorTrims.put(UUID + 2, new FakeSkyblocker.ArmorTrimId(Identifier.parse("minecraft:gold"), Identifier.parse("minecraft:sentry")));
                g.customGlint.put(UUID + 2, true);
                g.customHelmetTextures.put(UUID + 2, Repo.get().skin("NECRON_DIAMOND_KNIGHT").textures[0]);
                g.customItemModel.put(UUID + 2, Identifier.parse("minecraft:stone_sword"));
            });
            var source = OtherLooks.skyblocker(FakeSkyblocker.class.getName(), mc.gameDirectory.toPath().resolve("absent-config.json"));
            var changes = source.read();
            EnumSet<OtherLooks.Kind> kinds = EnumSet.noneOf(OtherLooks.Kind.class);
            for (var change : changes) {
                kinds.add(change.kind());
                check(source.remove(change), "remove a live " + change.kind() + " through its source");
                check(source.read().stream().noneMatch(c -> c.kind() == change.kind()), "only that feature is gone");
                check(source.restore(change), "restore the live " + change.kind());
                if (change.kind() == OtherLooks.Kind.TRIM) check(change.into().apply(Looks.Look.NONE).trim().equals("minecraft:sentry|minecraft:gold"), "trim migration retains pattern and material");
            }
            check(kinds.equals(EnumSet.allOf(OtherLooks.Kind.class)), "all six supported cosmetic kinds are detected");
            Object before = source.stamp();
            FakeSkyblocker.get().general.customItemNames.put(UUID + 2, Component.literal("Changed without resizing the map"));
            check(!before.equals(source.stamp()), "same-size live edits invalidate detection");
            FakeSkyOcean.reset();
            var data = FakeSkyOcean.of(new FakeSkyOcean.UuidKey(java.util.UUID.fromString(UUID + 3)))
                .with(FakeSkyOcean.TRIM, new FakeSkyOcean.ArmorTrim(Identifier.parse("minecraft:diamond"), Identifier.parse("minecraft:eye")));
            var ocean = OtherLooks.skyOcean(FakeSkyOcean.class.getName(), mc.gameDirectory.toPath().resolve("absent-ocean.json"));
            var trim = ocean.read().stream().filter(c -> c.kind() == OtherLooks.Kind.TRIM).findFirst().orElseThrow();
            check(trim.into().apply(Looks.Look.NONE).trim().equals("minecraft:eye|minecraft:diamond"), "SkyOcean trim migration keeps both ids");
            check(ocean.remove(trim) && !data.getData().containsKey(FakeSkyOcean.TRIM) && ocean.restore(trim), "SkyOcean trim removal and undo use its live data");
            FakeSkyOcean.reset();
        });
    }

    private static void chroma(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            Chroma.custom = true; Chroma.count = 2; Chroma.colors[0] = Chroma.colors[1] = 0xFF2020; Chroma.changed();
            String raw = Names.chroma() + "Custom Chroma";
            long tick = Names.liveTick();
            var first = Names.live(raw);
            var second = Names.live(raw);
            check(first.getString().equals("Custom Chroma"), "live names preserve text");
            // Names uses wall-clock ticks: two calls can straddle a 50 ms boundary even on the client thread.
            if (Names.liveTick() == tick) check(second == first, "live names reuse a tick's result");
            check(Chroma.nameColor(0) == Chroma.nameColor(10), "a uniform custom palette has the requested color");
            Looks.put(false, UUID + 5, Looks.Look.NONE.withGlint("on").withGlintColor("chroma").withGlintStrength(2f));
            mc.options.glintSpeed().set(0.0);
        });
        ctx.setScreen(GlintSample::new); ctx.waitTicks(8);
        Path red = ctx.takeScreenshot("skycosmetics-115-chroma-glint-red");
        int textures = ctx.computeOnClient(mc -> Glints.stats()[4]), uploads = Glints.chromaUploads();
        ctx.waitTicks(10);
        check(Glints.chromaUploads() == uploads, "stable chroma animation does not upload textures");
        ctx.runOnClient(mc -> { Chroma.colors[0] = Chroma.colors[1] = 0x20FF20; Chroma.changed(); });
        ctx.waitTicks(3);
        Path green = ctx.takeScreenshot("skycosmetics-116-chroma-glint-green");
        check(ctx.computeOnClient(mc -> Glints.stats()[4]) == textures, "changing the palette does not create more glint mask textures");
        check(Glints.chromaUploads() == uploads + 1, "a palette edit uploads one small texture");
        try {
            check(redMinusGreen(red) > redMinusGreen(green) + 10000, "the GPU glint visibly uses the edited palette");
        } catch (java.io.IOException e) { throw new AssertionError(e); }
        ctx.runOnClient(mc -> { Chroma.custom = false; Chroma.wave = true; Chroma.changed(); mc.options.glintSpeed().set(0.5); });
        ctx.waitTicks(3); ctx.takeScreenshot("skycosmetics-117-chroma-glint-rainbow");
    }

    private static long redMinusGreen(Path screenshot) throws java.io.IOException {
        try (var in = java.nio.file.Files.newInputStream(screenshot); NativeImage image = NativeImage.read(in)) {
            long sum = 0;
            for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++)
                for (int x = image.getWidth() / 3; x < image.getWidth() * 2 / 3; x++) {
                    int c = image.getPixel(x, y); sum += (c >> 16 & 255) - (c >> 8 & 255);
                }
            return sum;
        }
    }

    private static final class GlintSample extends Screen {
        GlintSample() { super(Component.literal("Chroma glint test")); }
        @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
            g.fill(0, 0, width, height, 0xFF181818);
            g.pose().pushMatrix(); g.pose().translate(width / 2 - 64, height / 2 - 64); g.pose().scale(8, 8);
            g.item(minecraft.player.getInventory().getItem(5), 0, 0); g.pose().popMatrix();
        }
    }
}
