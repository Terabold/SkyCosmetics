package io.github.terabold.skycosmetics.data;

import io.github.terabold.skycosmetics.test.Report;

import com.mojang.datafixers.util.Pair;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.Textures;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Real client hooks: ID-less named choices, non-marker previews, unrelated entities, resets and exact durations. */
public final class PreviewRecorderTest implements FabricClientGameTest {
    private static final String FAMILY = "WISP_SPIRIT", FIRST = FAMILY + "_TEST_STYLE_01";

    @Override public void runTest(ClientGameTestContext ctx) {
        ctx.waitFor(mc -> !Repo.loading() && Repo.get().skin(FAMILY) != null, 20 * 120);
        var file = FabricLoader.getInstance().getConfigDir().resolve("skycosmetics/captured.json");
        String saved;
        try { saved = Files.exists(file) ? Files.readString(file) : null; }
        catch (java.io.IOException e) { throw new AssertionError(e); }
        boolean learning = Settings.learnSkins;
        var choices = new ArrayList<ItemStack>();
        try (var world = ctx.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            Settings.learnSkins = true;
            ctx.runOnClient(mc -> {
                ItemStack parent = head(1000, "Wisp Spirit Skin");
                var tag = new CompoundTag(); tag.putString("id", FAMILY);
                parent.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                // Preview hints are not required to be the last lore line.
                parent.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Right-click to preview!"), Component.literal("More information"))));
                PreviewRecorder.clicked(parent, 1);
                for (int i = 1; i <= 21; i++) choices.add(head(1000 + i, "Test Style %02d".formatted(i)));
                mc.getConnection().handleOpenScreen(new ClientboundOpenScreenPacket(17, MenuType.GENERIC_9x6, Component.literal("Skin Preview")));
                var contents = new ArrayList<ItemStack>(java.util.Collections.nCopies(54, ItemStack.EMPTY));
                for (int i = 0; i < choices.size(); i++) contents.set(i, choices.get(i));
                mc.getConnection().handleContainerContent(new ClientboundContainerSetContentPacket(17, 0, contents, ItemStack.EMPTY));
            });
            ctx.waitFor(mc -> Repo.get().skin(FIRST) != null, 20 * 20);
            ctx.runOnClient(mc -> {
                for (int i = 1; i <= 21; i++) {
                    SkinEntry entry = Repo.get().skin(FAMILY + "_TEST_STYLE_%02d".formatted(i));
                    check(entry != null && FAMILY.equals(entry.parent), "all 21 bare preview heads have the correct family");
                    check(entry.name.endsWith("(Test Style %02d)".formatted(i)), "server choice names survive capture");
                }
                PreviewRecorder.clicked(choices.getFirst(), 0);
                var unrelated = new ArmorStand(mc.level, mc.player.getX() + 2, mc.player.getY(), mc.player.getZ());
                unrelated.setId(91001); mc.level.addEntity(unrelated);
                equipment(ctx, 91001, head(9000, "Unrelated"));
                check(PreviewRecorder.status().contains("0 frame changes"), "nearby unrelated stand cannot start recording");
                var preview = new ArmorStand(mc.level, mc.player.getX() + 3, mc.player.getY(), mc.player.getZ());
                preview.setId(91002); mc.level.addEntity(preview);
                check(!preview.isMarker(), "fixture covers Taylor-style non-marker stands");
                equipment(ctx, 91002, choices.getFirst());
                equipment(ctx, 91002, head(1001, "Same texture, different profile metadata"));
                check(PreviewRecorder.status().contains("1 frame changes"), "profile metadata changes are not animation frames");
                equipment(ctx, 91001, head(1002, "Unrelated"));
                check(PreviewRecorder.status().contains("1 frame changes"), "recording never switches to another entity");
            });
            // Three cycles plus the final boundary: A 2 ticks, B 4, C 6.
            for (int i = 1; i <= 10; i++) {
                ctx.waitTicks(new int[]{2,4,6}[(i - 1) % 3]);
                final int frame = 1001 + i % 3;
                ctx.runOnClient(mc -> equipment(ctx, 91002, head(frame, "Preview")));
            }
            ctx.waitFor(mc -> Repo.get().skin(FIRST).animated(), 20 * 20);
            ctx.runOnClient(mc -> {
                var entry = Repo.get().skin(FIRST);
                check(entry.textures.length == 3 && java.util.Arrays.equals(entry.frameTicks, new int[]{4,6,2}), "frame order and unequal durations survive capture");
                check(PreviewRecorder.status().contains("recorded"), "completed result remains visible after recording stops");
                // Item displays use entity data, not equipment updates.
                PreviewRecorder.clicked(choices.get(1), 0);
                var display = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, mc.level);
                display.setId(91003); display.setPos(mc.player.getX() + 3, mc.player.getY(), mc.player.getZ()); mc.level.addEntity(display);
                check(display.getSlot(0).set(choices.get(1)), "display fixture exposes its item slot");
                mc.getConnection().handleSetEntityData(new ClientboundSetEntityDataPacket(91003, display.getEntityData().packDirty()));
                check(PreviewRecorder.status().contains("1 frame changes"), "display item update reaches preview recorder");
                // A later unrelated menu must not inherit this family.
                mc.getConnection().handleOpenScreen(new ClientboundOpenScreenPacket(18, MenuType.GENERIC_9x6, Component.literal("Unrelated")));
                PreviewRecorder.onContent(new ClientboundContainerSetContentPacket(18, 0, List.of(ItemStack.EMPTY), ItemStack.EMPTY));
                PreviewRecorder.clicked(head(8500, "Not a Wisp choice"), 0);
                check(PreviewRecorder.status().contains("could not identify"), "ID-less choices cannot borrow stale context");
                Settings.learnSkins = false; PreviewRecorder.tick(mc);
            });
        } finally {
            Settings.learnSkins = learning;
            try { if (saved == null) Files.deleteIfExists(file); else Files.writeString(file, saved); }
            catch (java.io.IOException e) { throw new AssertionError(e); }
            var reloaded = new java.util.concurrent.atomic.AtomicBoolean();
            Repo.reload(() -> reloaded.set(true)); ctx.waitFor(mc -> reloaded.get(), 20 * 120);
        }
        System.out.println("[SkyCosmeticsTest] preview chooser, entity matching, animation timing and context isolation passed");
    }

    private static void equipment(ClientGameTestContext ctx, int id, ItemStack stack) {
        // Called only on the client thread; exercising the real read-only incoming hook.
        net.minecraft.client.Minecraft.getInstance().getConnection().handleSetEquipment(
            new ClientboundSetEquipmentPacket(id, List.of(Pair.of(EquipmentSlot.HEAD, stack))));
    }

    private static ItemStack head(int hash, String name) {
        var stack = new ItemStack(Items.PLAYER_HEAD);
        String json = "{\"profileName\":\"" + name + "\",\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/%064x\"}}}".formatted(hash);
        stack.set(DataComponents.PROFILE, Textures.profile(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Click to preview!"), Component.literal("More information"))));
        return stack;
    }
    private static void check(boolean condition, String message) { Report.check(condition, message); }
}
