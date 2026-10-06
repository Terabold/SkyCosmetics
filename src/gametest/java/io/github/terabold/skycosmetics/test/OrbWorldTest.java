package io.github.terabold.skycosmetics.test;

import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import io.github.terabold.skycosmetics.deploy.DeployedOrbs;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * A deployed power orb, the way Hypixel answers your right-click: an invisible
 * stand wearing the orb's head appears next to you. Only the stand that shows
 * up right after your own click gets your orb skin; one that was already there
 * and one that appears later (someone else's) keep Hypixel's head. Deploying
 * again moves the skin to the new orb, and a despawned orb releases the lock.
 * An orb that is an item display of another item is found too, by the orb
 * item's texture, over a nearer head that appeared at the same moment; with
 * no texture to go by, a stand's helmet wins over a nearer mob's head, and a
 * head under the orb's timer nametag over a nearer one. An orb that leaves
 * view and loads again is reskinned again, and with world reskins off a
 * deploy is not watched at all.
 */
final class OrbWorldTest {
    private static final String ORB_UUID = "t-orb";
    private static final String SKIN = "GLISTENING_MELON_FLUX";

    private OrbWorldTest() {}

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String orb = ctx.computeOnClient(mc -> {
            SkinEntry e = Repo.get().skin("RADIANT_POWER_ORB");
            return (e != null ? e : Repo.get().skin("GOLDEN_DRAGON;4")).textures[0];
        });
        String[] skin = ctx.computeOnClient(mc -> Repo.get().skin(SKIN).textures);
        String alien = ctx.computeOnClient(mc -> Repo.get().skin("NECRON_DIAMOND_KNIGHT").textures[0]);
        sp.getServer().runCommand("item replace entity @a hotbar.5 with minecraft:player_head[minecraft:custom_data="
            + "{id:\"RADIANT_POWER_ORB\",uuid:\"" + ORB_UUID + "\"},minecraft:lore=[\"\",\"UNCOMMON DEPLOYABLE\"],"
            + "minecraft:profile={properties:[{name:\"textures\",value:\"" + orb + "\"}]}]");
        // Someone else's orb, already standing near you before you click.
        double[] before = ctx.computeOnClient(mc -> PetWorldTest.around(mc, -1.6, 1.2, 0));
        PetWorldTest.summon(sp, "ssorb_before", before, orb, true);
        ctx.runOnClient(mc -> {
            Looks.put(false, ORB_UUID, new Looks.Look(SKIN, null, null, null, "orb"));
            mc.player.getInventory().setSelectedSlot(5);
        });
        ctx.waitFor(mc -> PetWorldTest.ready(SKIN), 20 * 60);

        double[] mine = ctx.computeOnClient(mc -> PetWorldTest.around(mc, 1.3, 1.2, 0));
        deploy(ctx);
        PetWorldTest.summon(sp, "ssorb_mine", mine, orb, true);
        ctx.waitFor(mc -> lockedAt(mc, mine), 20 * 3);
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> {
            PetWorldTest.assertShows(stand(mc, mine), skin, "your deployed orb shows its skin");
            PetWorldTest.assertShows(stand(mc, before), new String[]{orb}, "an orb that was already there is left alone");
            mc.player.connection.sendCommand("skycosmetics debug orb");
            DeployedOrbs.report().forEach(l -> System.out.println("[SkyCosmeticsTest] debug orb: " + l));
        });
        ctx.takeScreenshot("skycosmetics-16-deployed-orb");

        // Long after the click: a new orb is someone else's.
        ctx.waitTicks(90);
        double[] late = ctx.computeOnClient(mc -> PetWorldTest.around(mc, -0.6, 2.2, 0));
        PetWorldTest.summon(sp, "ssorb_late", late, orb, true);
        ctx.waitFor(mc -> stand(mc, late) != null, 20);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> {
            PetWorldTest.check(lockedAt(mc, mine), "the lock stays on your orb");
            PetWorldTest.assertShows(stand(mc, late), new String[]{orb}, "an orb without your click is left alone");
        });

        // Deploying again moves the skin to the new orb.
        double[] next = ctx.computeOnClient(mc -> PetWorldTest.around(mc, 0.4, 2.6, 0));
        deploy(ctx);
        PetWorldTest.summon(sp, "ssorb_next", next, orb, true);
        ctx.waitFor(mc -> lockedAt(mc, next), 20 * 3);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            PetWorldTest.assertShows(stand(mc, next), skin, "your new orb shows the skin");
            PetWorldTest.assertShows(stand(mc, mine), new String[]{orb}, "your old orb is released");
        });

        // Despawned: the lock goes.
        sp.getServer().runCommand("kill @e[type=armor_stand,tag=ssorb_next]");
        ctx.waitFor(mc -> DeployedOrbs.lockedId() == -1, 20 * 3);

        // Not a stand's helmet: an item display of paper carrying the orb's texture. A mob wearing some
        // other head appears nearer in the same moment; the orb item's own texture decides.
        double[] paper = ctx.computeOnClient(mc -> PetWorldTest.around(mc, -2.2, 2.0, 0.6));
        double[] mob = ctx.computeOnClient(mc -> PetWorldTest.around(mc, 2.4, 0.4, 0));
        deploy(ctx);
        PetWorldTest.summonWith(sp, "villager", "NoAI:1b,Silent:1b,Invulnerable:1b", "ssorb_mob", mob,
            "armor.head", "minecraft:player_head", alien);
        PetWorldTest.summonWith(sp, "item_display", "NoGravity:1b", "ssorb_paper", paper, "contents", "minecraft:paper", orb);
        ctx.waitFor(mc -> {
            Display.ItemDisplay d = PetWorldTest.entity(mc, paper, Display.ItemDisplay.class, x -> true);
            return d != null && DeployedOrbs.lockedId() == d.getId();
        }, 20 * 3);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            PetWorldTest.assertSkinned(PetWorldTest.entity(mc, paper, Display.ItemDisplay.class, x -> true), skin,
                "an orb shown as an item display of paper shows the skin");
            PetWorldTest.assertShows(PetWorldTest.entity(mc, mob, Villager.class, x -> true), new String[]{alien},
                "a nearer head without the orb's texture is left alone");
            List<String> report = DeployedOrbs.report();
            report.forEach(l -> System.out.println("[SkyCosmeticsTest] debug orb: " + l));
            String line = report.stream().filter(l -> l.contains("LOCKED")).findFirst().orElse("");
            PetWorldTest.check(line.contains("item=paper") && line.contains("IN SET"),
                "debug orb shows the locked display, its item and texture: " + line);
            PetWorldTest.check(report.stream().anyMatch(l -> l.contains("villager") && l.contains("head=player_head")),
                "debug orb lists the other entities near the click with what they wear");
        });
        ctx.runOnClient(mc -> mc.gui.getChat().clearMessages(false));
        ctx.takeScreenshot("skycosmetics-16b-deployed-orb-display");
        sp.getServer().runCommand("kill @e[tag=ssorb_paper]");
        ctx.waitFor(mc -> DeployedOrbs.lockedId() == -1, 20 * 3);

        // Hypixel's world head may not show the orb item's texture: then a stand's helmet (the shape 26.1
        // mods know) beats a mob's head that loaded nearer at the same moment, and keeps the lock.
        double[] far = ctx.computeOnClient(mc -> PetWorldTest.around(mc, -2.0, 2.4, 0));
        double[] near = ctx.computeOnClient(mc -> PetWorldTest.around(mc, 1.8, 0.6, 0));
        deploy(ctx);
        PetWorldTest.summonWith(sp, "villager", "NoAI:1b,Silent:1b,Invulnerable:1b", "ssorb_mob2", near,
            "armor.head", "minecraft:player_head", alien);
        PetWorldTest.summon(sp, "ssorb_alien", far, alien, true);
        ctx.waitFor(mc -> lockedAt(mc, far), 20 * 3);
        ctx.waitTicks(90); // past the click window
        ctx.runOnClient(mc -> {
            PetWorldTest.check(lockedAt(mc, far), "without the orb's texture, the stand's helmet keeps the lock");
            PetWorldTest.assertShows(stand(mc, far), skin, "that stand shows the orb skin");
            PetWorldTest.assertShows(PetWorldTest.entity(mc, near, Villager.class, x -> true), new String[]{alien},
                "the nearer mob keeps its head");
        });
        sp.getServer().runCommand("kill @e[tag=ssorb_alien]");
        ctx.waitFor(mc -> DeployedOrbs.lockedId() == -1, 20 * 3);

        // Two stands load after the click, neither with the orb's texture: the one under the orb's timer
        // nametag ("Mana Flux 30s") is the orb, though the other is nearer.
        double[] tagged = ctx.computeOnClient(mc -> PetWorldTest.around(mc, -1.8, 2.6, 0));
        double[] plain = ctx.computeOnClient(mc -> PetWorldTest.around(mc, 1.2, 0.8, 0));
        deploy(ctx);
        PetWorldTest.summon(sp, "ssorb_plain", plain, alien, true);
        PetWorldTest.summon(sp, "ssorb_tagged", tagged, alien, true);
        PetWorldTest.nametag(sp, tagged, "§9Mana Flux §e30s", 1.2);
        ctx.waitFor(mc -> lockedAt(mc, tagged), 20 * 3);
        ctx.waitTicks(90); // past the click window
        ctx.runOnClient(mc -> {
            PetWorldTest.check(lockedAt(mc, tagged), "the head under the orb's nametag is your orb");
            PetWorldTest.assertShows(stand(mc, plain), new String[]{alien}, "a nearer head without it is left alone");
        });

        // Your orb leaves view (you walked away) and loads again with the same entity id: it wears the skin again.
        Object[] gone = ctx.computeOnClient(mc -> {
            ArmorStand a = stand(mc, tagged);
            Object[] o = {a.getId(), a.getItemBySlot(EquipmentSlot.HEAD).copy(), a.position()};
            mc.level.removeEntity(a.getId(), Entity.RemovalReason.DISCARDED);
            return o;
        });
        ctx.waitFor(mc -> DeployedOrbs.lockedId() == -1, 20 * 2);
        ctx.runOnClient(mc -> PetWorldTest.check(DeployedOrbs.report().stream().anyMatch(l -> l.contains("out of view")),
            "debug orb says your orb is out of view"));
        ctx.runOnClient(mc -> {
            ArmorStand back = new ArmorStand(EntityType.ARMOR_STAND, mc.level);
            back.setId((Integer) gone[0]);
            back.setPos((Vec3) gone[2]);
            back.setInvisible(true);
            back.setItemSlot(EquipmentSlot.HEAD, (ItemStack) gone[1]);
            mc.level.addEntity(back);
        });
        ctx.waitFor(mc -> DeployedOrbs.lockedId() == (Integer) gone[0], 20 * 2);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> PetWorldTest.assertShows(stand(mc, tagged), skin, "back in view, your orb wears the skin again"));
        sp.getServer().runCommand("kill @e[tag=sspet]");
        ctx.waitFor(mc -> DeployedOrbs.lockedId() == -1, 20 * 3);

        // "Show my skins on my pet and power orbs" off: a deploy is not even watched.
        ctx.runOnClient(mc -> Settings.worldReskin = false);
        double[] off = ctx.computeOnClient(mc -> PetWorldTest.around(mc, 1.0, 1.6, 0));
        deploy(ctx);
        PetWorldTest.summon(sp, "ssorb_off", off, orb, true);
        ctx.waitFor(mc -> stand(mc, off) != null, 20 * 2);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> {
            PetWorldTest.check(DeployedOrbs.lockedId() == -1, "with world reskins off your orb is not locked");
            PetWorldTest.check(DeployedOrbs.report().get(0).contains("no deploy click"), "with world reskins off no click is pending");
            Settings.worldReskin = true;
        });
        sp.getServer().runCommand("kill @e[tag=ssorb_off]");
        System.out.println("[SkyCosmeticsTest] deployed orb: OK");

        ctx.runOnClient(mc -> {
            Looks.put(false, ORB_UUID, null);
            mc.player.getInventory().setSelectedSlot(0);
        });
        sp.getServer().runCommand("kill @e[tag=sspet]");
    }

    /** A real right-click into the sky with the orb in hand, as you would deploy it. */
    private static void deploy(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.player.setXRot(-90));
        ctx.waitTicks(1);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        ctx.waitTicks(1);
        ctx.runOnClient(mc -> mc.player.setXRot(0));
    }

    private static boolean lockedAt(Minecraft mc, double[] at) {
        ArmorStand a = stand(mc, at);
        return a != null && DeployedOrbs.lockedId() == a.getId();
    }

    private static ArmorStand stand(Minecraft mc, double[] at) {
        return PetWorldTest.entity(mc, at, ArmorStand.class, a -> true);
    }
}
