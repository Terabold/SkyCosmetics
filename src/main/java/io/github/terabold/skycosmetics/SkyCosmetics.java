package io.github.terabold.skycosmetics;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.terabold.skycosmetics.compat.OtherLooks;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinLearner;
import io.github.terabold.skycosmetics.data.TimingLearner;
import io.github.terabold.skycosmetics.deploy.DeployedOrbs;
import io.github.terabold.skycosmetics.gui.GeneralSection;
import io.github.terabold.skycosmetics.gui.InventoryButton;
import io.github.terabold.skycosmetics.items.Mine;
import io.github.terabold.skycosmetics.items.OwnedItems;
import io.github.terabold.skycosmetics.items.Profiles;
import io.github.terabold.skycosmetics.gui.SettingsScreen;
import io.github.terabold.skycosmetics.gui.StudioScreen;
import io.github.terabold.skycosmetics.gui.StudioSection;
import io.github.terabold.skycosmetics.mixin.AbstractContainerScreenAccessor;
import io.github.terabold.skycosmetics.mixin.RecipeBookComponentAccessor;
import io.github.terabold.skycosmetics.mixin.RecipeBookScreenAccessor;
import io.github.terabold.skycosmetics.pet.PetTracker;
import io.github.terabold.skycosmetics.render.Glints;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * SkyCosmetics: client-side skins, dyes, names and glint for SkyBlock items and pets.
 *
 * The open key (unbound by default; set it in the settings or Controls) over an item
 * in any inventory or menu opens the studio for that item; with no screen open, for the held item.
 * {@code /skycosmetics} opens the settings, {@code /skycosmetics edit} the studio for the held item (or the helmet);
 * {@code /skycosmetics debug pet|orb} is for bug reports.
 *
 * Purely visual and purely local: nothing is sent to the server.
 */
public class SkyCosmetics implements ClientModInitializer {
    public static final String MOD_ID = "skycosmetics";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        Migration.run(); // first: copies config/skinstudio before anything reads config/skycosmetics
        Settings.load();
        Looks.load();
        Favorites.load();
        Repo.reload(Looks::bump);
        SkinLearner.init();
        PetTracker.init();
        DeployedOrbs.init();
        OwnedItems.init();
        Mine.init();
        Profiles.init();
        InventoryButton.register();
        Glints.init();
        OtherLooks.init();
        GeneralSection.register();
        StudioSection.register();
        io.github.terabold.skycosmetics.slots.RarityBackgrounds.init();
        io.github.terabold.skycosmetics.slots.HeadSize.init();
        io.github.terabold.skycosmetics.hand.Hand.init();
        // Last, so saves queued by the other stop hooks are written before exit.
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> Io.flush());

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.skycosmetics.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category)); // unbound: K clashed with other mods

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                if (client.screen == null) openFor(client, null, heldOrHelmet(client));
            }
        });

        // Inside menus the key never reaches consumeClick, so catch it on the screen.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> acs)) return;
            // Typing in another mod's search box must not open the studio.
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, key) -> !openKey.matches(key)
                || typing(s) || !openHovered(client, acs));
            // A side mouse button may be the key too; never left or right click, which menus need.
            ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> click.button() <= GLFW.GLFW_MOUSE_BUTTON_RIGHT
                || !openKey.matchesMouse(click) || !openHovered(client, acs));
        });

        // /skycosmetics opens the settings, like other SkyBlock mods' main commands and Mod Menu's Configure button;
        // /skycosmetics edit opens the studio on the held item. Every node runs something, so no "/skycosmetics ..."
        // ever reaches the server as an unknown command; PetDebug and DeployedOrbs add "pet" and "orb" under
        // "debug". Features may add more debug children, never a command on "skycosmetics" itself (Brigadier would
        // replace this one).
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
            ClientCommands.literal("skycosmetics")
                .executes(ctx -> openLater(() -> SettingsScreen.create(null)))
                .then(ClientCommands.literal("edit")
                    .executes(ctx -> open(ctx.getSource(), heldOrHelmet(Minecraft.getInstance()))))
                .then(ClientCommands.literal("debug").executes(ctx -> {
                    ctx.getSource().sendFeedback(prefix().append(Component.literal("Usage: /skycosmetics debug pet|orb"
                        + " - what the pet or orb reskin sees near you").withStyle(ChatFormatting.GRAY)));
                    ctx.getSource().sendFeedback(prefix().append(Component.literal(TimingLearner.status()).withStyle(ChatFormatting.GRAY)));
                    return 1;
                }))));

        LOG.info("SkyCosmetics ready - /skycosmetics, the inventory brush, or the open key once bound");
    }

    /**
     * The open key in a menu: opens the studio for the SkyBlock item under the mouse, if any.
     * On the next task, not inside this key event: the same keystroke's 'k' character arrives
     * right after and would land in the studio's search box.
     */
    private static boolean openHovered(Minecraft client, AbstractContainerScreen<?> acs) {
        Slot slot = ((AbstractContainerScreenAccessor) acs).skycosmetics$hoveredSlot();
        if (slot == null || !slot.hasItem() || Cosmetics.identify(slot.getItem()) == null) return false;
        ItemStack picked = slot.getItem().copy();
        client.schedule(() -> {
            if (client.screen == acs) openFor(client, acs, picked);
        });
        return true;
    }

    /** A text field has the keyboard: the screen's own, the recipe book's search, or another mod's box. */
    private static boolean typing(Screen s) {
        if (s instanceof AbstractRecipeBookScreen<?> rb) {
            RecipeBookComponent<?> book = ((RecipeBookScreenAccessor) rb).skycosmetics$recipeBook();
            EditBox search = book == null ? null : ((RecipeBookComponentAccessor) book).skycosmetics$searchBox();
            if (search != null && book.isVisible() && search.isFocused()) return true;
        }
        return typing(s.children(), 0);
    }

    private static boolean typing(List<? extends GuiEventListener> children, int depth) {
        for (GuiEventListener c : children) {
            if (c instanceof EditBox e ? e.canConsumeInput() : c instanceof MultiLineEditBox m ? m.isFocused()
                : depth < 3 && c instanceof ContainerEventHandler h && typing(h.children(), depth + 1)) return true;
        }
        return false;
    }

    private static int open(FabricClientCommandSource src, ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        if (stack == null || stack.isEmpty() || Cosmetics.identify(stack) == null) {
            src.sendFeedback(prefix().append(Component.literal(
                "Hold a SkyBlock item, or " + openKeyHint() + ".").withStyle(ChatFormatting.YELLOW)));
            return 0;
        }
        // Chat is still closing while the command runs; switch screens next tick.
        mc.execute(() -> mc.setScreen(new StudioScreen(null, stack.copy())));
        return 1;
    }

    /**
     * Opens a screen on the next task, not inside the command: chat is still closing while the command runs. Built
     * there too, so a failure is logged instead of reaching the chat code.
     */
    private static int openLater(java.util.function.Supplier<Screen> screen) {
        Minecraft mc = Minecraft.getInstance();
        mc.schedule(() -> {
            try {
                mc.setScreen(screen.get());
            } catch (RuntimeException e) {
                Io.failed("Opening the settings", e);
            }
        });
        return 1;
    }

    private static void openFor(Minecraft mc, Screen parent, ItemStack stack) {
        if (stack == null || stack.isEmpty() || Cosmetics.identify(stack) == null) {
            if (mc.player != null && parent == null) {
                mc.player.sendSystemMessage(prefix().append(Component.literal(
                    "Hold a SkyBlock item, or " + openKeyHint() + ".").withStyle(ChatFormatting.YELLOW)));
            }
            return;
        }
        mc.setScreen(new StudioScreen(parent, stack.copy()));
    }

    /** The held SkyBlock item, else the helmet: what the studio opens on without a hovered item. */
    public static ItemStack heldOrHelmet(Minecraft mc) {
        if (mc.player == null) return ItemStack.EMPTY;
        ItemStack held = mc.player.getMainHandItem();
        if (Cosmetics.identify(held) != null) return held;
        return mc.player.getItemBySlot(EquipmentSlot.HEAD);
    }

    /** How to open the studio on a hovered item: with the open key, or first how to bind one. */
    public static String openKeyHint() {
        return openKey == null || openKey.isUnbound()
            ? "set an open key in /skycosmetics to use hovered items" : "hover an item in any menu and press " + openKeyName();
    }

    /** The "Open SkyCosmetics" key mapping; the settings screen rebinds it. */
    public static KeyMapping openKey() {
        return openKey;
    }

    /** The open key's name for display, e.g. "K", or vanilla's "Not Bound". */
    public static String openKeyName() {
        return openKey == null ? "" : openKey.getTranslatedKeyMessage().getString();
    }

    public static net.minecraft.network.chat.MutableComponent prefix() {
        return Component.literal("[SkyCosmetics] ").withStyle(ChatFormatting.LIGHT_PURPLE);
    }
}
