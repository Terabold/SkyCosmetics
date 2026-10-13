package io.github.terabold.skycosmetics.slots;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * Where the item being drawn right now sits: set around a menu slot, the HUD hotbar, the item on the cursor and the
 * settings' previews, so an item model hook can tell a menu head from a hotbar head. Render thread only.
 */
public final class SlotArea {
    public static final int OTHER = 0, INVENTORY = 1, MENU = 2, HOTBAR = 3, PREVIEW = 4;

    /** The menu slot being drawn, or null. */
    static Slot slot;
    /** HOTBAR, INVENTORY (the carried item) or PREVIEW while one of those draws; OTHER otherwise. */
    static int forced = OTHER;

    private SlotArea() {}

    public static int current() {
        if (forced != OTHER) return forced;
        Slot s = slot;
        if (s == null) return OTHER;
        return s.container instanceof Inventory ? INVENTORY : MENU;
    }

    public static void enterSlot(Slot s) {
        slot = s;
    }

    public static void leaveSlot() {
        slot = null;
    }

    public static boolean slotPosition(int x, int y) {
        return slot != null && slot.x == x && slot.y == y;
    }

    /** Devonian draws equipment beside the armor slot currently being extracted. */
    static boolean adjacentEquipment(int x, int y) {
        return slot != null && armor(slot) && y == slot.y
            && (x - slot.x == -25 || x - slot.x == 19 || x - slot.x == 52);
    }

    public static void enter(int area) {
        forced = area;
    }

    public static void leave() {
        forced = OTHER;
    }

    /** Your own item in a menu: the inventory and hotbar rows and the armor slots, in any screen. */
    static boolean own(Slot s) {
        return s.container instanceof Inventory;
    }

    /** One of the armor or off-hand slots of your inventory. */
    static boolean armor(Slot s) {
        int i = s.getContainerSlot();
        return s.container instanceof Inventory && i >= Inventory.INVENTORY_SIZE && i <= Inventory.SLOT_OFFHAND;
    }
}
