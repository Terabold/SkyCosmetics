package io.github.terabold.skycosmetics.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Migration;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.data.Catalog;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import io.github.terabold.skycosmetics.data.SkinLearner;
import io.github.terabold.skycosmetics.items.OwnedItems;
import io.github.terabold.skycosmetics.pet.PetTracker;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Server data and config files must never crash, freeze or lose anything: pet items with
 * malformed petInfo, 11-digit pet levels in a name and in Autopet chat, a pet nametag with a
 * long run of spaces, an item nested 300 levels deep, junk in captured.json, an animation whose
 * cycle overflowed, too many learned skins, and a looks.json or settings.json that cannot be read. Also the
 * one-time copy of the old Skin Studio config and open key.
 */
public class DataSafetyTest implements FabricClientGameTest {
    private static final String PET_SKIN = "PET_SKIN_GOLDEN_DRAGON_ANUBIS";
    /** What the server may put in petInfo instead of Hypixel's JSON object of strings. */
    private static final String[] BAD_PET_INFO = {"'{\"type\":null}'", "'{\"type\":{},\"uuid\":[]}'",
        "'{\"type\":[],\"uniqueId\":null}'", "'{\"uuid\":{}}'", "{type:1b,uuid:[I;1,2,3,4]}", "'[1,2]'",
        "'{\"type\":\"BEE\",\"uuid\":{\"a\":1}}'"};

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.waitFor(mc -> !Repo.get().skins.isEmpty(), 20 * 120);
        ctx.runOnClient(mc -> learnedData());
        migration();
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientLevel().waitForChunksRender();
            sp.getServer().runCommand("time set noon");
            ctx.runOnClient(mc -> PetTracker.debugSetCurrent(ItemStack.EMPTY));
            badPetItems(ctx, sp);
            petLevels(ctx, sp);
            longNametag(ctx, sp);
            deepItem(ctx);
            brokenFiles(ctx);
        }
    }

    /** captured.json junk is dropped, a cycle that overflowed still animates, and the cap keeps skins in use. */
    private static void learnedData() {
        String t1 = Repo.get().skin(PET_SKIN).textures[0];
        String t2 = Repo.get().skin("NECRON_DIAMOND_KNIGHT").textures[0];
        String elsewhere = Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":"
            + "\"http://evil.example/texture/" + "ab".repeat(32) + "\"}}}").getBytes(StandardCharsets.UTF_8));
        check(SkinLearner.mojangTexture(t1), "a repo texture is a Mojang skin");
        check(!SkinLearner.mojangTexture(elsewhere) && !SkinLearner.mojangTexture("A".repeat(40_000))
            && !SkinLearner.mojangTexture("not base64!"), "anything else is not");

        JsonObject skins = new JsonObject();
        skins.add("SSTEST_GOOD", still(t1, 1));
        skins.add("SSTEST_ELSEWHERE", still(elsewhere, 1));
        skins.add("SSTEST_HUGE", still("A".repeat(40_000), 1));
        skins.add("not an id!", still(t1, 1));
        skins.addProperty("SSTEST_NOT_AN_OBJECT", "x");
        JsonObject anims = new JsonObject();
        JsonObject overflow = anim(t1, t2, t1);
        JsonArray per = new JsonArray();
        per.add(Integer.MAX_VALUE);
        per.add(Integer.MAX_VALUE);
        per.add(2); // the int sum wrapped to exactly 0: a divide by zero when drawn
        overflow.add("ticksPerTexture", per);
        anims.add("SSTEST_ANIM", overflow);
        JsonObject junk = new JsonObject();
        junk.addProperty("textures", "x");
        junk.add("ticks", new JsonObject());
        anims.add("SSTEST_ANIM_JUNK", junk);
        JsonObject root = new JsonObject();
        root.add("skins", skins);
        root.add("animated", anims);

        Catalog m = Repo.withLearned(root.toString());
        check(m.skin("SSTEST_GOOD") != null && m.skin("SSTEST_GOOD").learned, "a real learned skin is kept");
        check(m.skin("SSTEST_ELSEWHERE") == null && m.skin("SSTEST_HUGE") == null && m.skin("not an id!") == null
            && m.skin("SSTEST_ANIM_JUNK") == null, "junk entries are dropped");
        SkinEntry anim = m.skin("SSTEST_ANIM");
        check(anim != null && anim.animated() && anim.frameTicks[0] == SkinEntry.MAX_FRAME_TICKS, "frame ticks are clamped");
        for (long t = 0; t < 500_000; t += 997) {
            int f = anim.frameAt(t);
            check(f >= 0 && f < 3, "frame " + f + " at tick " + t);
        }

        // 2100 learned stills: the 100 oldest that no look wears make room.
        JsonObject many = new JsonObject();
        for (int i = 0; i < 2100; i++) many.add("SSTEST_CAP_" + i, still(t1, i));
        JsonObject capRoot = new JsonObject();
        capRoot.add("skins", many);
        Looks.put(true, "SSTEST_TYPE", new Looks.Look("SSTEST_CAP_0", null, null, null, "wears a learned skin"));
        Catalog capped = Repo.withLearned(capRoot.toString());
        Looks.put(true, "SSTEST_TYPE", null);
        check(capped.learned == 2000, "2000 learned skins kept: " + capped.learned);
        check(capped.skin("SSTEST_CAP_0") != null, "the oldest is kept while a look wears it");
        check(capped.skin("SSTEST_CAP_1") == null && capped.skin("SSTEST_CAP_100") == null
            && capped.skin("SSTEST_CAP_101") != null && capped.skin("SSTEST_CAP_2099") != null, "the oldest unworn ones go");

        JsonObject deep = new JsonObject();
        JsonObject cur = deep;
        for (int i = 0; i < 100; i++) {
            JsonObject next = new JsonObject();
            cur.add("d", next);
            cur = next;
        }
        JsonObject big = new JsonObject();
        big.addProperty("lore", "x".repeat(100_000));
        check(!Io.fits(deep) && !Io.fits(big) && Io.fits(still(t1, 1)), "only small items are saved whole");
        System.out.println("[SkyCosmeticsTest] learned data checks passed");
    }

    /** Pet items whose petInfo fields are null, objects or arrays: drawn, named and scanned as sent. */
    private static void badPetItems(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        for (int i = 0; i < BAD_PET_INFO.length; i++) {
            sp.getServer().runCommand("item replace entity @a hotbar." + i
                + " with minecraft:player_head[minecraft:custom_data={id:\"PET\",petInfo:" + BAD_PET_INFO[i] + "}]");
        }
        ctx.runOnClient(mc -> Looks.put(true, "PET:BEE", new Looks.Look(null, null, "&dBuzz", null, "bee name")));
        ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
        ctx.waitTicks(25); // drawn every frame, scanned by My Items and the pet tracker
        ctx.takeScreenshot("skycosmetics-40-malformed-pet-items");
        ctx.runOnClient(mc -> {
            for (int i = 0; i < BAD_PET_INFO.length; i++) {
                ItemStack s = mc.player.getInventory().getItem(i);
                check(!s.isEmpty() && Cosmetics.apply(s, true) == s, "drawn as sent: " + BAD_PET_INFO[i]);
                check(Cosmetics.identify(s) != null && Cosmetics.identify(s).uuid() == null, "no uuid: " + BAD_PET_INFO[i]);
                Cosmetics.lookOf(s);
                s.getStyledHoverName();
                s.getHoverName();
            }
            check(Cosmetics.identify(mc.player.getInventory().getItem(0)).type().equals("PET"), "a null type is no type");
            check(mc.player.getInventory().getItem(6).getStyledHoverName().getString().equals("Buzz"),
                "the fields that are text still work");
            Looks.put(true, "PET:BEE", null);
        });
        ctx.setScreen(() -> null);
        sp.getServer().runCommand("clear @a minecraft:player_head");
        System.out.println("[SkyCosmeticsTest] malformed petInfo checks passed");
    }

    /** An 11-digit level in a pet's name or in Autopet chat is no level, not a crash or a disconnect. */
    private static void petLevels(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        ctx.runOnClient(mc -> {
            PetTracker.debugSetCurrent(petItem("t-bee-level", "[Lvl 99999999999] Bee"));
            check("PET:BEE".equals(PetTracker.currentIdent().type()), "a pet named with an 11-digit level is still read");
            PetTracker.debugSetCurrent(ItemStack.EMPTY);
        });
        sp.getServer().runCommand("tellraw @a {\"text\":\"Autopet equipped your [Lvl 99999999999] Bee! VIEW RULE\"}");
        ctx.waitTicks(5);
        check(ctx.computeOnClient(mc -> mc.getConnection() != null && mc.level != null && PetTracker.currentIdent() == null),
            "an 11-digit Autopet level is ignored and the game stays connected");
        sp.getServer().runCommand("tellraw @a {\"text\":\"Autopet equipped your [Lvl 100] Bee! VIEW RULE\"}");
        ctx.waitFor(mc -> PetTracker.currentIdent() != null, 20 * 3);
        check(ctx.computeOnClient(mc -> "PET:BEE".equals(PetTracker.currentIdent().type())), "a real Autopet line is read");
        sp.getServer().runCommand("tellraw @a {\"text\":\"You despawned your Bee!\"}");
        ctx.waitFor(mc -> PetTracker.currentIdent() == null, 20 * 3);
        System.out.println("[SkyCosmeticsTest] pet level checks passed");
    }

    /** A nametag of 60k spaces next to your skinned pet: matched in linear time, so the game keeps running. */
    private static void longNametag(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        ctx.runOnClient(mc -> {
            Looks.put(false, "t-bee-tag", new Looks.Look(PET_SKIN, null, null, null, "bee skin"));
            PetTracker.debugSetCurrent(petItem("t-bee-tag", "[Lvl 100] Bee"));
        });
        double[] p = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        sp.getServer().runCommand(String.format(Locale.ROOT,
            "summon minecraft:armor_stand %.2f %.2f %.2f {Tags:[\"sssafe\"],NoGravity:1b,Invisible:1b,Marker:1b}",
            p[0] + 1, p[1], p[2]));
        ctx.waitFor(mc -> stand(mc) != null, 20 * 5);
        ctx.runOnClient(mc -> stand(mc).setCustomName(Component.literal("[Lv1] a" + " ".repeat(60_000) + "x")));
        long t0 = System.nanoTime();
        ctx.waitTicks(40); // the pet is settling: every 2nd tick reads every nametag near you
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[SkyCosmeticsTest] 40 ticks beside a 60k-character nametag took " + ms + " ms");
        check(ms < 10_000, "a long nametag does not freeze the game: " + ms + " ms");
        sp.getServer().runCommand("kill @e[tag=sssafe]");
        ctx.runOnClient(mc -> {
            PetTracker.debugSetCurrent(ItemStack.EMPTY);
            Looks.put(false, "t-bee-tag", null);
        });
    }

    /** An item nested 300 levels deep is listed in My Items, but saved without it, so items.json still reads. */
    private static void deepItem(ClientGameTestContext ctx) {
        Path items = dir().resolve("items.json");
        ctx.runOnClient(mc -> {
            CompoundTag deep = new CompoundTag();
            CompoundTag cur = deep;
            for (int i = 0; i < 300; i++) {
                CompoundTag next = new CompoundTag();
                cur.put("d", next);
                cur = next;
            }
            CompoundTag tag = new CompoundTag();
            tag.putString("id", "SSTEST_DEEP");
            tag.putString("uuid", "t-deep");
            tag.put("deep", deep);
            ItemStack helmet = new ItemStack(Items.LEATHER_HELMET);
            helmet.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            helmet.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("LEGENDARY HELMET"))));
            mc.player.getInventory().setItem(20, helmet); // this client only: the server never sees it
        });
        ctx.waitFor(mc -> read(items).contains("t-deep"), 20 * 10);
        JsonObject entry = JsonParser.parseString(read(items)).getAsJsonObject().getAsJsonObject("items").getAsJsonObject("t-deep");
        check(entry != null && "HELMET".equals(entry.get("category").getAsString()) && !entry.has("item"),
            "the deep item is listed but not saved whole: " + entry);
        ctx.runOnClient(mc -> {
            mc.player.getInventory().setItem(20, ItemStack.EMPTY);
            OwnedItems.forget("t-deep");
        });
        System.out.println("[SkyCosmeticsTest] deep item checks passed");
    }

    /** A looks.json or settings.json that fails to load is set aside, never saved over, and named in chat. */
    private static void brokenFiles(ClientGameTestContext ctx) {
        Path looks = dir().resolve("looks.json");
        Path settings = dir().resolve("settings.json");
        Set<Path> before = broken();
        ctx.runOnClient(mc -> Looks.put(false, "t-safe", new Looks.Look(null, null, "&aSafe", null, "kept")));
        ctx.waitFor(mc -> read(looks).contains("t-safe"), 20 * 5);

        write(looks, "{\"items\": {oops");
        ctx.runOnClient(mc -> Looks.load());
        check(ctx.computeOnClient(mc -> Looks.byUuid("t-safe") != null), "an unreadable looks.json keeps the looks in memory");
        ctx.waitFor(mc -> read(looks).contains("t-safe"), 20 * 5); // saved again from memory

        write(looks, "{\"items\": {\"t-safe\": {\"name\": \"&aSafe\"}, \"t-bad\": 5}}");
        ctx.runOnClient(mc -> Looks.load());
        check(ctx.computeOnClient(mc -> Looks.byUuid("t-safe") != null && Looks.byUuid("t-bad") == null),
            "the readable looks are kept");

        boolean brush = ctx.computeOnClient(mc -> Settings.brush);
        write(settings, "not json");
        ctx.runOnClient(mc -> {
            Settings.load();
            check(Settings.brush == brush, "an unreadable settings.json keeps the settings");
            Settings.save();
        });

        Set<Path> aside = broken();
        aside.removeAll(before);
        List<String> kept = aside.stream().map(DataSafetyTest::read).toList();
        System.out.println("[SkyCosmeticsTest] set aside: " + aside);
        check(aside.size() == 3, "three files set aside: " + aside);
        check(kept.stream().anyMatch(s -> s.contains("oops")) && kept.stream().anyMatch(s -> s.contains("t-bad"))
            && kept.contains("not json"), "each set-aside file is the one that failed, unchanged");

        ctx.waitTicks(3);
        List<String> chat = ctx.computeOnClient(DataSafetyTest::chat);
        chat.stream().filter(l -> l.contains("set aside")).forEach(l -> System.out.println("[SkyCosmeticsTest] chat: " + l));
        check(chat.stream().anyMatch(l -> l.matches("\\[SkyCosmetics] Could not read looks\\.json, so it was set aside as "
            + "looks\\.broken-\\d{8}-\\d{6}(-\\d)?\\.json\\.")), "chat names the set-aside looks.json");
        check(chat.stream().anyMatch(l -> l.startsWith("[SkyCosmetics] Some looks in looks.json could not be read, so it was set aside as")),
            "chat says when only some looks could not be read");
        check(chat.stream().anyMatch(l -> l.startsWith("[SkyCosmetics] Could not read settings.json, so it was set aside as")),
            "chat names the set-aside settings.json");

        for (Path p : aside) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
        ctx.runOnClient(mc -> Looks.put(false, "t-safe", null));
        System.out.println("[SkyCosmeticsTest] broken file checks passed");
    }

    /** The old config folder is copied whole, once, and never touched; the old open key is carried over once. */
    private static void migration() {
        Path root = FabricLoader.getInstance().getGameDir().resolve("migration-test");
        Path from = root.resolve("skinstudio"), to = root.resolve("skycosmetics");
        try {
            if (Files.exists(root)) { // left by an earlier run in the same game folder
                try (Stream<Path> old = Files.walk(root)) {
                    for (Path p : old.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
                }
            }
            Files.createDirectories(from.resolve("sub"));
            Files.writeString(from.resolve("looks.json"), "{\"looks\":1}");
            Files.writeString(from.resolve("sub/x.json"), "[2]");
            check(Migration.copyConfig(from, to) == 2, "both old files are copied");
            check(Files.readString(to.resolve("looks.json")).equals("{\"looks\":1}")
                && Files.readString(to.resolve("sub/x.json")).equals("[2]"), "the copies are the same");
            check(Files.exists(from.resolve("looks.json")) && Files.exists(from.resolve("sub/x.json")), "the old folder stays");
            check(!Files.exists(root.resolve("skycosmetics.migrating")), "no staging folder is left");
            Files.writeString(to.resolve("looks.json"), "{\"looks\":2}");
            check(Migration.copyConfig(from, to) == 0 && Files.readString(to.resolve("looks.json")).equals("{\"looks\":2}"),
                "an existing new folder is never overwritten");
            check(Migration.copyConfig(root.resolve("nothing"), root.resolve("fresh")) == 0
                && !Files.exists(root.resolve("fresh")), "no old folder: nothing is made");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        String old = "key_key.skinstudio.open:key.keyboard.k";
        check("key.keyboard.k".equals(Migration.oldKey(List.of("version:4790", old, "key_key.jump:key.keyboard.space"))),
            "the old open key is found");
        check(Migration.oldKey(List.of(old, "key_key.skycosmetics.open:key.keyboard.unknown")) == null,
            "a saved new key (even unbound) is never replaced");
        check(Migration.oldKey(List.of("key_key.skinstudio.open:key.keyboard.unknown")) == null
            && Migration.oldKey(List.of("version:4790")) == null, "an unbound or missing old key carries nothing");
        System.out.println("[SkyCosmeticsTest] migration checks passed");
    }

    // ------------------------------------------------------------ helpers ---

    private static ItemStack petItem(String uuid, String name) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        CompoundTag t = new CompoundTag();
        t.putString("id", "PET");
        t.putString("petInfo", "{\"type\":\"BEE\",\"tier\":\"LEGENDARY\",\"uuid\":\"" + uuid + "\"}");
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return s;
    }

    private static ArmorStand stand(Minecraft mc) {
        List<ArmorStand> l = mc.level.getEntitiesOfClass(ArmorStand.class, mc.player.getBoundingBox().inflate(4));
        return l.isEmpty() ? null : l.getFirst();
    }

    private static JsonObject still(String texture, long at) {
        JsonObject o = new JsonObject();
        o.addProperty("name", "Test Skin");
        o.addProperty("kind", "SKIN");
        o.addProperty("texture", texture);
        o.addProperty("learnedAt", at);
        return o;
    }

    private static JsonObject anim(String... textures) {
        JsonObject o = new JsonObject();
        o.addProperty("ticks", 2);
        JsonArray a = new JsonArray();
        for (String t : textures) a.add("00000000-0000-0000-0000-000000000000:" + t);
        o.add("textures", a);
        return o;
    }

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("skycosmetics");
    }

    private static Set<Path> broken() {
        try (Stream<Path> s = Files.list(dir())) {
            return new HashSet<>(s.filter(p -> p.getFileName().toString().contains(".broken-")).toList());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** The file's text, or "" while it is missing or being replaced. */
    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void write(Path p, String text) {
        try {
            Files.writeString(p, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> chat(Minecraft mc) {
        try {
            Field f = ChatComponent.class.getDeclaredField("allMessages");
            f.setAccessible(true);
            List<String> out = new ArrayList<>();
            for (GuiMessage m : (List<GuiMessage>) f.get(mc.gui.getChat())) out.add(m.content().getString());
            return out;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("[SkyCosmeticsTest] failed: " + what);
    }
}
