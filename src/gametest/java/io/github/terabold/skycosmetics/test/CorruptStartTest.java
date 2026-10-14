package io.github.terabold.skycosmetics.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.slots.SlotLocks;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static io.github.terabold.skycosmetics.test.StudioLayoutTest.check;

/**
 * The client started with every file SkyCosmetics saves broken (build.gradle copies src/gametest/corrupt-config in
 * first): junk bytes, cut-off JSON, 100,000 nested arrays, an empty file, the wrong JSON at the root, and valid JSON
 * whose every value has the wrong type. Getting here means none of it crashed the game. Unreadable files must be set
 * aside, never saved over; the rest are read with the bad values left out; every other test then runs on top.
 */
public class CorruptStartTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("skycosmetics");
        // The repo thread reads captured.json, timings.json and the published skins while it builds the catalog.
        ctx.waitFor(mc -> !Repo.loading() && Repo.get().skin("NECRON_DIAMOND_KNIGHT") != null, 20 * 180);

        for (String name : List.of("items", "looks", "favorites", "captured", "updates")) {
            check(setAside(dir, name), name + ".json could not be read and was set aside, not saved over");
        }

        JsonObject locks = ctx.computeOnClient(mc -> SlotLocks.testFile());
        JsonObject xy = locks.getAsJsonObject("buckets").getAsJsonObject("x|y");
        check(xy != null && xy.getAsJsonArray("locked").equals(single(1))
            && xy.getAsJsonObject("binds").entrySet().size() == 1 && xy.getAsJsonObject("binds").get("11").getAsInt() == 2,
            "slot-locks.json keeps only real slots and bindings: " + xy);
        check(!locks.getAsJsonObject("buckets").has("bad|bucket") && !locks.getAsJsonObject("lastProfile").has("x"),
            "and drops entries of the wrong shape: " + locks);

        JsonObject s = ctx.computeOnClient(mc -> Settings.snapshot());
        for (String key : List.of("slotLocks", "rarityBackgrounds", "nametags", "hand", "updates", "zoom", "tooltips", "headSize")) {
            check(s.get(key) != null && s.get(key).isJsonObject(), "settings '" + key + "' falls back to a working value: " + s.get(key));
        }
        check(s.getAsJsonObject("slotLocks").get("on").getAsBoolean(), "a wrong-typed section keeps its defaults");
        check(s.getAsJsonObject("updates").get("channel").getAsJsonPrimitive().isString()
            && s.getAsJsonObject("updates").get("check").getAsJsonPrimitive().isBoolean(), "updates settings keep their types");
        check(s.get("problemAlerts").getAsJsonPrimitive().isBoolean() && s.get("learnTiming").getAsJsonPrimitive().isBoolean(),
            "on/off settings stay on/off: " + s.get("problemAlerts") + " " + s.get("learnTiming"));
        // Every number that comes back is a real one.
        check(!s.toString().contains("Infinity") && !s.toString().contains("NaN"), "no NaN or infinity survives: " + s);
        System.out.println("[SkyCosmeticsTest] start with every saved file broken: no crash, unreadable files set aside, bad values dropped");
    }

    private static JsonArray single(int v) {
        JsonArray a = new JsonArray();
        a.add(v);
        return a;
    }

    private static boolean setAside(Path dir, String base) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(p -> p.getFileName().toString().startsWith(base + ".broken-"));
        } catch (IOException e) {
            return false;
        }
    }
}
