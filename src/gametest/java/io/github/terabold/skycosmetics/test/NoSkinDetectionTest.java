package io.github.terabold.skycosmetics.test;

import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.Textures;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.gui.SettingsScreen;
import io.github.terabold.skycosmetics.hub.Hub;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Old learning flags cannot re-enable collection through normal item or incoming packet paths. */
public final class NoSkinDetectionTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.waitFor(mc -> !Repo.get().skins.isEmpty(), 20 * 120);
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("skycosmetics");
        Path settings = dir.resolve("settings.json"), captures = dir.resolve("captured.json"), timings = dir.resolve("timings.json");
        awaitWrites();
        byte[] oldSettings = read(settings), oldCaptures = read(captures), oldTimings = read(timings);
        try {
            Files.createDirectories(dir);
            Files.writeString(settings, "{\"learnSkins\":true,\"learnTiming\":true,\"futureFlag\":\"preserve\"}");
            ctx.runOnClient(mc -> Settings.load());
            for (String type : List.of("data.SkinLearner", "data.TimingLearner", "data.PreviewRecorder",
                    "data.MissingAnimations", "data.Collector", "mixin.ClientPacketListenerMixin")) {
                try {
                    Class.forName("io.github.terabold.skycosmetics." + type, false, getClass().getClassLoader());
                    throw new AssertionError("Removed collector class still available: " + type);
                } catch (ClassNotFoundException expected) { }
            }
            try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
                sp.getClientLevel().waitForChunksRender();
                ctx.runOnClient(mc -> {
                    ItemStack previous = mc.player.getInventory().getItem(0).copy();
                    try {
                        for (int i = 0; i < 20; i++) {
                            ItemStack skin = skin(i);
                            check(Cosmetics.identify(skin) != null, "ordinary item identity still works");
                            skin.getStyledHoverName();
                            mc.getConnection().handleSetEquipment(new ClientboundSetEquipmentPacket(424242,
                                List.of(Pair.of(EquipmentSlot.HEAD, skin))));
                            mc.getConnection().handleContainerSetSlot(new ClientboundContainerSetSlotPacket(0, 0, 36, skin));
                        }
                    } finally {
                        mc.player.getInventory().setItem(0, previous);
                    }
                    var studio = Hub.sections().stream().filter(s -> s.id().equals(Hub.STUDIO)).findFirst().orElseThrow();
                    check(studio.rows().apply(SettingsScreen.create(null)).stream()
                        .noneMatch(o -> o.id().equals("learnSkins") || o.id().equals("learnTiming")), "learning controls removed");
                });
                ctx.waitTicks(40);
            }
            awaitWrites();
            check(Repo.get().skin("SKYCOSMETICS_NO_CAPTURE_SKIN") == null, "observed skin never added to catalog");
            check(Arrays.equals(oldCaptures, read(captures)), "captures unchanged");
            check(Arrays.equals(oldTimings, read(timings)), "measured timings unchanged");
            check(!Files.exists(dir.resolve("collector-exports")), "no collector exports generated");
            ctx.runOnClient(mc -> Settings.save());
            awaitWrites();
            var saved = JsonParser.parseString(Files.readString(settings)).getAsJsonObject();
            check(saved.get("learnSkins").getAsBoolean() && saved.get("learnTiming").getAsBoolean()
                && saved.get("futureFlag").getAsString().equals("preserve"), "unused legacy settings preserved safely");
        } catch (IOException e) {
            throw new AssertionError(e);
        } finally {
            restore(settings, oldSettings);
            ctx.runOnClient(mc -> Settings.load());
        }
        System.out.println("[SkyCosmeticsTest] no skin detection checks passed");
    }

    private static ItemStack skin(int frame) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "SKYCOSMETICS_NO_CAPTURE_SKIN");
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        s.set(DataComponents.CUSTOM_NAME, Component.literal("SkyCosmetics Test Skin"));
        s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("EPIC COSMETIC"))));
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/"
            + String.format("%064x", frame + 1) + "\"}}}";
        s.set(DataComponents.PROFILE, Textures.profile(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))));
        return s;
    }

    private static byte[] read(Path p) {
        try { return Files.isRegularFile(p) ? Files.readAllBytes(p) : null; }
        catch (IOException e) { throw new AssertionError(e); }
    }

    /** Match the existing IO tests' barrier; flush() is reserved for client shutdown. */
    private static void awaitWrites() {
        try {
            var field = Io.class.getDeclaredField("WRITER");
            field.setAccessible(true);
            ((ExecutorService) field.get(null)).submit(() -> {}).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("Waiting for config writes", e);
        }
    }

    private static void restore(Path p, byte[] bytes) {
        try { if (bytes == null) Files.deleteIfExists(p); else Files.write(p, bytes); }
        catch (IOException e) { throw new AssertionError(e); }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
