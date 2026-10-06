package io.github.terabold.skycosmetics.pet;

import io.github.terabold.skycosmetics.Io;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything {@link PetTracker} learns from, all passive: menus you open, every
 * click in the Pets menu (mouse, number keys, or another mod's such as Odin's
 * pet keybinds), chat lines with Autopet's hover, and the tab list's Pet widget
 * with its held item. Menus are rescanned only when a slot's stack changes; the
 * tab list is read every two seconds.
 */
final class PetSources {
    static final String CLICKED = "your click in the Pets menu";
    private static final Pattern PETS_MENU = Pattern.compile("^(?:\\(\\d+/\\d+\\) )?Pets(?:: \".*\")?(?: \\(\\d+/\\d+\\))? ?$");
    private static final String EQUIPMENT_MENU = "Stats & Equipment";
    private static final int EQUIPMENT_PET_SLOT = 47;

    private static final Pattern SUMMON = Pattern.compile("^You summoned your (?<pet>[\\w '-]+?)(?<skin> ✦)?!$");
    private static final Pattern DESPAWN = Pattern.compile("^You despawned your (?<pet>[\\w '-]+?)(?<skin> ✦)?!$");
    private static final Pattern AUTOPET = Pattern.compile(
        "^Autopet equipped your \\[Lvl (?<level>\\d{1,4})] (?:\\[\\d+[✦⚔]] )?(?<pet>[\\w '-]+?)(?<skin> ✦)?! VIEW RULE(?: \\(\\d+\\))?$");
    private static final Pattern HOLDING = Pattern.compile("^Your pet is now holding (?<item>.{1,64}?)\\.$");
    private static final Pattern REMOVED = Pattern.compile("^You removed .{1,64} from your pet!$");
    /** Autopet's hover is the pet's tooltip, a few hundred characters; anything far longer is not read. */
    private static final int HOVER_MAX = 8192;
    /** Lines of the Pet widget read after the name: the held item and XP. */
    private static final int TAB_EXTRA = 3;
    private static final Pattern TAB_HEADER = Pattern.compile("^\\s*Pet:\\s*$");
    /** Matched on the line without trailing spaces: a trailing {@code \s*} after the lazy name made it quadratic. */
    private static final Pattern TAB_NAME = Pattern.compile(
        "^\\s*\\[Lvl (?<level>\\d{1,3}(?:,\\d{3})?)] (?:\\[\\d+✦] )?(?<pet>[\\w '-]+?)(?<skin> ✦)?$");

    /** PlayerTabOverlay's own (private) sort, so "the line after Pet:" means what it shows. */
    private static final Comparator<PlayerInfo> TAB_ORDER = Comparator
        .comparingInt((PlayerInfo p) -> -p.getTabListOrder())
        .thenComparingInt(p -> p.getGameMode() == GameType.SPECTATOR ? 1 : 0)
        .thenComparing(p -> p.getTeam() == null ? "" : p.getTeam().getName())
        .thenComparing(p -> p.getProfile().name(), String::compareToIgnoreCase);
    private static final int TAB_EVERY = 40;
    /** The widget still shows the previous world's pet for a moment after a switch. */
    private static final long TAB_WORLD_GRACE_MS = 3_000;

    private enum Menu { NONE, PETS, EQUIPMENT }

    private static Screen lastScreen;
    private static Menu lastMenu = Menu.NONE;
    private static int lastSig;
    private static ClientLevel lastLevel;
    private static long levelAt;
    private static int ticks;

    private PetSources() {}

    /** Chat is read inside packet handling: a failure must never get out. Clicks come from a mixin. */
    static void init() {
        // Chat filters (SkyHanni, NoFrills, Skyblocker) often hide the Autopet line; hidden still counts.
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (!overlay) guardedChat(msg);
        });
        ClientReceiveMessageEvents.GAME_CANCELED.register((msg, overlay) -> {
            if (!overlay) guardedChat(msg);
        });
    }

    private static void guardedChat(Component msg) {
        try {
            chat(msg);
        } catch (RuntimeException e) {
            Io.failed("Reading a pet chat line", e);
        }
    }

    static void tick(Minecraft mc) {
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            levelAt = System.currentTimeMillis();
        }
        if (mc.player == null) return;

        Screen screen = mc.screen;
        if (screen != lastScreen) {
            lastScreen = screen;
            lastMenu = menuOf(screen);
            lastSig = 0;
        }
        if (lastMenu != Menu.NONE && screen instanceof AbstractContainerScreen<?> acs) {
            AbstractContainerMenu menu = acs.getMenu();
            Inventory inv = mc.player.getInventory();
            int sig = signature(menu, inv);
            if (sig != lastSig) {
                lastSig = sig;
                if (lastMenu == Menu.PETS) scanPets(menu, inv);
                else scanEquipment(menu, inv);
            }
        }

        if (++ticks % TAB_EVERY == 0 && System.currentTimeMillis() - levelAt > TAB_WORLD_GRACE_MS) tab(mc);
    }

    private static Menu menuOf(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?>)) return Menu.NONE;
        String title = ChatFormatting.stripFormatting(screen.getTitle().getString());
        if (title == null) return Menu.NONE;
        if (PETS_MENU.matcher(title).matches()) return Menu.PETS;
        if (title.equals(EQUIPMENT_MENU)) return Menu.EQUIPMENT;
        return Menu.NONE;
    }

    /** Changes whenever any menu slot is handed a different stack; cheap enough to run every tick. */
    private static int signature(AbstractContainerMenu menu, Inventory inv) {
        int h = 1;
        for (Slot slot : menu.slots) {
            if (slot.container != inv) h = 31 * h + System.identityHashCode(slot.getItem());
        }
        return h == 0 ? 1 : h;
    }

    // --------------------------------------------------------------- menus ---

    /** Pet stacks sit in slots 10-43 inside the border; slot 4 names the selected pet. */
    private static void scanPets(AbstractContainerMenu menu, Inventory inv) {
        Pet active = null;
        boolean none = false;
        for (Slot slot : menu.slots) {
            if (slot.container == inv || !slot.hasItem()) continue;
            int i = slot.getContainerSlot();
            ItemStack s = slot.getItem();
            if (i == 4) {
                none = Pet.loreHas(s, "Selected pet: None");
                continue;
            }
            if (i < 10 || i > 43 || i % 9 == 0 || i % 9 == 8) continue;
            Pet p = Pet.of(s);
            if (p == null || !p.resolved()) continue;
            Pet known = PetTracker.seen(p);
            if (Pet.loreHas(s, "Click to despawn!")) active = known;
        }
        // The active pet may be on another page, so a page without it proves nothing.
        if (active != null) PetTracker.setCurrent(active, PetTracker.Source.MENU, "the Pets menu");
        else if (none) PetTracker.clearCurrent(PetTracker.Source.MENU);
    }

    private static void scanEquipment(AbstractContainerMenu menu, Inventory inv) {
        if (menu.slots.size() <= EQUIPMENT_PET_SLOT) return;
        Slot slot = menu.slots.get(EQUIPMENT_PET_SLOT);
        if (slot.container == inv || !slot.hasItem()) return;
        ItemStack s = slot.getItem();
        if ("Empty Pet Slot".equals(ChatFormatting.stripFormatting(io.github.terabold.skycosmetics.Cosmetics.originalName(s).getString()))) {
            PetTracker.clearCurrent(PetTracker.Source.EQUIPMENT);
            return;
        }
        Pet p = Pet.of(s);
        if (p != null && p.resolved()) {
            PetTracker.setCurrent(PetTracker.seen(p), PetTracker.Source.EQUIPMENT, "the Stats & Equipment menu");
        }
    }

    /**
     * A click in the Pets menu, read before it is sent, whoever made it: your
     * mouse, a number key, or another mod (Odin's pet keybinds click slots
     * directly). A left click summons or despawns at once; any other click on a
     * pet is remembered so the summon line that follows names that pet. A
     * shift-click only toggles a favourite.
     */
    static void containerInput(int containerId, int slotId, int button, ContainerInput input) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || input == ContainerInput.QUICK_MOVE || menuOf(mc.screen) != Menu.PETS) return;
        AbstractContainerMenu menu = player.containerMenu;
        if (menu.containerId != containerId || slotId < 0 || slotId >= menu.slots.size()) return;
        Slot slot = menu.slots.get(slotId);
        if (slot.container == player.getInventory() || !slot.hasItem()) return;
        ItemStack s = slot.getItem();
        Pet p = Pet.of(s);
        if (p == null || !p.resolved()) return;
        boolean left = input == ContainerInput.PICKUP && button == 0;
        if (input == ContainerInput.PICKUP && button == 1) {
            if (Pet.loreHas(s, "Right-click to convert to an item!")) PetTracker.forget(p.uuid);
        } else if (Pet.loreHas(s, "Click to despawn!")) {
            if (left) PetTracker.clearCurrent(PetTracker.Source.CLICK);
        } else if (Pet.loreHas(s, "Left-click to summon!")) {
            Pet known = PetTracker.seen(p);
            PetTracker.clicked(known);
            if (left) PetTracker.setCurrent(known, PetTracker.Source.CLICK, CLICKED);
        }
    }

    // ---------------------------------------------------------------- chat ---

    private static void chat(Component msg) {
        String plain = ChatFormatting.stripFormatting(msg.getString());
        if (plain == null || plain.length() > Pet.MAX_TEXT) return;
        if (plain.startsWith("You despawned your ")) {
            if (DESPAWN.matcher(plain).matches()) PetTracker.clearCurrent(PetTracker.Source.CHAT);
            return;
        }
        if (plain.startsWith("Your pet is now holding ")) {
            Matcher m = HOLDING.matcher(plain);
            if (m.matches()) PetTracker.held(m.group("item").trim());
            return;
        }
        if (plain.startsWith("You removed ")) {
            if (REMOVED.matcher(plain).matches()) PetTracker.held("");
            return;
        }
        boolean summon = plain.startsWith("You summoned your ");
        if (!summon && !plain.startsWith("Autopet equipped your ")) return;
        Styled st = Styled.of(msg);
        Matcher m = (summon ? SUMMON : AUTOPET).matcher(st.text);
        if (!m.matches()) return;
        announce(st, m, summon ? 0 : Pet.level(m.group("level")), summon ? List.of() : hoverHeld(msg),
            summon ? PetTracker.Source.CHAT : PetTracker.Source.AUTOPET);
    }

    private static void announce(Styled st, Matcher m, int level, List<String> held, PetTracker.Source src) {
        boolean star = m.group("skin") != null;
        PetTracker.choose(PetTracker.resolve(m.group("pet").trim(), st.code(m.start("pet")), star, level,
            star ? st.rgb[m.end("skin") - 1] : -1, held, true), src);
    }

    /** The item a chat line's hover says the pet holds: Autopet's hover is the pet's tooltip. */
    private static List<String> hoverHeld(Component msg) {
        List<String> out = new ArrayList<>(1);
        msg.visit((style, s) -> {
            if (!(style.getHoverEvent() instanceof HoverEvent.ShowText t)) return Optional.empty();
            String text = t.value().getString();
            if (text.length() > HOVER_MAX) return Optional.empty();
            for (String line : text.split("\n")) {
                String plain = ChatFormatting.stripFormatting(line);
                if (plain != null && plain.length() <= Pet.MAX_TEXT && plain.startsWith(Pet.HELD)) {
                    out.add(plain.substring(Pet.HELD.length()).trim());
                    return Optional.of(Boolean.TRUE);
                }
            }
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    // ----------------------------------------------------------------- tab ---

    /**
     * The Pet widget (needs /tab's Pet widget on): the pet's name, then its held
     * item and XP. A passive cross-check: it speaks when it disagrees with what
     * we have, or when its held item can settle a guess or a pet only remembered
     * from last session, and never right after a menu, click or chat line, since
     * the widget refreshes a few seconds late.
     */
    private static void tab(Minecraft mc) {
        ClientPacketListener c = mc.getConnection();
        if (c == null || !PetTracker.tabMayAssert()) return;
        // The listed set is unordered; the widget's lines only follow each other in drawn order.
        List<PlayerInfo> infos = new ArrayList<>(c.getListedOnlinePlayers());
        infos.sort(TAB_ORDER);
        boolean header = false;
        Component line = null;
        List<String> extra = new ArrayList<>(TAB_EXTRA);
        for (PlayerInfo info : infos) {
            Component d = info.getTabListDisplayName();
            String s = d == null ? null : ChatFormatting.stripFormatting(d.getString());
            if (line != null) {
                // The widget ends at a blank line.
                if (s == null || s.isBlank() || extra.size() == TAB_EXTRA) break;
                if (s.length() <= Pet.MAX_TEXT) extra.add(s.trim());
                continue;
            }
            if (s == null || s.isBlank()) continue;
            if (header) {
                if (s.length() > Pet.MAX_TEXT) break;
                line = d;
                continue;
            }
            header = s.contains("Pet:") && TAB_HEADER.matcher(s).matches();
        }
        if (line == null) return;
        Styled st = Styled.of(line);
        // Only trailing spaces go, so match indices still index st.rgb.
        Matcher m = TAB_NAME.matcher(st.text.stripTrailing());
        if (!m.matches()) return;
        String name = m.group("pet").trim();
        char rarity = st.code(m.start("pet"));
        boolean star = m.group("skin") != null;
        Pet cur = PetTracker.current();
        if (cur != null && cur.matches(name, rarity, star) && PetTracker.certain()
            && (extra.isEmpty() || PetTracker.source() != PetTracker.Source.SAVED)) {
            return;
        }
        PetTracker.Choice choice = PetTracker.resolve(name, rarity, star, Pet.level(m.group("level")),
            star ? st.rgb[m.end("skin") - 1] : -1, extra, false);
        // Still a guess among the same pets: keep the current one rather than churn.
        if (!choice.sure() && choice.fits().contains(cur)) return;
        PetTracker.choose(choice, PetTracker.Source.TAB);
    }

    // ------------------------------------------------------------- styling ---

    /**
     * A message flattened to plain text with the colour each character is drawn
     * in, from component styles and from any legacy § codes inside the text, so
     * a pet's rarity can be read off its name whichever way Hypixel sent it.
     */
    private static final class Styled {
        final String text;
        final int[] rgb;

        private Styled(String text, int[] rgb) {
            this.text = text;
            this.rgb = rgb;
        }

        static Styled of(Component c) {
            StringBuilder text = new StringBuilder();
            int[][] rgb = {new int[64]};
            c.visit((style, s) -> {
                TextColor col = style.getColor();
                int base = col == null ? -1 : col.getValue();
                int cur = base;
                for (int i = 0; i < s.length(); i++) {
                    char ch = s.charAt(i);
                    if (ch == '§' && i + 1 < s.length()) {
                        ChatFormatting f = ChatFormatting.getByCode(s.charAt(++i));
                        if (f == ChatFormatting.RESET) cur = base;
                        else if (f != null && f.isColor()) cur = f.getColor();
                        continue;
                    }
                    if (text.length() == rgb[0].length) rgb[0] = Arrays.copyOf(rgb[0], rgb[0].length * 2);
                    rgb[0][text.length()] = cur;
                    text.append(ch);
                }
                return Optional.empty();
            }, Style.EMPTY);
            return new Styled(text.toString(), rgb[0]);
        }

        /** Legacy colour char at {@code i} ('6' for gold), or 0 if none or not a named colour. */
        char code(int i) {
            if (i < 0 || i >= text.length() || rgb[i] < 0) return 0;
            for (ChatFormatting f : ChatFormatting.values()) {
                if (f.isColor() && f.getColor() == rgb[i]) return f.getChar();
            }
            return 0;
        }
    }
}
