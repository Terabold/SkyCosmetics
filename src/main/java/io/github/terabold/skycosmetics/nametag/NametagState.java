package io.github.terabold.skycosmetics.nametag;

import net.minecraft.network.chat.Component;

/** Per-frame visibility metadata is separate from the persistent, entity-free style cache. */
public interface NametagState {
    Data skycosmetics$tag();
    final class Data {
        public boolean selected, own, inventory, preview;
        public int kind;
        public float previewFit = 1;
        public Cache cache;
    }
    final class Cache {
        public boolean own;
        public Component source, styled, plateText;
        public long revision = -1, tick = -1;
        public int chromaVersion;
        public String username = "";
        public Cache score;
        public Plate plate;
        public int plateWidth = -1;
        public long plateRevision = -1;
    }
}
