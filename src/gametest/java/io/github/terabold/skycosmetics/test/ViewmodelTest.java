package io.github.terabold.skycosmetics.test;

import com.google.gson.*;
import io.github.terabold.skycosmetics.gui.SettingsScreen;
import io.github.terabold.skycosmetics.hand.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.*;

/** Checks base-item precedence, legacy imports and the real searchable item chooser. */
public final class ViewmodelTest implements FabricClientGameTest {
    public void runTest(ClientGameTestContext ctx) {
        var setup = ctx.computeOnClient(mc -> Hand.setup());
        var presets = ctx.computeOnClient(mc -> java.util.List.copyOf(Hand.presets()));
        boolean enabled = ctx.computeOnClient(mc -> Hand.enabled());
        try (var world = ctx.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender(); window(ctx, 1280, 720, 2);
            ctx.runOnClient(mc -> {
                for (var preset : java.util.List.copyOf(Hand.presets())) Hand.removePreset(preset.name());
                Hand.apply(new JsonObject());
                var pose = HandPose.VANILLA.with(HandPose.Field.SIZE, .5f);
                Hand.addRule("item:minecraft:diamond_sword", "Diamond Sword");
                Hand.setPose("item:minecraft:diamond_sword", HandPose.VANILLA.with(HandPose.Field.SIZE, .3f));
                Hand.setPose(Hand.EVERYTHING, pose);
                var sword = new ItemStack(Items.DIAMOND_SWORD);
                check(Hand.resolve(sword).equals(pose), "shared pose overrides dormant legacy item rules");
                var data = new CompoundTag(); data.putString("id", "TEST_VIEWMODEL_SWORD");
                sword.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
                check(Hand.resolve(sword).equals(pose), "SkyBlock items use the same shared pose");
                Hand.addRule("item:TEST_VIEWMODEL_SWORD", "Test Sword");
                Hand.setPose("item:TEST_VIEWMODEL_SWORD", HandPose.VANILLA.with(HandPose.Field.SIZE, .8f));
                check(Hand.resolve(sword).equals(pose), "legacy SkyBlock rules cannot override the shared pose");
                check(Hand.setup().getAsJsonObject("rules").size() == 2, "legacy rules remain saved for compatibility");
                JsonObject deep = new JsonObject(), tail = deep;
                for (int i = 0; i < 30; i++) { var next = new JsonObject(); tail.add("nested", next); tail = next; }
                check(HandCode.decode(HandCode.code(deep)) == null, "over-nested share data is rejected before applying anything");
                check(HandCode.decode("skyhand1:" + "A".repeat(33 * 1024)) == null, "oversized share codes are rejected before decompression");
            });
            ctx.setScreen(() -> new SettingsScreen(null, "hand")); ctx.waitTicks(2);
            ctx.runOnClient(mc -> check(((SettingsScreen)mc.screen).widget("handAdd") == null, "per-item controls are absent"));
            ctx.setScreen(() -> new HandScreen(null, "item:TEST_VIEWMODEL_SWORD")); ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> ((HandScreen)mc.screen).key().equals(Hand.EVERYTHING)), "the editor always opens the shared pose");
            ctx.takeScreenshot("skycosmetics-viewmodel-shared-editor");
            // In third person the editor shows first person, and gives the view back when it closes.
            ctx.setScreen(() -> null);
            ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
            ctx.setScreen(() -> new HandScreen(null, Hand.EVERYTHING)); ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> mc.options.getCameraType() == net.minecraft.client.CameraType.FIRST_PERSON),
                "the editor shows first person");
            ctx.setScreen(() -> null); ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> mc.options.getCameraType() == net.minecraft.client.CameraType.THIRD_PERSON_BACK),
                "and gives the third-person view back");
            ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
            quickSlots(ctx);
            thirdPersonSwing(ctx, world);
        } finally {
            ctx.setScreen(() -> null);
            ctx.runOnClient(mc -> {
                Hand.apply(setup); for (var preset : java.util.List.copyOf(Hand.presets())) Hand.removePreset(preset.name());
                for (var preset : presets) Hand.restorePreset(preset);
                Hand.setEnabled(enabled); Hand.save();
            });
        }
        System.out.println("[SkyCosmeticsTest] viewmodel shared pose, share codes and quick slots passed");
    }
    private static void thirdPersonSwing(ClientGameTestContext ctx,
        net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
        var camera = ctx.computeOnClient(mc -> mc.options.getCameraType());
        ctx.setScreen(() -> null);
        world.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:diamond_sword"); ctx.waitTicks(5);
        try {
            ctx.runOnClient(mc -> {
                Hand.apply(new JsonObject()); Hand.setEnabled(true);
                Hand.setPose(Hand.EVERYTHING, HandPose.VANILLA.with(HandPose.Field.SPEED, .5f)
                    .with(HandPose.Field.X, 2).with(HandPose.Field.SIZE, .5f));
            });
            for (var view : new net.minecraft.client.CameraType[]{net.minecraft.client.CameraType.THIRD_PERSON_BACK,
                net.minecraft.client.CameraType.THIRD_PERSON_FRONT}) {
                ctx.runOnClient(mc -> {
                    mc.options.setCameraType(view); HandSwing.reset();
                    mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                });
                ctx.waitTicks(3);
                ctx.runOnClient(mc -> {
                    float real = mc.player.getAttackAnim(.5f); int ticks = mc.player.swingTime;
                    var state = (net.minecraft.client.renderer.entity.state.AvatarRenderState)
                        mc.getEntityRenderDispatcher().getRenderer(mc.player).createRenderState(mc.player, .5f);
                    check(Math.abs(state.attackTime - HandSwing.progress(.5f)) < 1e-6, "third-person extraction uses cosmetic swing timing in " + view);
                    check(Math.abs(state.attackTime - real) > .05, "slower cosmetic swing differs from the real attack timer");
                    check(mc.player.getAttackAnim(.5f) == real && mc.player.swingTime == ticks, "rendering never changes actual swing timing");
                    check(state.rightHandItemStack.is(Items.DIAMOND_SWORD), "third-person held item remains the real item");
                    var mob = net.minecraft.world.entity.EntityType.ZOMBIE.create(mc.level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                    var other = (net.minecraft.client.renderer.entity.state.HumanoidRenderState)
                        mc.getEntityRenderDispatcher().getRenderer(mob).createRenderState(mob, .5f);
                    check(other.attackTime == mob.getAttackAnim(.5f), "other entities keep native swing timing");
                    Hand.setEnabled(false);
                    state = (net.minecraft.client.renderer.entity.state.AvatarRenderState)
                        mc.getEntityRenderDispatcher().getRenderer(mc.player).createRenderState(mc.player, .5f);
                    check(state.attackTime == real, "disabled viewmodel preserves native third-person animation");
                    Hand.setEnabled(true);
                });
                ctx.takeScreenshot("skycosmetics-viewmodel-swing-" + view.name().toLowerCase(java.util.Locale.ROOT));
                ctx.waitTicks(15);
            }
        } finally { ctx.runOnClient(mc -> { mc.options.setCameraType(camera); HandSwing.reset(); }); }
    }
    private static void quickSlots(ClientGameTestContext ctx) {
        ctx.setScreen(() -> new SettingsScreen(null, "hand", "handSavedSlots")); ctx.waitTicks(2);
        var saved = ctx.computeOnClient(mc -> Hand.setup());
        int[] at = ctx.computeOnClient(mc -> {
            var screen = (SettingsScreen)mc.screen; screen.scrollTo("handSavedSlots");
            var row = (ButtonRow)screen.widget("handSavedSlots");
            check(row.parts().size() == 6 && screen.widget("handSavedSetup") == null, "six quick slots replace the preset dropdown");
            var plus = row.parts().getFirst(); return new int[]{plus.getX() + plus.getWidth()/2, plus.getY() + plus.getHeight()/2};
        });
        click(ctx, at[0], at[1]); ctx.waitTicks(3);
        check(ctx.computeOnClient(mc -> Hand.presets().stream().anyMatch(p -> p.name().equals("Preset 1") && p.setup().equals(saved))), "one plus click saves the complete current viewmodel");
        ctx.runOnClient(mc -> Hand.setPose(Hand.EVERYTHING, HandPose.VANILLA.with(HandPose.Field.SIZE, .65f)));
        at = ctx.computeOnClient(mc -> {
            var row = (ButtonRow)((SettingsScreen)mc.screen).widget("handSavedSlots"); var tile = row.parts().getFirst();
            return new int[]{tile.getX() + tile.getWidth()/2, tile.getY() + tile.getHeight()/2};
        });
        click(ctx, at[0], at[1]); ctx.waitTicks(3);
        check(ctx.computeOnClient(mc -> Hand.setup().equals(saved)), "one saved-slot click restores the complete setup");
        ctx.takeScreenshot("skycosmetics-viewmodel-quick-slots");
        ctx.runOnClient(mc -> ((SettingsScreen)mc.screen).scrollTo("handSetupTools")); ctx.waitTicks(2);
        at = ctx.computeOnClient(mc -> {
            var row = (ButtonRow)((SettingsScreen)mc.screen).widget("handSetupTools"); var button = row.parts().get(1);
            return new int[]{button.getX() + button.getWidth()/2, button.getY() + button.getHeight()/2};
        });
        click(ctx, at[0], at[1]); ctx.waitTicks(2); ctx.takeScreenshot("skycosmetics-viewmodel-import-share");
        ctx.runOnClient(mc -> ((io.github.terabold.skycosmetics.gui.hub.TextField)mc.screen.children().stream()
            .filter(io.github.terabold.skycosmetics.gui.hub.TextField.class::isInstance).findFirst().orElseThrow()).setValue("invalid"));
        ctx.clickScreenButton("Apply code");
        check(ctx.computeOnClient(mc -> Hand.setup().equals(saved)), "invalid code leaves the current setup unchanged");
        ctx.clickScreenButton("Copy current"); ctx.clickScreenButton("Paste"); ctx.clickScreenButton("Apply code");
        check(ctx.computeOnClient(mc -> Hand.setup().equals(saved)), "compact code controls round-trip the complete viewmodel");
        ctx.clickScreenButton("Done"); ctx.waitTicks(2);
        ctx.runOnClient(mc -> ((SettingsScreen)mc.screen).scrollTo("handSetupTools")); ctx.waitTicks(2);
        at = ctx.computeOnClient(mc -> {
            var row = (ButtonRow)((SettingsScreen)mc.screen).widget("handSetupTools"); var button = row.parts().getFirst();
            return new int[]{button.getX() + button.getWidth()/2, button.getY() + button.getHeight()/2};
        });
        click(ctx, at[0], at[1]); ctx.waitTicks(2); ctx.clickScreenButton("Preset 1"); ctx.waitTicks(2);
        ctx.runOnClient(mc -> ((io.github.terabold.skycosmetics.gui.hub.TextField)mc.screen.children().stream()
            .filter(io.github.terabold.skycosmetics.gui.hub.TextField.class::isInstance).findFirst().orElseThrow()).setValue("Test setup"));
        ctx.clickScreenButton("Rename"); ctx.waitTicks(2); ctx.takeScreenshot("skycosmetics-viewmodel-edit-slot");
        check(ctx.computeOnClient(mc -> Hand.presets().stream().anyMatch(p -> p.name().equals("Test setup") && p.setup().equals(saved))), "renaming a slot preserves its complete setup");
        ctx.clickScreenButton("Delete"); ctx.waitTicks(2); ctx.clickScreenButton("Undo"); ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> Hand.presets().stream().anyMatch(p -> p.name().equals("Test setup") && p.setup().equals(saved))), "deleted setup can be restored with Undo");
        ctx.clickScreenButton("Done");
    }
}
