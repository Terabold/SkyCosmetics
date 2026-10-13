package io.github.terabold.skycosmetics.data;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Published skins: what a supplement may carry, what is refused, and that the live catalog takes them in. */
public final class RemoteTest implements FabricClientGameTest {
    private static final String SKIN = "TEST_PUBLISHED_SKIN", BAD = "TEST_PUBLISHED_BAD";

    private static String texture(String url) {
        return Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}").getBytes(StandardCharsets.UTF_8));
    }

    private static String supplement(String format) {
        String good = texture("http://textures.minecraft.net/texture/" + "ab12".repeat(16));
        String bad = texture("https://example.com/texture/" + "cd34".repeat(16));
        return "{\"format\":\"" + format + "\",\"version\":1,\"generatedAt\":1,"
            + "\"skins\":{\"" + SKIN + "\":{\"name\":\"Published Test Skin\",\"color\":\"#FFAA00\",\"kind\":\"SKIN\",\"texture\":\"" + good + "\",\"use\":\"HELMET\"},"
            + "\"" + BAD + "\":{\"name\":\"Elsewhere\",\"kind\":\"SKIN\",\"texture\":\"" + bad + "\"}},"
            + "\"animated\":{},\"timings\":{\"TEST_PUBLISHED_ANIMATION\":{\"frames\":2,\"ticksPerTexture\":[40,2]},"
            + "\"TEST_PUBLISHED_JUNK\":{\"frames\":2,\"ticksPerTexture\":[0,99999]}}}";
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    @Override public void runTest(ClientGameTestContext ctx) {
        var s = Remote.parse(supplement(Remote.FORMAT));
        check(s.skins().stills.containsKey(SKIN), "a published Mojang texture is read");
        check(!s.skins().stills.containsKey(BAD), "a texture from anywhere else is refused");
        check(s.timings().skins.containsKey("TEST_PUBLISHED_ANIMATION") && !s.timings().skins.containsKey("TEST_PUBLISHED_JUNK"),
            "only real timings are read");
        boolean refused = false;
        try { Remote.parse(supplement("something-else")); } catch (RuntimeException e) { refused = true; }
        check(refused, "a file in another format is not a supplement");

        Captured local = new Captured();
        local.stills.put(SKIN, new Captured.Still("Mine", 0xFFFFFFFF, SkinEntry.Kind.SKIN, s.skins().stills.get(SKIN).texture(), 1));
        check(Remote.merge(s, local).stills.get(SKIN).name().equals("Mine"), "what you learned yourself wins over a published entry");
        Timings bundled = new Timings();
        bundled.skins.put("TEST_PUBLISHED_ANIMATION", new Timings.Timing(new int[]{20, 20}, 1));
        check(Remote.merge(s, bundled).skins.get("TEST_PUBLISHED_ANIMATION").ticks()[0] == 40, "a published timing replaces a shipped one");
        check(java.util.Arrays.equals(Timings.smooth(new int[]{3, 2, 3, 3, 4, 3}), new int[]{3, 3, 3, 3, 3, 3}), "measuring jitter is steadied");
        check(java.util.Arrays.equals(Timings.smooth(new int[]{61, 2}), new int[]{61, 2}), "a blink keeps its long open frame");
        check(java.util.Arrays.equals(Timings.smooth(new int[]{2, 2, 6, 2}), new int[]{2, 2, 6, 2}), "a real hold is kept");

        ctx.waitFor(mc -> !Repo.loading() && Repo.get().skins.size() > 100, 20 * 120);
        var file = Remote.file();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, supplement(Remote.FORMAT));
            reload();
            check(Repo.get().skin(SKIN) != null && Repo.get().skin(BAD) == null, "the live catalog shows published skins");
        } catch (java.io.IOException e) {
            throw new AssertionError("published skins file", e);
        } finally {
            try { Files.deleteIfExists(file); } catch (java.io.IOException ignored) { }
            reload();
        }
        check(Repo.get().skin(SKIN) == null, "removing the file removes its skins");
        System.out.println("[SkyCosmeticsTest] published skins checks passed");
    }

    private static void reload() {
        CountDownLatch done = new CountDownLatch(1);
        Repo.reload(done::countDown);
        try {
            if (!done.await(120, TimeUnit.SECONDS)) throw new AssertionError("repo reload timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
