package io.github.terabold.skycosmetics.test;

import net.minecraft.network.protocol.Packet;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the client sent its server during the run, by packet type (only serverbound ones: the integrated server's
 * own sends are left out). The mod must never send chat or a command: the run checks it at the end.
 */
public final class PacketLog {
    private static final Map<String, AtomicInteger> SENT = new ConcurrentHashMap<>();

    private PacketLog() {}

    public static void sent(Packet<?> packet) {
        String name = packet.getClass().getSimpleName();
        if (!name.startsWith("Serverbound")) return;
        SENT.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
    }

    static int count(String type) {
        AtomicInteger n = SENT.get(type);
        return n == null ? 0 : n.get();
    }

    static Map<String, Integer> all() {
        Map<String, Integer> out = new TreeMap<>();
        SENT.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }
}
