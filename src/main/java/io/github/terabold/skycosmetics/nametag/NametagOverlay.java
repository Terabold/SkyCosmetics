package io.github.terabold.skycosmetics.nametag;

import io.github.terabold.skycosmetics.Io;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
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
                paint(font, buffers, count);
                buffers.endBatch();
            } catch (RuntimeException failure) {
                Io.failed("Drawing nametags after translucent blocks", failure);
            } finally { worldPass = false; clear(); }
        });
    }

    /**
     * Letters first, then their shadows: a shadow only fills in around its letters, so translucent text never shows
     * its own shadow through it. A letter drawn without its shadow is lifted to where a shadowed label has it.
     */
    private static void paint(Font font, MultiBufferSource buffers, int n) {
        var paint = LabelText.buffers(buffers, LabelText.Pass.PAINT);
        var letters = LabelText.buffers(buffers, LabelText.Pass.PAINT, Font.SHADOW_DEPTH);
        for (int i = 0; i < n; i++) {
            Entry e = ENTRIES[i];
            font.drawInBatch(e.text, e.x, e.y, e.color, false, e.pose, e.style.shadow() ? letters : paint, Font.DisplayMode.NORMAL, 0, e.light);
        }
        for (int i = 0; i < n; i++) {
            Entry e = ENTRIES[i];
            if (e.style.shadow()) font.drawInBatch(e.text, e.x, e.y, e.color, true, e.pose, paint, Font.DisplayMode.NORMAL, 0, e.light);
        }
    }

    /**
     * A styled label outside the world pass (the inventory and settings previews), drawn at once the same way when
     * its text is see-through with a shadow, so the preview matches the world. False leaves it to the native draw.
     */
    public static boolean drawNow(Font font, Component text, float x, float y, int color, Matrix4fc pose, MultiBufferSource buffers,
                                  int light, Nametags.DrawStyle style) {
        if (worldPass || !style.shadow() || style.normalAlpha() >= 255 || style.seeThrough()
            || !(buffers instanceof MultiBufferSource.BufferSource batch)) return false;
        Entry e = ENTRIES[0] == null ? (ENTRIES[0] = new Entry()) : ENTRIES[0];
        e.pose.set(pose); e.text = text; e.style = style; e.x = x; e.y = y; e.light = light;
        e.color = style.normalAlpha() << 24 | color & 0xFFFFFF;
        try {
            if (style.plate() != null) {
                style.plate().draw(pose, batch.getBuffer(Plate.renderType(false)), x + style.plate().width() / 2f, y, style.opacity(), light);
                batch.endBatch(Plate.renderType(false));
            }
            font.drawInBatch(text, x, y, e.color, true, e.pose, LabelText.buffers(batch, LabelText.Pass.PRIME_NORMAL), Font.DisplayMode.NORMAL, 0, light);
            batch.endBatch();
            paint(font, batch, 1);
            batch.endBatch();
        } finally {
            e.text = null; e.style = null;
        }
        return true;
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
