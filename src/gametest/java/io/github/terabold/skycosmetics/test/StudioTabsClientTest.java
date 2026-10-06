package io.github.terabold.skycosmetics.test;

import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.compat.OtherLooks;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.items.OwnedItems;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.terabold.skycosmetics.test.StudioLayoutTest.check;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.window;

/**
 * The studio's Saved and Other Mods tabs on items of every kind: a helmet, boots, a sword, a pet, a power orb and
 * a chestplate, all with real-looking UUIDs. Looks other tests left are set aside first and put back at the end.
 */
public class StudioTabsClientTest implements FabricClientGameTest {
    static final String HELM = "0f5e8a6c-1d2b-4c3a-9e8f-000000000001";
    static final String BOOTS = "0f5e8a6c-1d2b-4c3a-9e8f-000000000002";
    static final String SWORD = "0f5e8a6c-1d2b-4c3a-9e8f-000000000003";
    static final String PET = "0f5e8a6c-1d2b-4c3a-9e8f-000000000004";
    static final String ORB = "0f5e8a6c-1d2b-4c3a-9e8f-000000000005";
    static final String CHEST = "0f5e8a6c-1d2b-4c3a-9e8f-000000000006";
    /** Saved for an item never seen (sold, or on another profile): only its type is known. */
    static final String GONE = "0f5e8a6c-1d2b-4c3a-9e8f-000000000007";
    /** Only another mod has it. */
    static final String UNSEEN = "0f5e8a6c-1d2b-4c3a-9e8f-000000000008";
    static final String[] ALL = {HELM, BOOTS, SWORD, PET, ORB, CHEST, GONE, UNSEEN};

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.waitFor(mc -> !Repo.get().skins.isEmpty(), 20 * 120);
        Map<String, Looks.Look> uuids = new HashMap<>(), types = new HashMap<>();
        Map<String, String> itemTypes = new HashMap<>();
        ctx.runOnClient(mc -> {
            uuids.putAll(Looks.uuidLooks());
            types.putAll(Looks.typeLooks());
            for (String u : uuids.keySet()) itemTypes.put(u, Looks.itemType(u));
            for (String u : uuids.keySet()) Looks.put(false, u, null);
            for (String t : types.keySet()) Looks.put(true, t, null);
        });
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientLevel().waitForChunksRender();
            sp.getServer().runCommand("time set noon");
            give(sp, "hotbar.0", "minecraft:player_head", "ZEPHYR_HELMET", HELM, "Zephyr Helmet", "LEGENDARY HELMET");
            give(sp, "hotbar.1", "minecraft:leather_boots", "SPRING_BOOTS", BOOTS, "Spring Boots", "RARE BOOTS");
            give(sp, "hotbar.2", "minecraft:diamond_sword", "ZEPHYR_SWORD", SWORD, "Zephyr Sword", "LEGENDARY SWORD");
            sp.getServer().runCommand("item replace entity @a hotbar.3 with minecraft:player_head[minecraft:custom_data="
                + "{id:\"PET\",petInfo:'{\"type\":\"BEE\",\"tier\":\"LEGENDARY\",\"uuid\":\"" + PET + "\"}'}]");
            give(sp, "hotbar.4", "minecraft:player_head", "PLASMAFLUX_POWER_ORB", ORB, "Plasmaflux Power Orb", "LEGENDARY DEPLOYABLE");
            give(sp, "hotbar.5", "minecraft:leather_chestplate", "ZEPHYR_CHESTPLATE", CHEST, "Zephyr Chestplate",
                "LEGENDARY CHESTPLATE");
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
            ctx.waitTicks(12); // My Items learns them
            ctx.runOnClient(mc -> {
                for (String u : new String[]{HELM, BOOTS, SWORD, PET, ORB, CHEST}) check(OwnedItems.knows(u), "My Items knows " + u);
            });

            SavedTabTest.run(ctx);
            OtherModsTest.run(ctx);

            window(ctx, 854, 480, 0);
            ctx.setScreen(() -> null);
            for (int i = 0; i <= 5; i++) sp.getServer().runCommand("item replace entity @a hotbar." + i + " with minecraft:air");
            ctx.waitTicks(2);
        } finally {
            ctx.runOnClient(mc -> {
                OtherLooks.use(List.of());
                for (String u : ALL) {
                    Looks.put(false, u, null);
                    OwnedItems.forget(u);
                }
                for (String t : List.copyOf(Looks.typeLooks().keySet())) Looks.put(true, t, null);
                uuids.forEach((u, l) -> Looks.putItem(u, itemTypes.get(u), l));
                types.forEach((t, l) -> Looks.put(true, t, l));
            });
        }
    }

    private static void give(TestSingleplayerContext sp, String slot, String item, String id, String uuid, String name, String rarity) {
        sp.getServer().runCommand("item replace entity @a " + slot + " with " + item + "[minecraft:custom_data={id:\"" + id
            + "\",uuid:\"" + uuid + "\"},minecraft:custom_name={text:\"" + name + "\",italic:false},minecraft:lore=[\""
            + rarity + "\"]]");
    }
}
