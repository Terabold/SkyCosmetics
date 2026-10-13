package io.github.terabold.skycosmetics.nametag;

import io.github.terabold.skycosmetics.Io;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Reuses native label submissions but draws styled world labels after glass and translucent entities, so their
 * backgrounds are never hidden behind glass. Labels vanilla shows through walls stay see-through; sneaking players'
 * labels and "visible surfaces only" labels stay depth-tested, exactly where vanilla would hide them.
 */
public final class NametagOverlay {
    private static final Entry[] ENTRIES = new Entry[1024];
    private static int count;
    private static boolean worldPass;

    private static final class Entry {
        final Matrix4f pose = new Matrix4f();
        Component text;
        Nametags.DrawStyle style;
        float x, y;
        int color, light;
    }

    private NametagOverlay() {}

    static void init() {
        LevelRenderEvents.START_MAIN.register(context -> { clear(); worldPass = true; });
        LevelRenderEvents.END_MAIN.register(context -> {
            try {
                if (count == 0 || !NametagSettings.p.enabled) return;
                var buffers = context.bufferSource();
                // Backgrounds first: they never write depth, so the text passes below still draw over them.
                for (int i = 0; i < count; i++) {
                    Entry e = ENTRIES[i];
                    if (e.style.plate() != null) e.style.plate().draw(e.pose, buffers.getBuffer(Plate.renderType(e.style.seeThrough())),
                        e.x + e.style.plate().width() / 2f, e.y, e.style.opacity(), e.light);
                }
                buffers.endBatch(Plate.renderType(true));
                buffers.endBatch(Plate.renderType(false));
                var font = Minecraft.getInstance().font;
                var seeThrough = LabelText.buffers(buffers, LabelText.Pass.PRIME_SEE_THROUGH);
                var normal = LabelText.buffers(buffers, LabelText.Pass.PRIME_NORMAL);
                for (int i = 0; i < count; i++) {
                    Entry e = ENTRIES[i];
                    font.drawInBatch(e.text, e.x, e.y, e.color, e.style.shadow(), e.pose, e.style.seeThrough() ? seeThrough : normal,
                        Font.DisplayMode.NORMAL, 0, e.light);
                }
                buffers.endBatch();
                var paint = LabelText.buffers(buffers, LabelText.Pass.PAINT);
                for (int i = 0; i < count; i++) {
                    Entry e = ENTRIES[i];
                    font.drawInBatch(e.text, e.x, e.y, e.color, e.style.shadow(), e.pose, paint, Font.DisplayMode.NORMAL, 0, e.light);
                }
                buffers.endBatch();
            } catch (RuntimeException failure) {
                Io.failed("Drawing nametags after translucent blocks", failure);
            } finally { worldPass = false; clear(); }
        });
    }

    /**
     * Takes over a styled world label's native draw. Vanilla submits a see-through label twice (a faint through-wall
     * copy and a normal one) and a sneaking label once; the normal submission is kept and redrawn later, the faint
     * copy is dropped. GUI previews and labels past the queue's size keep their native passes.
     */
    public static boolean defer(Component text, float x, float y, int color, Matrix4fc pose,
        Font.DisplayMode mode, int light, Nametags.DrawStyle style) {
        if (!worldPass || count == ENTRIES.length) return false;
        if (mode == Font.DisplayMode.SEE_THROUGH) return true;
        if (mode != Font.DisplayMode.NORMAL) return false;
        Entry e = ENTRIES[count];
        if (e == null) ENTRIES[count] = e = new Entry();
        e.pose.set(pose); e.text = text; e.style = style; e.x = x; e.y = y;
        e.color = style.normalAlpha() << 24 | color & 0xFFFFFF; e.light = light;
        count++;
        return true;
    }

    private static void clear() {
        for (int i = 0; i < count; i++) { ENTRIES[i].text = null; ENTRIES[i].style = null; }
        count = 0;
    }
}
