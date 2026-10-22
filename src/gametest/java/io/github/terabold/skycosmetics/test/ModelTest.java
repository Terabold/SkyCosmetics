package io.github.terabold.skycosmetics.test;

import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.gui.StudioScreen;
import io.github.terabold.skycosmetics.gui.hub.SearchBox;
import io.github.terabold.skycosmetics.items.OwnedItems;
import io.github.terabold.skycosmetics.render.ItemModels;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import java.util.List;

import static io.github.terabold.skycosmetics.test.StudioLayoutTest.check;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.window;

/** Tests model-only copies, inheritance, persistence and real picker interactions. */
public class ModelTest implements FabricClientGameTest {
    private static final String UUID = "a324d9cf-3de1-4d98-a27a-000000000001";
    private static final String[] IDS = {"AXE_OF_THE_SHREDDED", "DAEDALUS_AXE", "STARRED_DAEDALUS_AXE",
        "ENRAGER", "MERCENARY_AXE", "RAGNAROCK_AXE", "RAIDER_AXE"};

    private static void armor(net.minecraft.client.Minecraft mc) {
        var real = new ItemStack(net.minecraft.world.item.Items.LEATHER_LEGGINGS);
        var tag = new net.minecraft.nbt.CompoundTag(); tag.putString("id", "MODEL_TEST_LEGS"); tag.putString("uuid", "model-test-legs");
        real.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
        real.set(DataComponents.DYED_COLOR, new net.minecraft.world.item.component.DyedItemColor(0x3366FF));
        var before = real.copy();
        try {
            Looks.put(false, "model-test-legs", Looks.Look.NONE.withModel("minecraft:iron_leggings").withDye("#FF44AA")
                .withTrim("minecraft:eye|minecraft:gold"));
            var iron = Cosmetics.apply(real, true);
            check(iron.is(net.minecraft.world.item.Items.IRON_LEGGINGS) && iron.get(DataComponents.EQUIPPABLE).assetId()
                .equals(net.minecraft.world.item.Items.IRON_LEGGINGS.components().get(DataComponents.EQUIPPABLE).assetId()), "remodel changes inventory and worn armor material");
            check(iron.get(DataComponents.DYED_COLOR) == null && iron.get(DataComponents.TRIM) != null, "iron keeps its trim without an invalid leather tint");
            Looks.put(false, "model-test-legs", Looks.Look.NONE.withDye("#FF44AA").withTrim("minecraft:eye|minecraft:gold"));
            var leather = Cosmetics.apply(real, true);
            check(leather.is(net.minecraft.world.item.Items.LEATHER_LEGGINGS) && leather.get(DataComponents.DYED_COLOR).rgb() == 0xFF44AA
                && leather.get(DataComponents.TRIM) != null, "custom leather dye and trim coexist");
            Looks.put(false, "model-test-legs", Looks.Look.NONE.withTrim("minecraft:eye|minecraft:gold"));
            check(Cosmetics.apply(real, true).get(DataComponents.DYED_COLOR).rgb() == 0x3366FF, "server leather color survives trim editing");
            check(ItemStack.isSameItemSameComponents(real, before), "armor changes never touch the source stack");
        } finally { Looks.put(false, "model-test-legs", null); }
    }

    @Override public void runTest(ClientGameTestContext ctx) {
        try (var world = ctx.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            world.getServer().runCommand("item replace entity @a hotbar.0 with minecraft:diamond_sword"
                + "[minecraft:custom_data={id:\"AXE_OF_THE_SHREDDED\",uuid:\"" + UUID + "\"},"
                + "minecraft:item_model=\"minecraft:diamond_sword\",minecraft:lore=[\"LEGENDARY SWORD\"]]");
            ctx.waitTicks(8);
            ctx.runOnClient(mc -> {
                ItemStack real = mc.player.getInventory().getItem(0);
                for (String id : IDS) {
                    String axe = ItemModels.oldAxe(id);
                    check(axe != null && ItemModels.valid(axe) != null, "verified original model for " + id);
                }
                check(ItemModels.oldAxe("TREECAPITATOR_AXE") == null, "foraging axes are not remapped");
                check(ItemModels.valid("missing:item") == null && ItemModels.valid("minecraft:air") == null
                    && ItemModels.valid("minecraft:not_an_item") == null, "invalid or empty models are ignored");
                Looks.put(true, IDS[0], Looks.Look.NONE.withModel("minecraft:diamond_axe"));
                ItemStack shown = Cosmetics.apply(real, true);
                check(shown != real && shown.getItem() == real.getItem(), "model copy retains the actual sword item and behavior");
                check(shown.get(DataComponents.ITEM_MODEL).equals(Identifier.parse("minecraft:diamond_axe")), "old axe model applied");
                check(Cosmetics.apply(real, true) == shown, "unchanged model reuses the cached copy");
                check(Cosmetics.apply(real, false) == real, "type model obeys ownership restrictions");
                var checkCopy = shown.copy();
                checkCopy.set(DataComponents.ITEM_MODEL, real.get(DataComponents.ITEM_MODEL));
                check(ItemStack.isSameItemSameComponents(checkCopy, real), "all use, attack, lore and other components preserved");
                Looks.put(false, UUID, Looks.Look.NONE.withModel("original").withName("Example"));
                check(Cosmetics.apply(real, true).get(DataComponents.ITEM_MODEL).equals(real.get(DataComponents.ITEM_MODEL)),
                    "per-item Original blocks inherited remodel");
                Looks.put(false, UUID, Looks.byUuid(UUID).withModel(null));
                check(Cosmetics.apply(real, true).get(DataComponents.ITEM_MODEL).equals(Identifier.parse("minecraft:diamond_axe")),
                    "Inherit restores type-level model field independently of name");
                Looks.put(false, UUID, Looks.Look.NONE.withModel("minecraft:iron_axe"));
            });
            ctx.waitFor(mc -> {
                try { return java.nio.file.Files.readString(mc.gameDirectory.toPath().resolve("config/skycosmetics/looks.json"))
                    .contains("minecraft:iron_axe"); } catch (java.io.IOException e) { return false; }
            });
            ctx.runOnClient(mc -> {
                Looks.load();
                check(Looks.byUuid(UUID).model().equals("minecraft:iron_axe"), "model survives save/load");
                check(Looks.byUuid(UUID).withTrim("off").withName("Test").model().equals("minecraft:iron_axe"),
                    "editing other fields preserves model");
            });
            window(ctx, 1280, 720, 2);
            ctx.setScreen(() -> new StudioScreen(null, ItemStack.EMPTY)); ctx.waitTicks(3);
            ctx.runOnClient(mc -> ((StudioScreen) mc.screen).pick("inv:0"));
            ctx.clickScreenButton("Model");
            ctx.waitTicks(3);
            check(ctx.computeOnClient(mc -> mc.screen instanceof StudioScreen), "model controls stay inside Studio");
            ctx.clickScreenButton("Original axe");
            check(ctx.computeOnClient(mc -> Looks.byUuid(UUID).model().equals("minecraft:diamond_axe")), "original axe shortcut saves correct material");
            ctx.takeScreenshot("skycosmetics-140-original-axe");
            ctx.runOnClient(mc -> mc.screen.children().stream().filter(SearchBox.class::isInstance).map(SearchBox.class::cast)
                .filter(b -> b.getMessage().getString().equals("Search models")).findFirst().orElseThrow().setValue("netherite sword"));
            ctx.waitTicks(3); ctx.takeScreenshot("skycosmetics-141-model-search");
            ctx.clickScreenButton("Netherite Sword"); ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> Looks.byUuid(UUID).model().equals("minecraft:netherite_sword")), "icon search selects a vanilla model");
            ctx.clickScreenButton("Reset model");
            check(ctx.computeOnClient(mc -> Looks.byUuid(UUID).model().equals("original")), "Original is an explicit override");
            window(ctx, 1050, 720, 3);
            ctx.waitTicks(4);
            ctx.runOnClient(mc -> {
                for (var child : mc.screen.children()) if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget.visible)
                    check(widget.getX() >= 0 && widget.getY() >= 0 && widget.getRight() <= mc.screen.width
                        && widget.getBottom() <= mc.screen.height, "picker controls fit the minimum GUI viewport");
            });
            ctx.takeScreenshot("skycosmetics-142-model-small-window");
            ctx.clickScreenButton("Use shared model");
            check(ctx.computeOnClient(mc -> Looks.byUuid(UUID) == null), "reset removes a model-only saved look");
            ctx.runOnClient(mc -> armor(mc));
            window(ctx,1280,720,2);
            ctx.takeScreenshot("skycosmetics-model-inline");
            ctx.runOnClient(mc -> {
                for (int i = 1; i < 36; i++) {
                    var stack = new ItemStack(net.minecraft.world.item.Items.LEATHER_BOOTS);
                    var data = new net.minecraft.nbt.CompoundTag(); data.putString("id", "MODEL_SCROLL_TEST"); data.putString("uuid", "scroll-test-" + i);
                    stack.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(data));
                    stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(net.minecraft.network.chat.Component.literal("LEGENDARY BOOTS"))));
                    mc.player.getInventory().setItem(i, stack);
                }
            });
            ctx.setScreen(() -> new StudioScreen(null, ItemStack.EMPTY)); ctx.waitTicks(3);
            ctx.runOnClient(mc -> {
                var studio = (StudioScreen)mc.screen; var bar = studio.itemScrollbar();
                check(bar[4] > 0, "item list overflows for a real scrollbar test");
                var press = new net.minecraft.client.input.MouseButtonEvent(bar[0],bar[1] + 2,
                    new net.minecraft.client.input.MouseButtonInfo(0,0));
                check(studio.mouseClicked(press,false), "the visible item scrollbar accepts a press");
                var drag = new net.minecraft.client.input.MouseButtonEvent(bar[0],bar[2] - 2,
                    new net.minecraft.client.input.MouseButtonInfo(0,0));
                check(studio.mouseDragged(drag,0,bar[2]-bar[1]-4), "dragging scrolls the list");
                check(studio.itemScrollbar()[3] == bar[4], "drag to the bottom reaches the final item");
                check(studio.mouseReleased(drag) && !studio.isDragging(), "release ends scrollbar capture");
                check(!io.github.terabold.skycosmetics.tooltip.TooltipRenderer.wheel(studio,bar[0],bar[1]+10,-1,false),
                    "Studio's list keeps the wheel even when its hover tooltip is visible");
            });
            ctx.takeScreenshot("skycosmetics-studio-scrollbar-dragged");
        } finally {
            ctx.setScreen(() -> null);
            ctx.runOnClient(mc -> {
                Looks.put(false, UUID, null); Looks.put(true, IDS[0], null); OwnedItems.forget(UUID);
                for (int i = 1; i < 36; i++) OwnedItems.forget("scroll-test-" + i);
            });
        }
        System.out.println("[SkyCosmeticsTest] model safety, original axes and picker checks passed");
    }
}
