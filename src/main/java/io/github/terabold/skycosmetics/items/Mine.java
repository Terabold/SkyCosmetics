package io.github.terabold.skycosmetics.items;

import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.pet.PetTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Whose item a stack is. "Every item of this type" looks show only on the player's own items: what they wear,
 * hold and carry, their summoned pet, the menus that only hold their things (Wardrobe, Ender Chest...), and items
 * My Items remembers. Never on items in the auction house, bazaar, trades, shops or on other players, so nobody
 * sees a skin on an item they might buy.
 *
 * Once per tick it notes the UUIDs in the player's inventory and in the open menu. A stack's answer is cached
 * with its look ({@code Cosmetics}) and asked again only when {@link #version} changes.
 */
public final class Mine {
    /** UUIDs in the inventory, on the cursor, the summoned pet, and in a menu that only shows your things. */
    private static volatile Set<String> live = Set.of();
    /** UUIDs in the open menu's own slots when it is not one of yours: an auction, a trade, a shop... */
    private static volatile Set<String> foreign = Set.of();
    private static volatile int version;
    private static int ownedVersion = -1;

    /** The last stacks scanned per slot, so an unchanged slot costs one reference compare. */
    private static final Map<Slot, Seen> SLOTS = new IdentityHashMap<>();
    private static final Seen[] INVENTORY = new Seen[64];
    /** This tick's sets, reused; copied out only when they differ from the last. */
    private static final Set<String> NOW_LIVE = new HashSet<>(), NOW_FOREIGN = new HashSet<>();
    private static AbstractContainerScreen<?> menuScreen;
    private static boolean menuOwn;

    private record Seen(ItemStack stack, String uuid) {}

    private Mine() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                tick(mc);
            } catch (RuntimeException e) {
                Io.failed("Noting which items are yours", e);
            }
        });
    }

    /** Changes when the player's items or the open menu change: cached answers are then asked again. */
    public static int version() {
        return version;
    }

    /**
     * Whether "every item of this type" looks may show on {@code s} ({@code uuid} its SkyBlock UUID, or null).
     * An item without a UUID counts only while it is in the player's own inventory.
     */
    public static boolean allows(ItemStack s, String uuid) {
        if (uuid != null && live.contains(uuid)) return true;
        if (inInventory(s)) return true;
        return uuid != null && !foreign.contains(uuid) && OwnedItems.knows(uuid);
    }

    /** The stack itself is in the player's inventory, armor, offhand or on the cursor. */
    private static boolean inInventory(ItemStack s) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc == null ? null : mc.player;
        if (p == null || s == null || s.isEmpty()) return false;
        Inventory inv = p.getInventory();
        for (int i = 0, n = inv.getContainerSize(); i < n; i++) if (inv.getItem(i) == s) return true;
        return p.containerMenu != null && p.containerMenu.getCarried() == s;
    }

    static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        Set<String> nowLive = NOW_LIVE, nowForeign = NOW_FOREIGN;
        nowLive.clear();
        nowForeign.clear();
        if (p != null) {
            Inventory inv = p.getInventory();
            int n = Math.min(inv.getContainerSize(), INVENTORY.length);
            for (int i = 0; i < n; i++) add(nowLive, INVENTORY[i] = seen(INVENTORY[i], inv.getItem(i)));
            if (p.containerMenu != null) add(nowLive, seen(null, p.containerMenu.getCarried()));
            Cosmetics.Ident pet = PetTracker.currentIdent();
            if (pet != null && pet.uuid() != null) nowLive.add(pet.uuid());
            menu(mc, p, nowLive, nowForeign);
        }
        int owned = OwnedItems.version();
        if (!nowLive.equals(live) || !nowForeign.equals(foreign) || owned != ownedVersion) {
            live = Set.copyOf(nowLive);
            foreign = Set.copyOf(nowForeign);
            ownedVersion = owned;
            version++;
        }
    }

    /** The open menu's own slots: yours in the Wardrobe, Ender Chest...; foreign anywhere else. */
    private static void menu(Minecraft mc, LocalPlayer p, Set<String> live, Set<String> foreign) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> acs)) {
            menuScreen = null;
            SLOTS.clear();
            return;
        }
        if (acs != menuScreen) { // a menu's title never changes while it is open
            menuScreen = acs;
            menuOwn = OwnedItems.menuSource(acs.getTitle().getString()) != null;
            SLOTS.clear();
        }
        Set<String> into = menuOwn ? live : foreign;
        for (Slot slot : acs.getMenu().slots) {
            if (slot.container == p.getInventory()) continue;
            Seen s = seen(SLOTS.get(slot), slot.getItem());
            SLOTS.put(slot, s);
            add(into, s);
        }
    }

    /** The UUID of what a slot holds now, identified again only when the stack changed. */
    private static Seen seen(Seen last, ItemStack s) {
        if (last != null && last.stack() == s) return last;
        Cosmetics.Ident id = s == null || s.isEmpty() ? null : Cosmetics.identify(s);
        return new Seen(s, id == null ? null : id.uuid());
    }

    private static void add(Set<String> into, Seen s) {
        if (s != null && s.uuid() != null) into.add(s.uuid());
    }
}
