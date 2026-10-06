package io.github.terabold.skycosmetics.test;

import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.HeadSwap;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Textures;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import io.github.terabold.skycosmetics.pet.PetDebug;
import io.github.terabold.skycosmetics.pet.PetTracker;
import io.github.terabold.skycosmetics.pet.WorldPet;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Your summoned pet in the world, the way Hypixel may spawn it: an invisible
 * armour stand (marker or not) or an item display wearing the pet's head, with
 * a "[Lv100] Golden Dragon" nametag stand over it. Two stands wear the same
 * Golden Dragon head; only the nearer one is "yours" and gets the skin, and it
 * goes back to Hypixel's head as soon as chat says the pet was despawned. A
 * head whose texture is unknown is still found by the nametag above it. Then
 * the other shapes a 26.1 head may take: a non-head item with a profile, a
 * mob's helmet, a head held in a hand, an item display of another item, an
 * NPC's helmet (but never a real player's or a dropped item). Last, a crowded
 * hub: your pet keeps the lock among other players' Golden Dragons, and one
 * matching pass over 300 entities stays cheap. And head values Minecraft's
 * decoder rejects never swap a head to Steve. Then {@link PetChoiceTest}: two
 * pets of the same type, each with its own look.
 */
final class PetWorldTest {
    private static final String PET_UUID = "t-pet-world";
    /** A uuid look, so it must win over the "all Golden Dragons" type look the main test set. */
    private static final String SKIN = "PET_SKIN_GOLDEN_DRAGON_ANUBIS";
    private static final String HEAD = "GOLDEN_DRAGON;4";
    /** A head that is no Golden Dragon texture at all: only the nametag can tie it to the pet. */
    private static final String ALIEN = "NECRON_DIAMOND_KNIGHT";
    private static final Identifier HEAD_MODEL = Identifier.withDefaultNamespace("player_head");

    private PetWorldTest() {}

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String texture = ctx.computeOnClient(mc -> Repo.get().skin(HEAD).textures[0]);
        // Ours beside you, a lookalike (someone else's Golden Dragon) further off on the other side.
        double[] ours = ctx.computeOnClient(mc -> around(mc, 1.3, 0.6, 0));
        double[] other = ctx.computeOnClient(mc -> around(mc, -2.2, 0.6, 0));
        summon(sp, "sspet_ours", ours, texture, true);
        summon(sp, "sspet_other", other, texture, true);

        ctx.runOnClient(mc -> {
            Looks.put(false, PET_UUID, new Looks.Look(SKIN, null, null, null, "world pet"));
            PetTracker.debugSetCurrent(petItem());
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        });
        ctx.waitFor(mc -> stand(mc, ours) != null && stand(mc, other) != null
            && WorldPet.lockedId() == stand(mc, ours).getId() && ready(SKIN), 20 * 60);
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> {
            assertShows(stand(mc, ours), Repo.get().skin(SKIN).textures, "our pet shows the skin");
            assertShows(stand(mc, other), new String[]{texture}, "a lookalike further away is left alone");
            check(PET_UUID.equals(PetTracker.currentIdent().uuid()), "current pet is the one we set");
            check(!PetTracker.currentPetItem().isEmpty(), "currentPetItem is the pet item");
        });
        ctx.takeScreenshot("skycosmetics-10-world-pet");

        // Despawned in chat: the stand that is still there must go back to Hypixel's head.
        sp.getServer().runCommand("tellraw @a {\"text\":\"You despawned your Golden Dragon!\",\"color\":\"green\"}");
        ctx.waitFor(mc -> PetTracker.currentIdent() == null && WorldPet.lockedId() == -1, 20 * 5);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            assertShows(stand(mc, ours), new String[]{texture}, "despawned pet shows Hypixel's head");
            check(PetTracker.currentPetItem().isEmpty(), "no pet item after despawn");
        });
        ctx.takeScreenshot("skycosmetics-11-world-pet-despawned");

        // A chat-only summon names the pet and its rarity colour; it must resolve to the known pet.
        sp.getServer().runCommand("tellraw @a {\"text\":\"You summoned your \",\"color\":\"green\",\"extra\":["
            + "{\"text\":\"Golden Dragon\",\"color\":\"gold\"},{\"text\":\"!\",\"color\":\"green\"}]}");
        ctx.waitFor(mc -> WorldPet.lockedId() == stand(mc, ours).getId(), 20 * 5);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            check(PET_UUID.equals(PetTracker.currentIdent().uuid()), "chat summon resolved to the known pet");
            assertShows(stand(mc, ours), Repo.get().skin(SKIN).textures, "re-summoned pet shows the skin again");
        });
        ctx.takeScreenshot("skycosmetics-12-world-pet-chat-summon");

        // Not a marker: a plain invisible stand wearing the head is found just the same.
        sp.getServer().runCommand("kill @e[tag=sspet]");
        summon(sp, "sspet_plain", ours, texture, false);
        ctx.waitFor(mc -> {
            ArmorStand a = entity(mc, ours, ArmorStand.class, s -> !s.isMarker());
            return a != null && WorldPet.lockedId() == a.getId();
        }, 20 * 5);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> assertShows(entity(mc, ours, ArmorStand.class, s -> !s.isMarker()),
            Repo.get().skin(SKIN).textures, "a non-marker stand shows the skin"));
        ctx.takeScreenshot("skycosmetics-13-world-pet-plain-stand");

        // An item display carrying the head: drawn through the item model, same skin.
        sp.getServer().runCommand("kill @e[tag=sspet]");
        double[] high = {ours[0], ours[1] + 1.4, ours[2], ours[3]};
        sp.getServer().runCommand(String.format(Locale.ROOT,
            "summon minecraft:item_display %.3f %.3f %.3f {Tags:[\"sspet\"],item:{id:\"minecraft:player_head\","
                + "count:1,components:{\"minecraft:profile\":{properties:[{name:\"textures\",value:\"%s\"}]}}}}",
            high[0], high[1], high[2], texture));
        ctx.waitFor(mc -> {
            Display.ItemDisplay d = entity(mc, high, Display.ItemDisplay.class, x -> true);
            return d != null && WorldPet.lockedId() == d.getId();
        }, 20 * 5);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> assertShows(entity(mc, high, Display.ItemDisplay.class, x -> true),
            Repo.get().skin(SKIN).textures, "an item display head shows the skin"));
        ctx.takeScreenshot("skycosmetics-14-world-pet-item-display");

        // The world texture is unknown: only "[Lv100] Golden Dragon" over a head says it is ours.
        // Decoys: the same unknown head nearer to you with no nametag, one under another
        // pet's nametag, and one under a lower-level Golden Dragon (someone else's).
        sp.getServer().runCommand("kill @e[tag=sspet]");
        String alien = ctx.computeOnClient(mc -> Repo.get().skin(ALIEN).textures[0]);
        double[] tagged = ctx.computeOnClient(mc -> around(mc, 1.6, 1.5, 0));
        double[] bare = ctx.computeOnClient(mc -> around(mc, -1.0, 0.8, 0));
        double[] ender = ctx.computeOnClient(mc -> around(mc, -2.6, 1.8, 0));
        double[] lower = ctx.computeOnClient(mc -> around(mc, -0.3, 1.4, 0));
        summon(sp, "sspet_tagged", tagged, alien, true);
        nametag(sp, tagged, "§8[§7Lv100§8] §6Golden Dragon", 0.6);
        summon(sp, "sspet_bare", bare, alien, true);
        summon(sp, "sspet_ender", ender, alien, true);
        nametag(sp, ender, "§8[§7Lv100§8] §5Ender Dragon", 0.6);
        summon(sp, "sspet_lower", lower, alien, true);
        nametag(sp, lower, "§8[§7Lv57§8] §6Golden Dragon", 0.6);
        ctx.waitFor(mc -> {
            ArmorStand a = headStand(mc, tagged);
            return a != null && WorldPet.lockedId() == a.getId();
        }, 20 * 5);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            assertShows(headStand(mc, tagged), Repo.get().skin(SKIN).textures, "the head under our nametag shows the skin");
            assertShows(headStand(mc, bare), new String[]{alien}, "a nearer head without a nametag is left alone");
            assertShows(headStand(mc, ender), new String[]{alien}, "a head under another pet's nametag is left alone");
            assertShows(headStand(mc, lower), new String[]{alien}, "a lower-level Golden Dragon is someone else's");

            // The debug command runs, and its report shows the lock and why.
            mc.player.connection.sendCommand("skycosmetics debug pet");
            List<String> report = PetDebug.report();
            report.forEach(l -> System.out.println("[SkyCosmeticsTest] debug pet: " + l));
            String locked = report.stream().filter(l -> l.contains("LOCKED")).findFirst().orElse("");
            check(!locked.contains("IN SET") && locked.contains("score=2") && locked.contains("head=player_head"),
                "debug report lists the locked head, matched by nametag only: " + locked);
            check(report.stream().anyMatch(l -> l.contains("(your pet's nametag)")), "debug report lists our nametag");
            check(report.stream().anyMatch(l -> l.contains("(another pet's nametag)")), "debug report lists other nametags");
            check(report.stream().anyMatch(l -> l.contains("[Lv57] Golden Dragon\" (same pet, lower level")),
                "debug report says a lower-level Golden Dragon is someone else's");
        });
        ctx.takeScreenshot("skycosmetics-15-world-pet-nametag");

        shapes(ctx, sp, texture, alien);
        crowd(ctx, sp, texture, alien);
        readability(ctx, texture);
        System.out.println("[SkyCosmeticsTest] world pet: OK");

        ctx.runOnClient(mc -> {
            PetTracker.debugSetCurrent(ItemStack.EMPTY);
            Looks.put(false, PET_UUID, null);
        });
        sp.getServer().runCommand("kill @e[tag=sspet]");
        PetChoiceTest.run(ctx, sp);
    }

    /**
     * Heads that are not a player_head on a stand's helmet (a 26.1 debug report found none of those):
     * each is ours when it sits under our nametag or shows a Golden Dragon texture, and is drawn as a
     * player head with the skin; its lookalike next to it is left alone.
     */
    private static void shapes(ClientGameTestContext ctx, TestSingleplayerContext sp, String texture, String alien) {
        String[] skin = ctx.computeOnClient(mc -> Repo.get().skin(SKIN).textures);
        ctx.runOnClient(mc -> mc.gui.getChat().clearMessages(false)); // the debug report would hide the screenshots
        String tag = "§8[§7Lv100§8] §6Golden Dragon";

        // A stand's helmet that is a stone button carrying the profile; the decoy is under another pet's nametag.
        sp.getServer().runCommand("kill @e[tag=sspet]");
        double[] button = ctx.computeOnClient(mc -> around(mc, 1.4, 1.2, 0));
        double[] buttonDecoy = ctx.computeOnClient(mc -> around(mc, -1.4, 1.2, 0));
        String marker = "Marker:1b,Invisible:1b,NoGravity:1b";
        summonWith(sp, "armor_stand", marker, "sspet_button", button, "armor.head", "minecraft:stone_button", alien);
        nametag(sp, button, tag, 0.6);
        summonWith(sp, "armor_stand", marker, "sspet_button2", buttonDecoy, "armor.head", "minecraft:stone_button", alien);
        nametag(sp, buttonDecoy, "§8[§7Lv100§8] §5Ender Dragon", 0.6);
        ctx.waitFor(mc -> locked(headStand(mc, button)), 20 * 5);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            assertSkinned(headStand(mc, button), skin, "a stone-button helmet with a profile under our nametag");
            assertShows(headStand(mc, buttonDecoy), new String[]{alien},
                "the same helmet under another pet's nametag is left alone");
            String line = PetDebug.report().stream().filter(l -> l.contains("LOCKED")).findFirst().orElse("");
            check(line.contains("head=stone_button") && line.contains("skin="), "debug shows what the head is drawn with: " + line);
        });
        ctx.takeScreenshot("skycosmetics-15a-world-pet-button-helmet");

        // A mob wearing the head under our nametag; the same mob and head nearer to you, untagged, is left alone.
        sp.getServer().runCommand("kill @e[tag=sspet]");
        double[] mob = ctx.computeOnClient(mc -> around(mc, 1.6, 2.2, 0));
        double[] mobDecoy = ctx.computeOnClient(mc -> around(mc, -1.2, 1.4, 0));
        String villager = "NoAI:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b";
        summonWith(sp, "villager", villager, "sspet_mob", mob, "armor.head", "minecraft:player_head", alien);
        nametag(sp, mob, tag, 2.3);
        summonWith(sp, "villager", villager, "sspet_mob2", mobDecoy, "armor.head", "minecraft:player_head", alien);
        ctx.waitFor(mc -> locked(entity(mc, mob, Villager.class, v -> true)), 20 * 5);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            assertSkinned(entity(mc, mob, Villager.class, v -> true), skin, "a villager wearing the head under our nametag");
            assertShows(entity(mc, mobDecoy, Villager.class, v -> true), new String[]{alien},
                "a villager without a nametag is left alone");
        });
        ctx.takeScreenshot("skycosmetics-15b-world-pet-mob");

        // A stand holding a Golden Dragon head in its hand: found by texture; the same one far away is left alone.
        sp.getServer().runCommand("kill @e[tag=sspet]");
        ctx.waitTicks(40); // the villagers' death animation and poof would hide the screenshot
        double[] hand = ctx.computeOnClient(mc -> around(mc, 1.2, 1.0, 0));
        double[] handFar = ctx.computeOnClient(mc -> around(mc, -6.5, 2.0, 0));
        String arms = "Invisible:1b,NoGravity:1b,ShowArms:1b";
        summonWith(sp, "armor_stand", arms, "sspet_hand", hand, "weapon.mainhand", "minecraft:player_head", texture);
        summonWith(sp, "armor_stand", arms, "sspet_hand2", handFar, "weapon.mainhand", "minecraft:player_head", texture);
        ctx.waitFor(mc -> locked(headStand(mc, hand)), 20 * 5);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            check(HeadSwap.headOf(headStand(mc, hand)).slot() == EquipmentSlot.MAINHAND, "the head is found in the hand");
            assertSkinned(headStand(mc, hand), skin, "a head held in a stand's hand");
            assertShows(headStand(mc, handFar), new String[]{texture}, "the same head further away is left alone");
            ArmorStand a = headStand(mc, hand);
            check(texture(Cosmetics.forEntity(a, EquipmentSlot.HEAD, a.getMainHandItem())).equals(texture),
                "only the locked slot is swapped: a helmet read of the same stack is left alone");
        });
        ctx.takeScreenshot("skycosmetics-15c-world-pet-hand");

        // An item display whose item is paper carrying the profile, under our nametag; an untagged one is left alone.
        sp.getServer().runCommand("kill @e[tag=sspet]");
        double[] paper = ctx.computeOnClient(mc -> around(mc, 1.3, 1.4, 1.0));
        double[] paperDecoy = ctx.computeOnClient(mc -> around(mc, -1.0, 1.0, 1.0));
        summonWith(sp, "item_display", "NoGravity:1b", "sspet_paper", paper, "contents", "minecraft:paper", alien);
        nametag(sp, paper, tag, 0.6);
        summonWith(sp, "item_display", "NoGravity:1b", "sspet_paper2", paperDecoy, "contents", "minecraft:paper", alien);
        ctx.waitFor(mc -> locked(entity(mc, paper, Display.ItemDisplay.class, d -> true)), 20 * 5);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            assertSkinned(entity(mc, paper, Display.ItemDisplay.class, d -> true), skin,
                "an item display of paper with a profile under our nametag");
            assertShows(entity(mc, paperDecoy, Display.ItemDisplay.class, d -> true), new String[]{alien},
                "an untagged paper display is left alone");
            String line = PetDebug.report().stream().filter(l -> l.contains("LOCKED")).findFirst().orElse("");
            check(line.contains("item_display") && line.contains("item=paper"), "debug shows the display's item: " + line);
        });
        ctx.takeScreenshot("skycosmetics-15d-world-pet-paper-display");

        // Who may carry a head: Hypixel's NPCs (fake players, UUID version 2), never a real player or a dropped item.
        ctx.runOnClient(mc -> {
            ItemStack head = new ItemStack(Items.PLAYER_HEAD);
            head.set(DataComponents.PROFILE, Textures.profile(texture));
            RemotePlayer npc = new RemotePlayer(mc.level, new GameProfile(new UUID(0x2000L, 1L), "npc"));
            npc.setItemSlot(EquipmentSlot.HEAD, head);
            HeadSwap.Head h = HeadSwap.headOf(npc);
            check(h != null && h.slot() == EquipmentSlot.HEAD, "an NPC (fake player) wearing a head can be your pet");
            RemotePlayer real = new RemotePlayer(mc.level, new GameProfile(new UUID(0x4000L, 1L), "real"));
            real.setItemSlot(EquipmentSlot.HEAD, head);
            check(HeadSwap.headOf(real) == null, "a real player's head never is");
            Vec3 p = mc.player.position();
            check(HeadSwap.headOf(new ItemEntity(mc.level, p.x, p.y, p.z, head)) == null, "a dropped head never is");
        });
    }

    /**
     * A crowded hub around you: 300 entities within the matcher's reach - stands wearing other heads, other
     * pets' nametags, plain stands, and five other players' Golden Dragons (your pet's texture under the same
     * "[Lv100] Golden Dragon" nametag) 4 to 8 blocks away. Yours, beside you, keeps the lock and wins it
     * back after a re-summon; one matching pass is timed.
     */
    private static void crowd(ClientGameTestContext ctx, TestSingleplayerContext sp, String texture, String alien) {
        String tag = "§8[§7Lv100§8] §6Golden Dragon";
        String[] others = {"§8[§7Lv100§8] §5Ender Dragon", "§8[§7Lv87§8] §6Griffin", "§8[§7Lv100§8] §6Bal", "§8[§7Lv12§8] §aRabbit"};
        sp.getServer().runCommand("kill @e[tag=sspet]");
        double[] ours = ctx.computeOnClient(mc -> around(mc, 1.2, 0.6, 0));
        summon(sp, "sspet_crowd", ours, texture, true);
        nametag(sp, ours, tag, 0.6);
        double[] me = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ(), 0});
        String stand = "summon minecraft:armor_stand %.2f %.2f %.2f {Marker:1b,Invisible:1b,NoGravity:1b,Tags:[\"sspet\"]%s}";
        String head = ",equipment:{head:{id:\"minecraft:player_head\",count:1,components:{\"minecraft:profile\":"
            + "{properties:[{name:\"textures\",value:\"%s\"}]}}}}";
        int entities = 1;
        for (int i = 0; i < 300; i++) {
            double a = i * 2.4, r = i < 5 ? 4 + i : 3 + i % 7; // a spiral 3 to 9 blocks around you
            double[] at = {me[0] + Math.cos(a) * r, me[1] + i % 3 * 0.7, me[2] + Math.sin(a) * r, 0};
            String extra = i < 5 || i % 3 == 0 ? String.format(Locale.ROOT, head, i < 5 ? texture : alien)
                : i % 3 == 1 ? ",CustomNameVisible:1b,CustomName:\"" + others[i % others.length] + "\"" : "";
            sp.getServer().runCommand(String.format(Locale.ROOT, stand, at[0], at[1], at[2], extra));
            if (i < 5) nametag(sp, at, tag, 0.6);
            entities += i < 5 ? 2 : 1;
        }
        int all = entities;
        ctx.waitFor(mc -> mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(10)).size() > all, 20 * 10);
        ctx.waitFor(mc -> locked(headStand(mc, ours)), 20 * 5);
        ctx.waitTicks(25);
        ctx.runOnClient(mc -> {
            int near = mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(10)).size();
            check(locked(headStand(mc, ours)), "among " + near + " entities your pet keeps the lock, not a lookalike's");
            double ms = passMillis(mc, 200);
            System.out.printf(Locale.ROOT, "[SkyCosmeticsTest] crowd: one matching pass over %d entities takes %.3f ms%n", near, ms);
            check(ms < 3, String.format(Locale.ROOT, "one matching pass over %d entities is cheap (%.3f ms)", near, ms));
            PetTracker.debugSetCurrent(ItemStack.EMPTY);
            PetTracker.debugSetCurrent(petItem());
        });
        ctx.waitFor(mc -> locked(headStand(mc, ours)), 20 * 5);
        ctx.waitTicks(70); // past the 3 s settling
        ctx.runOnClient(mc -> check(locked(headStand(mc, ours)), "after a re-summon in the crowd, yours (the nearest) is locked again"));
        ctx.takeScreenshot("skycosmetics-15e-world-pet-crowd");
        sp.getServer().runCommand("kill @e[tag=sspet]");
    }

    /**
     * Head values Minecraft's strict decoder rejects: one with the wrong '=' padding (some repo heads have it)
     * still draws its skin; one that is no skin at all gets a default skin from Minecraft, so it is never
     * ready and can't replace a head with Steve.
     */
    private static void readability(ClientGameTestContext ctx, String texture) {
        String core = texture.replaceAll("=+$", "");
        String padded = core + (core.length() % 4 == 3 ? "==" : "=");
        String broken = Base64.getEncoder().encodeToString("{\"textures\":{\"SKIN\":".getBytes(StandardCharsets.UTF_8));
        check(!decodes(padded), "the test value really has the wrong padding");
        ctx.waitFor(mc -> Textures.ready(padded), 20 * 30);
        ctx.runOnClient(mc -> {
            String value = Textures.profile(padded).partialProfile().properties().get("textures").iterator().next().value();
            check(decodes(value), "a head with the wrong padding is drawn from a value Minecraft can read");
            Textures.ready(broken);
        });
        ctx.waitFor(mc -> mc.playerSkinRenderCache().lookup(Textures.profile(broken)).isDone(), 20 * 10);
        ctx.runOnClient(mc -> {
            check(mc.playerSkinRenderCache().lookup(Textures.profile(broken)).join().isPresent(),
                "Minecraft draws an unreadable head value as a default skin");
            check(!Textures.ready(broken), "an unreadable head value is never ready, so no head turns into Steve");
        });
    }

    private static boolean decodes(String base64) {
        try {
            Base64.getDecoder().decode(base64);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Mean time of one matching pass over what is around you now (WorldPet.pick, run directly). */
    private static double passMillis(Minecraft mc, int passes) {
        try {
            Method current = PetTracker.class.getDeclaredMethod("current");
            current.setAccessible(true);
            Object pet = current.invoke(null);
            Method pick = WorldPet.class.getDeclaredMethod("pick", LocalPlayer.class, current.getReturnType(), boolean.class);
            pick.setAccessible(true);
            for (int i = 0; i < passes; i++) pick.invoke(null, mc.player, pet, false); // warm up the JIT
            long t = System.nanoTime();
            for (int i = 0; i < passes; i++) pick.invoke(null, mc.player, pet, false);
            return (System.nanoTime() - t) / 1e6 / passes;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("[SkyCosmeticsTest] world heads: could not run a matching pass", e);
        }
    }

    private static boolean locked(Entity e) {
        return e != null && WorldPet.lockedId() == e.getId();
    }

    /**
     * A {@code type} entity with {@code nbt} at {@code at}, holding {@code item} with the skin
     * {@code texture} in {@code slot} (an /item replace slot name), tagged for cleanup.
     */
    static void summonWith(TestSingleplayerContext sp, String type, String nbt, String tag, double[] at,
                           String slot, String item, String texture) {
        sp.getServer().runCommand(String.format(Locale.ROOT,
            "summon minecraft:%s %.3f %.3f %.3f {%s,Rotation:[%.1ff,0f],Tags:[\"sspet\",\"%s\"]}",
            type, at[0], at[1], at[2], nbt, at[3], tag));
        sp.getServer().runCommand("item replace entity @e[tag=" + tag + ",limit=1] " + slot + " with " + item
            + "[minecraft:profile={properties:[{name:\"textures\",value:\"" + texture + "\"}]}]");
    }

    /** A point {@code right} blocks to your right and {@code forward} ahead, plus your yaw. */
    static double[] around(Minecraft mc, double right, double forward, double up) {
        Vec3 p = mc.player.position();
        Vec3 f = Vec3.directionFromRotation(0, mc.player.getYRot());
        Vec3 r = new Vec3(-f.z, 0, f.x);
        Vec3 a = p.add(r.scale(right)).add(f.scale(forward)).add(0, up, 0);
        return new double[]{a.x, a.y, a.z, mc.player.getYRot()};
    }

    /** An invisible stand wearing {@code texture}, tagged for cleanup. */
    static void summon(TestSingleplayerContext sp, String tag, double[] at, String texture, boolean marker) {
        sp.getServer().runCommand(String.format(Locale.ROOT,
            "summon minecraft:armor_stand %.3f %.3f %.3f {Marker:%db,Invisible:1b,NoGravity:1b,Rotation:[%.1ff,0f],Tags:[\"sspet\",\"%s\"]}",
            at[0], at[1], at[2], marker ? 1 : 0, at[3], tag));
        sp.getServer().runCommand("item replace entity @e[type=armor_stand,tag=" + tag + ",limit=1] armor.head with "
            + "minecraft:player_head[minecraft:profile={properties:[{name:\"textures\",value:\"" + texture + "\"}]}]");
    }

    /** Hypixel's pet nametag: a marker stand with only a name, floating {@code up} blocks over the head. */
    static void nametag(TestSingleplayerContext sp, double[] at, String name, double up) {
        sp.getServer().runCommand(String.format(Locale.ROOT,
            "summon minecraft:armor_stand %.3f %.3f %.3f {Marker:1b,Invisible:1b,NoGravity:1b,CustomNameVisible:1b,"
                + "CustomName:\"%s\",Tags:[\"sspet\"]}", at[0], at[1] + up, at[2], name));
    }

    /** A pet item as the Pets menu would show it: id PET, petInfo with type, tier and uuid, and its level. */
    private static ItemStack petItem() {
        CompoundTag t = new CompoundTag();
        t.putString("id", "PET");
        t.putString("uuid", PET_UUID);
        t.putString("petInfo", "{\"type\":\"GOLDEN_DRAGON\",\"tier\":\"LEGENDARY\",\"uuid\":\"" + PET_UUID + "\"}");
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        s.set(DataComponents.CUSTOM_NAME, Component.literal("[Lvl 100] Golden Dragon"));
        return s;
    }

    /** The marker stand summoned at {@code at}. Tags never reach the client, but positions do, and these never move. */
    private static ArmorStand stand(Minecraft mc, double[] at) {
        return entity(mc, at, ArmorStand.class, ArmorStand::isMarker);
    }

    /** The head-carrying stand at {@code at} (its nametag stand floats above it). */
    private static ArmorStand headStand(Minecraft mc, double[] at) {
        return entity(mc, at, ArmorStand.class, a -> HeadSwap.headOf(a) != null);
    }

    static <T extends Entity> T entity(Minecraft mc, double[] at, Class<T> type, Predicate<T> filter) {
        Vec3 p = new Vec3(at[0], at[1], at[2]);
        for (T e : mc.level.getEntitiesOfClass(type, mc.player.getBoundingBox().inflate(8))) {
            if (!e.isRemoved() && e.position().distanceToSqr(p) < 0.1 && filter.test(e)) return e;
        }
        return null;
    }

    static boolean ready(String skin) {
        SkinEntry e = Repo.get().skin(skin);
        boolean all = e != null;
        if (e != null) for (String t : e.textures) all &= Textures.ready(t);
        return all;
    }

    /** The head the renderer will be handed for this entity must wear one of {@code expected}. */
    static void assertShows(Entity e, String[] expected, String what) {
        String tex = texture(drawn(e, what));
        check(tex != null && Arrays.asList(expected).contains(tex), what + " (got " + abbreviate(tex) + ")");
    }

    /** As {@link #assertShows}, and whatever item Hypixel used, it is drawn as a player head. */
    static void assertSkinned(Entity e, String[] skin, String what) {
        assertShows(e, skin, what);
        ItemStack drawn = drawn(e, what);
        check(drawn.is(Items.PLAYER_HEAD) && HEAD_MODEL.equals(drawn.get(DataComponents.ITEM_MODEL)),
            what + ": drawn as a player head");
    }

    /** What the renderer is handed for the entity's head: the helmet read for a worn head, the item model otherwise. */
    private static ItemStack drawn(Entity e, String what) {
        check(e != null, what + ": entity exists");
        HeadSwap.Head h = HeadSwap.headOf(e);
        check(h != null, what + ": entity carries a head");
        return h.slot() == EquipmentSlot.HEAD
            ? Cosmetics.forEntity(e, EquipmentSlot.HEAD, h.stack())
            : Cosmetics.forRender(h.stack(), e);
    }

    private static String texture(ItemStack s) {
        ResolvableProfile p = s.get(DataComponents.PROFILE);
        if (p == null) return null;
        for (var prop : p.partialProfile().properties().get("textures")) return prop.value();
        return null;
    }

    private static String abbreviate(String s) {
        return s == null ? "null" : s.length() <= 24 ? s : s.substring(0, 24) + "...";
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("[SkyCosmeticsTest] world heads: " + what);
        System.out.println("[SkyCosmeticsTest] ok: " + what);
    }
}
