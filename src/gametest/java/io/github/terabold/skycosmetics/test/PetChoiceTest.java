package io.github.terabold.skycosmetics.test;

import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.Textures;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import io.github.terabold.skycosmetics.mixin.AbstractContainerScreenAccessor;
import io.github.terabold.skycosmetics.pet.PetDebug;
import io.github.terabold.skycosmetics.pet.PetTracker;
import io.github.terabold.skycosmetics.pet.WorldPet;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Two of your pets share type, rarity, level and name (such as two Lv100
 * Mythic Rabbits), each with its own skin look. The pet in the world is a
 * marker stand holding the head, under "[Lv100] Rabbit", as Hypixel shows it on
 * 26.1. Whichever way it is summoned, the right skin shows: a real mouse click
 * in the Pets menu, another mod's click on the slot (Odin's pet keybinds),
 * Autopet's line with the held item in its hover. A summon line while one is
 * out names the other. A summon line with nothing to tell them apart picks
 * the one out most recently and says so in "/skycosmetics debug pet"; when both
 * look the same it says that. The choice survives a relog (pets.json read
 * again), and with "Show my skins on my pet and power orbs" off nothing is
 * locked.
 */
final class PetChoiceTest {
    private static final String A = "t-rabbit-a";
    private static final String B = "t-rabbit-b";
    private static final String SKIN_A = "PET_SKIN_RABBIT_LUNAR";
    private static final String SKIN_B = "PET_SKIN_RABBIT_LUNAR_BABY";
    private static final int SLOT_A = 10;
    private static final int SLOT_B = 11;
    private static final String SUMMON = "tellraw @a {\"text\":\"You summoned your \",\"color\":\"green\",\"extra\":["
        + "{\"text\":\"Rabbit\",\"color\":\"light_purple\"},{\"text\":\"!\",\"color\":\"green\"}]}";
    private static final String DESPAWN = "tellraw @a {\"text\":\"You despawned your Rabbit!\",\"color\":\"green\"}";

    private PetChoiceTest() {}

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String head = ctx.computeOnClient(mc -> rabbitHead().textures[0]);
        String[] skinA = ctx.computeOnClient(mc -> Repo.get().skin(SKIN_A).textures);
        String[] skinB = ctx.computeOnClient(mc -> Repo.get().skin(SKIN_B).textures);
        ctx.runOnClient(mc -> {
            PetTracker.debugSetCurrent(ItemStack.EMPTY);
            Looks.put(false, A, new Looks.Look(SKIN_A, null, null, null, "rabbit a"));
            Looks.put(false, B, new Looks.Look(SKIN_B, null, null, null, "rabbit b"));
            mc.gui.getChat().clearMessages(false);
        });
        ctx.waitFor(mc -> PetWorldTest.ready(SKIN_A) && PetWorldTest.ready(SKIN_B), 20 * 60);
        sp.getServer().runCommand("kill @e[tag=sspet]");
        double[] at = ctx.computeOnClient(mc -> PetWorldTest.around(mc, 1.3, 0.8, 0));
        PetWorldTest.summonWith(sp, "armor_stand", "Marker:1b,Invisible:1b,NoGravity:1b,ShowArms:1b", "sspet_rabbit", at,
            "weapon.mainhand", "minecraft:player_head", head);
        PetWorldTest.nametag(sp, at, "§8[§7Lv100§8] §dRabbit", 1.0);
        ctx.waitFor(mc -> stand(mc, at) != null, 20 * 5);

        // A real mouse click on B in the Pets menu; the summon line that follows agrees.
        openPets(ctx, head, null);
        double[] slotB = ctx.computeOnClient(mc -> slotCentre(mc, SLOT_B));
        ctx.getInput().setCursorPos(slotB[0], slotB[1]);
        ctx.waitTicks(2);
        ctx.takeScreenshot("skycosmetics-17-pet-choice-menu");
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(1);
        ctx.runOnClient(mc -> PetWorldTest.check(B.equals(uuid()), "a mouse click in the Pets menu picks that Rabbit"));
        closePets(ctx);
        sp.getServer().runCommand(SUMMON);
        expect(ctx, at, B, skinB, "clicked with the mouse, the summoned Rabbit shows its own skin");
        ctx.takeScreenshot("skycosmetics-17a-pet-choice-click");

        // Another mod clicks A's slot for you (Odin's pet keybinds): the same click path.
        openPets(ctx, head, B);
        ctx.runOnClient(mc -> PetWorldTest.check(B.equals(uuid()), "the Pets menu shows B as the one out"));
        ctx.runOnClient(mc -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, SLOT_A, 0,
            ContainerInput.PICKUP, mc.player));
        closePets(ctx);
        sp.getServer().runCommand(SUMMON);
        expect(ctx, at, A, skinA, "clicked by another mod, the other Rabbit shows its skin");

        // Autopet names the level only; its hover (the pet's tooltip) names B's held item.
        despawn(ctx, sp);
        sp.getServer().runCommand("tellraw @a {\"text\":\"\",\"extra\":[{\"text\":\"Autopet \",\"color\":\"red\"},"
            + "{\"text\":\"equipped your \",\"color\":\"yellow\"},{\"text\":\"[Lvl 100] \",\"color\":\"gray\"},"
            + "{\"text\":\"Rabbit\",\"color\":\"light_purple\",\"hover_event\":{\"action\":\"show_text\",\"value\":"
            + "\"[Lvl 100] Rabbit\\nFarming Pet\\n\\nHeld Item: Green Bandana\\n\\nMAX LEVEL\"}},"
            + "{\"text\":\"! \",\"color\":\"yellow\"},{\"text\":\"VIEW RULE\",\"color\":\"green\",\"bold\":true}]}");
        expect(ctx, at, B, skinB, "Autopet's hover tells the Rabbits apart by held item");

        // A summon line while B is out names the other Rabbit: summoning B again would have despawned it.
        sp.getServer().runCommand(SUMMON);
        expect(ctx, at, A, skinA, "a summon while one Rabbit is out names the other");
        ctx.runOnClient(mc -> PetWorldTest.check(PetDebug.report().stream().anyMatch(l -> l.startsWith("Ambiguous")
            && l.contains("the other one was already out")), "debug pet: the other one was already out"));

        // A summon line alone: both fit. The one out most recently (B) is shown, never nothing, and the debug says why.
        despawn(ctx, sp);
        sp.getServer().runCommand(SUMMON);
        expect(ctx, at, B, skinB, "with nothing to tell them apart, the Rabbit out most recently");
        ctx.runOnClient(mc -> {
            List<String> report = PetDebug.report();
            report.forEach(l -> System.out.println("[SkyCosmeticsTest] debug pet: " + l));
            PetWorldTest.check(report.stream().anyMatch(l -> l.startsWith("Ambiguous: 2 of your pets fit")
                && l.contains("out most recently")), "debug pet says the pet is a guess and why");
            PetWorldTest.check(report.stream().anyMatch(l -> l.contains(B) && l.contains("Green Bandana")
                && l.contains(SKIN_B) && l.endsWith("(chosen)")), "debug pet lists the chosen candidate with its held item");
            PetWorldTest.check(report.stream().anyMatch(l -> l.contains(A) && l.contains("Yellow Bandana")
                && !l.endsWith("(chosen)")), "debug pet lists the other candidate");
            mc.player.connection.sendCommand("skycosmetics debug pet");
        });
        ctx.waitTicks(3);
        ctx.takeScreenshot("skycosmetics-17b-pet-choice-guess");
        ctx.runOnClient(mc -> mc.gui.getChat().clearMessages(false));

        // A relog: pets.json is read again and the same Rabbit, still marked a guess, wears its skin.
        ctx.waitFor(mc -> saved().contains("\"current\": \"" + B + "\"") && saved().contains("\"candidates\""), 20 * 5);
        ctx.runOnClient(mc -> {
            relog();
            PetWorldTest.check(B.equals(uuid()), "after a relog the same Rabbit is out");
            List<String> report = PetDebug.report();
            PetWorldTest.check(report.stream().anyMatch(l -> l.startsWith("Ambiguous: 2") && l.contains("last session")),
                "after a relog it is still marked a guess, remembered from the last session");
        });
        expect(ctx, at, B, skinB, "after a relog the Rabbit wears its skin again");

        // Both wear the same skin: any guess is right, and the debug says so.
        ctx.runOnClient(mc -> Looks.put(false, A, new Looks.Look(SKIN_B, null, null, null, "rabbit a")));
        despawn(ctx, sp);
        sp.getServer().runCommand(SUMMON);
        expect(ctx, at, B, skinB, "two Rabbits with the same skin show it");
        ctx.runOnClient(mc -> PetWorldTest.check(PetDebug.report().stream()
            .anyMatch(l -> l.startsWith("Ambiguous") && l.contains("they all look the same")), "debug pet: they all look the same"));

        // "Show my skins on my pet and power orbs" off: nothing is locked, Hypixel's head shows.
        ctx.runOnClient(mc -> Settings.worldReskin = false);
        ctx.waitFor(mc -> WorldPet.lockedId() == -1, 20 * 2);
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> {
            PetWorldTest.check(WorldPet.lockedId() == -1, "with world reskins off nothing is locked");
            PetWorldTest.assertShows(stand(mc, at), new String[]{head}, "with world reskins off Hypixel's head shows");
            Settings.worldReskin = true;
        });
        ctx.waitFor(mc -> WorldPet.lockedId() == stand(mc, at).getId(), 20 * 5);
        System.out.println("[SkyCosmeticsTest] pet choice: OK");

        ctx.runOnClient(mc -> {
            PetTracker.debugSetCurrent(ItemStack.EMPTY);
            Looks.put(false, A, null);
            Looks.put(false, B, null);
            forget(A);
            forget(B);
        });
        sp.getServer().runCommand("kill @e[tag=sspet]");
    }

    private static void despawn(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand(DESPAWN);
        ctx.waitFor(mc -> PetTracker.currentIdent() == null && WorldPet.lockedId() == -1, 20 * 3);
    }

    /** The pet out is {@code uuid}, and the stand at {@code at} is locked and wears {@code skin}. */
    private static void expect(ClientGameTestContext ctx, double[] at, String uuid, String[] skin, String what) {
        ctx.waitFor(mc -> uuid.equals(uuid()) && stand(mc, at) != null && WorldPet.lockedId() == stand(mc, at).getId(),
            20 * 5);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> PetWorldTest.assertShows(stand(mc, at), skin, what));
    }

    private static String uuid() {
        return PetTracker.currentIdent() == null ? null : PetTracker.currentIdent().uuid();
    }

    /** Hypixel's Pets menu with both Rabbits, {@code active} summoned (or none); clicks go to this menu. */
    private static void openPets(ClientGameTestContext ctx, String head, String active) {
        ctx.runOnClient(mc -> {
            SimpleContainer box = new SimpleContainer(54);
            box.setItem(SLOT_A, rabbit(A, "YELLOW_BANDANA", "Yellow Bandana", head, A.equals(active)));
            box.setItem(SLOT_B, rabbit(B, "GREEN_BANDANA", "Green Bandana", head, B.equals(active)));
            ChestMenu menu = ChestMenu.sixRows(101, mc.player.getInventory(), box);
            mc.player.containerMenu = menu;
            mc.setScreen(new ContainerScreen(menu, mc.player.getInventory(), Component.literal("Pets")));
        });
        ctx.waitTicks(3); // scanned: both Rabbits are known
    }

    private static void closePets(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            mc.player.containerMenu.setCarried(ItemStack.EMPTY);
            mc.player.containerMenu = mc.player.inventoryMenu;
            mc.setScreen(null);
        });
    }

    private static double[] slotCentre(Minecraft mc, int index) {
        AbstractContainerScreenAccessor a = (AbstractContainerScreenAccessor) mc.screen;
        Slot slot = mc.player.containerMenu.slots.get(index);
        double scale = mc.getWindow().getGuiScale();
        return new double[]{(a.skycosmetics$leftPos() + slot.x + 8) * scale, (a.skycosmetics$topPos() + slot.y + 8) * scale};
    }

    /** A Lv100 Mythic Rabbit as the Pets menu shows it, holding {@code heldName}; {@code active} if it is out. */
    private static ItemStack rabbit(String uuid, String heldId, String heldName, String head, boolean active) {
        CompoundTag t = new CompoundTag();
        t.putString("id", "PET");
        t.putString("uuid", uuid);
        t.putString("petInfo", "{\"type\":\"RABBIT\",\"tier\":\"MYTHIC\",\"heldItem\":\"" + heldId + "\",\"uuid\":\""
            + uuid + "\",\"uniqueId\":\"u-" + uuid + "\"}");
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        s.set(DataComponents.CUSTOM_NAME, Component.literal("[Lvl 100] ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal("Rabbit").withStyle(ChatFormatting.LIGHT_PURPLE)));
        s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Farming Pet"), Component.empty(),
            Component.literal("Held Item: ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(heldName).withStyle(ChatFormatting.DARK_PURPLE)),
            Component.empty(), Component.literal("MAX LEVEL"), Component.empty(),
            Component.literal(active ? "Click to despawn!" : "Left-click to summon!"),
            Component.literal("Shift Left-click to toggle as favorite!"),
            Component.literal("Right-click to convert to an item!"))));
        s.set(DataComponents.PROFILE, Textures.profile(head));
        return s;
    }

    private static SkinEntry rabbitHead() {
        SkinEntry e = Repo.get().skin("RABBIT;5");
        return e != null ? e : Repo.get().skin("RABBIT;4");
    }

    private static ArmorStand stand(Minecraft mc, double[] at) {
        return PetWorldTest.entity(mc, at, ArmorStand.class, a -> !a.getMainHandItem().isEmpty());
    }

    private static String saved() {
        try {
            return Files.readString(petsJson(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static Path petsJson() {
        return FabricLoader.getInstance().getConfigDir().resolve("skycosmetics/pets.json");
    }

    /** What a restart does: the tracker forgets the pet out and reads pets.json again. */
    private static void relog() {
        try {
            Field current = PetTracker.class.getDeclaredField("current");
            current.setAccessible(true);
            current.set(null, null);
            Method load = PetTracker.class.getDeclaredMethod("load");
            load.setAccessible(true);
            load.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("[SkyCosmeticsTest] pet choice: could not read pets.json again", e);
        }
    }

    /** Test pets leave pets.json as a converted pet would. */
    private static void forget(String uuid) {
        try {
            Method forget = PetTracker.class.getDeclaredMethod("forget", String.class);
            forget.setAccessible(true);
            forget.invoke(null, uuid);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("[SkyCosmeticsTest] pet choice: could not forget " + uuid, e);
        }
    }
}
