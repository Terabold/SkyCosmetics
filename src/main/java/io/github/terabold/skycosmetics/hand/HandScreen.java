package io.github.terabold.skycosmetics.hand;

import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.gui.ColorPopup;
import io.github.terabold.skycosmetics.gui.hub.ActionButton;
import io.github.terabold.skycosmetics.gui.hub.CloseButton;
import io.github.terabold.skycosmetics.gui.hub.OptionSlider;
import io.github.terabold.skycosmetics.gui.hub.SegmentedChoice;
import io.github.terabold.skycosmetics.gui.hub.ToggleSwitch;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Host;
import net.minecraft.ChatFormatting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The live viewmodel editor: a panel at the side opposite your main hand while the game keeps drawing your real
 * first-person hand next to it, so what you see is exactly what you get. Nothing is paused, dimmed or blurred. In
 * third person the view turns to first person while it is open, as F5 would (a client-only view setting), and back
 * when it closes.
 *
 * From the top: quick presets, then the pose in groups (Position, Size, Rotation, Swing) and the settings for how
 * the hand behaves. Sliders apply as you drag and save when you let go; right-click one to put it back to
 * Minecraft's. Undo returns the pose to how it was when the editor opened. Test Swing plays a swing on screen only.
 */
public final class HandScreen extends Screen implements Host {
    private static final int PAD = 6, IN = 8, HEAD = 30, FOOT = 26, LABEL = 64, GAP = 4;
    /** The width of every slider's value ("Default", "-180°"), so the tracks line up. */
    private static final int VALUE = 40;
    /** The panel stays a little see-through so the hand behind it shows. */
    private static final int PANEL = 0xE8000000 | Theme.BODY & 0xFFFFFF;
    /** Ticks between swings while Repeat is on: a swing, then a short rest to see the pose still. */
    private static final int REPEAT_REST = 12;

    private final Screen parent;
    /** The view before the editor opened, and F1's hidden-HUD state: both come back when it closes. */
    private CameraType camera;
    private boolean hideGui;
    /** The pose as it was when the editor opened: Undo puts it back. */
    private HandPose undo;
    private boolean repeat;
    private int restTicks;

    /** The editor's pages: the pose, its swing, and how the hand behaves. */
    private enum Tab {
        POSE("Pose"), SWING("Swing"), BEHAVIOUR("Behaviour");
        final String title;
        Tab(String title) { this.title = title; }
    }

    private Tab tab = Tab.POSE;

    // Built in init().
    private int px, pw;
    private final List<AbstractWidget> sliders = new ArrayList<>();
    private final List<Runnable> refreshers = new ArrayList<>();
    private ActionButton repeatButton;

    /** {@code key} is kept for callers; every item shares the one pose now. */
    public HandScreen(Screen parent, String key) {
        super(Component.translatable("skycosmetics.hand.editor"));
        this.parent = parent;
    }

    public String key() {
        return Hand.EVERYTHING;
    }

    // ------------------------------------------------------------- layout ---

    @Override
    protected void init() {
        if (camera == null) {
            camera = minecraft.options.getCameraType();
            hideGui = minecraft.options.hideGui;
            view(minecraft, CameraType.FIRST_PERSON);
            minecraft.options.hideGui = false; // F1 hides the hand too
        }
        if (undo == null) undo = pose();
        sliders.clear();
        refreshers.clear();
        pw = Math.clamp(width * 2 / 5, Math.min(180, width - 2 * PAD), 250);
        boolean rightHanded = minecraft.player == null || minecraft.player.getMainArm() == HumanoidArm.RIGHT;
        px = rightHanded ? PAD : width - PAD - pw;
        int inner = pw - 2 * IN, x0 = px + IN;

        CloseButton close = addRenderableWidget(new CloseButton(Component.translatable("gui.done"), Component.translatable("gui.done"), b -> onClose()));
        close.setPosition(px + pw - IN - CloseButton.SIZE, PAD + 6);
        repeatButton = null;

        // One tab at a time, so each fits without scrolling on a normal screen (a very small one still scrolls).
        SegmentedChoice<Tab> tabs = addRenderableWidget(new SegmentedChoice<>(Component.literal("Page"), inner,
            new Control.Choice<>(List.of(Tab.values()), t -> Component.literal(t.title), () -> tab, t -> { tab = t; rebuildWidgets(); }), this));
        tabs.setPosition(x0, PAD + HEAD);
        int top = PAD + HEAD + SegmentedChoice.H + 6, bottom = height - PAD - FOOT;
        int rw = inner - 8; // room for a scrollbar on a very small screen
        LinearLayout rows = LinearLayout.vertical().spacing(3);
        if (!Hand.enabled()) {
            rows.addChild(new MultiLineTextWidget(
                Component.translatable("skycosmetics.hand.editor.off").withColor(Theme.GOLD & 0xFFFFFF), font).setMaxWidth(rw));
        }
        switch (tab) {
            case POSE -> {
                rows.addChild(presets(rw));
                rows.addChild(heading(Component.translatable("skycosmetics.hand.editor.group.position").getString(), rw));
                field(rows, rw, HandPose.Field.X, "Sideways");
                field(rows, rw, HandPose.Field.Y, "Height");
                field(rows, rw, HandPose.Field.Z, "Depth");
                rows.addChild(heading(Component.translatable("skycosmetics.hand.editor.group.size").getString(), rw));
                field(rows, rw, HandPose.Field.SIZE, "Size");
                field(rows, rw, HandPose.Field.SIZE_X, "Stretch X");
                field(rows, rw, HandPose.Field.SIZE_Y, "Stretch Y");
                field(rows, rw, HandPose.Field.SIZE_Z, "Stretch Z");
                rows.addChild(heading(Component.translatable("skycosmetics.hand.editor.group.rotation").getString(), rw));
                choice(rows, rw, "Turn around", List.of(HandPose.Pivot.values()),
                    v -> Component.translatable("skycosmetics.hand.pivot." + v.key), () -> pose().pivot, v -> setPose(pose().with(v)));
                explain(rows, rw, () -> Component.translatable("skycosmetics.hand.pivot." + pose().pivot.key + ".tooltip"));
                field(rows, rw, HandPose.Field.ROT_X, "Pitch");
                field(rows, rw, HandPose.Field.ROT_Y, "Yaw");
                field(rows, rw, HandPose.Field.ROT_Z, "Roll");
            }
            case SWING -> {
                choice(rows, rw, "Style", List.of(HandPose.Swing.values()),
                    v -> Component.translatable("skycosmetics.hand.swing." + v.key), () -> pose().swing, v -> setPose(pose().with(v)));
                explain(rows, rw, () -> Component.translatable("skycosmetics.hand.swing." + pose().swing.key + ".tooltip"));
                AbstractWidget speed = field(rows, rw, HandPose.Field.SPEED, "Speed");
                AbstractWidget tilt = field(rows, rw, HandPose.Field.SWING_TURN, "Tilt");
                List<AbstractWidget> distance = List.of(field(rows, rw, HandPose.Field.SWING_X, "Distance X"),
                    field(rows, rw, HandPose.Field.SWING_Y, "Distance Y"), field(rows, rw, HandPose.Field.SWING_Z, "Distance Z"));
                refreshers.add(() -> {
                    HandPose.Swing sw = pose().swing;
                    speed.active = tilt.active = sw != HandPose.Swing.NONE;
                    for (AbstractWidget d : distance) d.active = sw == HandPose.Swing.MOVING;
                });
                LinearLayout tests = LinearLayout.horizontal().spacing(GAP);
                int half = (rw - GAP) / 2;
                ActionButton test = tests.addChild(new ActionButton(Component.translatable("skycosmetics.hand.editor.test"), half, false, b -> HandSwing.test()));
                test.setTooltip(Tooltip.create(Component.translatable("skycosmetics.hand.editor.test.tooltip")));
                repeatButton = tests.addChild(new ActionButton(Component.empty(), rw - half - GAP, false, b -> {
                    repeat = !repeat;
                    restTicks = 0;
                    refresh();
                }));
                repeatButton.setTooltip(Tooltip.create(Component.translatable("skycosmetics.hand.editor.repeat.tooltip")));
                rows.addChild(tests);
            }
            case BEHAVIOUR -> {
                choice(rows, rw, "Equip", List.of(Hand.Equip.values()),
                    v -> Component.literal(switch (v) { case NORMAL -> "Normal"; case SLOTS -> "On switch"; case OFF -> "Off"; }), Hand::equip, Hand::setEquip);
                explain(rows, rw, () -> Component.translatable("skycosmetics.hand.option.handEquipAnimation.tooltip"));
                OptionSlider sway = new OptionSlider(Component.translatable("skycosmetics.hand.option.handSway"), rw - LABEL - GAP,
                    new Control.Slider(Hand::sway, v -> Hand.setSway((float) v), 0, 2, 0.05, HandScreen::swayLabel), this).resettable(() -> 1)
                    .valueWidth(VALUE);
                row(rows, rw, "Sway", sway);
                refreshers.add(sway::refresh);
                toggle(rows, rw, "handFullSwings", Hand::fullSwings, Hand::setFullSwings);
                toggle(rows, rw, "handIgnoreEffects", Hand::ignoreEffects, Hand::setIgnoreEffects);
                toggle(rows, rw, "handEmptyAndMaps", Hand::emptyAndMaps, Hand::setEmptyAndMaps);
            }
        }

        ScrollableLayout list = new ScrollableLayout(minecraft, rows, Math.max(40, bottom - top));
        list.setY(top);
        list.arrangeElements();
        list.setX(x0);
        list.arrangeElements();
        list.visitWidgets(this::addRenderableWidget);

        // Footer: back to Minecraft's, back to how it was, done.
        int fy = height - PAD - FOOT + 5, third = (inner - 2 * GAP) / 3;
        ActionButton reset = addRenderableWidget(new ActionButton(Component.translatable("skycosmetics.hand.editor.reset"), third, false, b -> setPose(HandPose.VANILLA)));
        reset.setTooltip(Tooltip.create(Component.translatable("skycosmetics.hand.editor.reset.tooltip")));
        reset.setPosition(x0, fy);
        ActionButton back = addRenderableWidget(new ActionButton(Component.literal("Undo"), third, false, b -> setPose(undo)));
        back.setTooltip(Tooltip.create(Component.literal("Puts the pose back to how it was when the editor opened.")));
        back.setPosition(x0 + third + GAP, fy);
        refreshers.add(() -> back.active = !pose().equals(undo));
        ActionButton done = addRenderableWidget(new ActionButton(Component.translatable("gui.done").withStyle(ChatFormatting.GREEN),
            inner - 2 * (third + GAP), true, b -> onClose()));
        done.setPosition(x0 + 2 * (third + GAP), fy);

        preview();
        refresh();
    }

    /** A muted line under a choice saying what the chosen one does; it follows the choice. */
    private void explain(LinearLayout rows, int w, java.util.function.Supplier<Component> text) {
        MultiLineTextWidget line = new MultiLineTextWidget(text.get().copy().withColor(Theme.MUTED & 0xFFFFFF), font).setMaxWidth(w);
        rows.addChild(line);
        refreshers.add(() -> line.setMessage(text.get().copy().withColor(Theme.MUTED & 0xFFFFFF)));
    }

    private StringWidget heading(String text, int w) {
        return new StringWidget(w, 12, Component.literal(text).withColor(Theme.ACCENT & 0xFFFFFF), font);
    }

    /** A labelled row: the name on the left, the control filling the rest. */
    private void row(LinearLayout rows, int w, String label, AbstractWidget control) {
        LinearLayout line = LinearLayout.horizontal().spacing(GAP);
        line.addChild(new StringWidget(LABEL, control.getHeight(), Component.literal(label).withColor(Theme.MUTED & 0xFFFFFF), font));
        line.addChild(control);
        rows.addChild(line);
    }

    /**
     * One number of the pose. Sizes and speed move in powers of two, so 1x sits in the middle and 0.5x is as far
     * from it as 2x; right-click puts it back to Minecraft's.
     */
    private AbstractWidget field(LinearLayout rows, int w, HandPose.Field f, String label) {
        Control.Slider s = f.multiplier()
            ? new Control.Slider(() -> PoseNumbers.toSlider(f, value(f)), v -> set(f, PoseNumbers.fromSlider(f, v)), 0, 1, 0.001,
                v -> PoseNumbers.text(f, PoseNumbers.fromSlider(f, v)))
            : new Control.Slider(() -> value(f), v -> set(f, (float) v), f.min, f.max, f.step, v -> PoseNumbers.text(f, (float) v));
        OptionSlider slider = new OptionSlider(Component.literal(label), w - LABEL - GAP, s, this)
            .resettable(() -> f.multiplier() ? PoseNumbers.toSlider(f, f.vanilla) : f.vanilla).valueWidth(VALUE);
        slider.setTooltip(Tooltip.create(Component.translatable("skycosmetics.hand.editor.hint")));
        row(rows, w, label, slider);
        sliders.add(slider);
        refreshers.add(slider::refresh);
        return slider;
    }

    private <T> void choice(LinearLayout rows, int w, String label, List<T> values, java.util.function.Function<T, Component> name,
                            java.util.function.Supplier<T> get, Consumer<T> set) {
        SegmentedChoice<T> c = new SegmentedChoice<>(Component.literal(label), w - LABEL - GAP, new Control.Choice<>(values, name, get, set), this);
        row(rows, w, label, c);
    }

    private void toggle(LinearLayout rows, int w, String id, BooleanSupplier get, Consumer<Boolean> set) {
        Component title = Component.translatable("skycosmetics.hand.option." + id);
        ToggleSwitch t = new ToggleSwitch(title, get, b -> { set.accept(!get.getAsBoolean()); changed(); });
        t.setTooltip(Tooltip.create(Component.translatable("skycosmetics.hand.option." + id + ".tooltip")));
        LinearLayout line = LinearLayout.horizontal().spacing(GAP);
        line.addChild(new StringWidget(w - ToggleSwitch.W - GAP, ToggleSwitch.H, title.copy().withColor(Theme.TEXT & 0xFFFFFF), font));
        line.addChild(t);
        rows.addChild(line);
    }

    /** Vanilla and three starting points; each sets position, size and rotation and keeps the swing as it is. */
    private LinearLayout presets(int w) {
        LinearLayout line = LinearLayout.horizontal().spacing(GAP);
        int each = (w - 3 * GAP) / 4;
        String[] names = {"Vanilla", "Compact", "Low", "Centered"};
        String[] tips = {"Minecraft's pose.", "A smaller item, a little lower.", "Lower in the view.", "Smaller and nearer the middle."};
        for (int i = 0; i < names.length; i++) {
            int which = i;
            ActionButton b = line.addChild(new ActionButton(Component.literal(names[i]), i == 3 ? w - 3 * (each + GAP) : each, false,
                btn -> setPose(preset(which))));
            b.setTooltip(Tooltip.create(Component.literal(tips[i])));
        }
        return line;
    }

    private HandPose preset(int which) {
        if (which == 0) return HandPose.VANILLA;
        HandPose p = pose();
        for (HandPose.Field f : new HandPose.Field[]{HandPose.Field.X, HandPose.Field.Y, HandPose.Field.Z, HandPose.Field.ROT_X,
            HandPose.Field.ROT_Y, HandPose.Field.ROT_Z, HandPose.Field.SIZE, HandPose.Field.SIZE_X, HandPose.Field.SIZE_Y, HandPose.Field.SIZE_Z}) {
            p = p.with(f, f.vanilla);
        }
        return switch (which) {
            case 1 -> p.with(HandPose.Field.SIZE, .7f).with(HandPose.Field.Y, -.05f);
            case 2 -> p.with(HandPose.Field.Y, -.15f);
            default -> p.with(HandPose.Field.X, -.25f).with(HandPose.Field.SIZE, .85f);
        };
    }

    private static Component swayLabel(double v) {
        if (v == 0) return Component.translatable("skycosmetics.menu.off");
        if (v == 1) return Component.translatable("skycosmetics.hand.sway.normal");
        return Component.literal(Math.round(v * 100) + "%");
    }

    // -------------------------------------------------------------- state ---

    private HandPose pose() {
        HandPose p = Hand.pose(Hand.EVERYTHING);
        return p != null ? p : HandPose.VANILLA;
    }

    private float value(HandPose.Field f) {
        return pose().get(f);
    }

    /** One number, live while dragging; saved when the drag ends ({@link #changed}). */
    private void set(HandPose.Field f, float v) {
        if (value(f) != v) Hand.setPose(Hand.EVERYTHING, pose().with(f, v));
    }

    private void setPose(HandPose p) {
        Hand.setPose(Hand.EVERYTHING, p);
        changed();
    }

    /** Saves, and reads every control again. */
    @Override
    public void changed() {
        Hand.save();
        refresh();
    }

    @Override
    public void openPopup(ColorPopup popup) {
        // No colors here.
    }

    private void refresh() {
        for (Runnable r : refreshers) r.run();
        if (repeatButton != null && tab == Tab.SWING) repeatButton.setMessage(Component.translatable("skycosmetics.hand.editor.repeat",
            Component.translatable(repeat ? "skycosmetics.menu.on" : "skycosmetics.menu.off")));
    }

    /** How many numbers of the pose differ from Minecraft's, for the header. */
    private int changedCount() {
        HandPose p = pose();
        int n = 0;
        for (HandPose.Field f : HandPose.Field.values()) if (p.get(f) != f.vanilla) n++;
        if (p.pivot != HandPose.VANILLA.pivot) n++;
        if (p.swing != HandPose.VANILLA.swing) n++;
        return n;
    }

    /** Shows the pose on your main hand while editing. */
    private void preview() {
        try {
            Hand.preview(Hand.EVERYTHING, sample(Hand.EVERYTHING));
        } catch (RuntimeException e) {
            Io.failed("Previewing a hand pose", e);
            Hand.preview(Hand.EVERYTHING, null);
        }
    }

    /**
     * What the main hand holds while a pose is edited: the held item when the pose applies to it, else an item of
     * yours it applies to, else a plain Minecraft one of that kind. Null keeps the held item.
     */
    static ItemStack sample(String key) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (Hand.EVERYTHING.equals(key) || player == null) return null;
        HandCategory kind = HandCategory.byKey(key);
        if (kind == HandCategory.EMPTY) return ItemStack.EMPTY;
        ItemStack held = player.getMainHandItem();
        if (fits(held, key, kind)) return null;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && fits(s, key, kind)) return s;
        }
        if (kind == null) return null;
        return switch (kind) {
            case SWORD -> new ItemStack(Items.DIAMOND_SWORD);
            case BOW -> new ItemStack(Items.BOW);
            case TOOL -> new ItemStack(Items.DIAMOND_PICKAXE);
            case ROD -> new ItemStack(Items.FISHING_ROD);
            case OTHER -> new ItemStack(Items.BLAZE_ROD);
            case EMPTY -> ItemStack.EMPTY;
        };
    }

    private static boolean fits(ItemStack s, String key, HandCategory kind) {
        if (s.isEmpty()) return false;
        if (kind != null) return HandCategory.of(s) == kind;
        Cosmetics.Ident id = Cosmetics.identify(s);
        return id != null && key.equals(Hand.ITEM + id.type());
    }

    /** As F5 switches the view: only the client's camera, never anything sent. */
    private static void view(Minecraft mc, CameraType to) {
        CameraType before = mc.options.getCameraType();
        if (before == to) return;
        mc.options.setCameraType(to);
        if (before.isFirstPerson() != to.isFirstPerson()) mc.gameRenderer.checkEntityPostEffect(to.isFirstPerson() ? mc.getCameraEntity() : null);
        mc.levelRenderer.needsUpdate();
    }

    @Override
    public void tick() {
        if (!repeat) return;
        if (HandSwing.running()) {
            restTicks = 0;
        } else if (++restTicks >= REPEAT_REST) {
            restTicks = 0;
            HandSwing.test();
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        Hand.endPreview();
        Hand.save();
        // Back to the view you had, unless something else changed it meanwhile.
        if (camera != null && minecraft.options.getCameraType() == CameraType.FIRST_PERSON) view(minecraft, camera);
        if (camera != null) minecraft.options.hideGui = hideGui;
        camera = null;
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false; // the world, and the swing timer, keep going behind the panel
    }

    // ------------------------------------------------------------- render ---

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // Only the panel: the rest of the screen is the game, with your hand in it.
        g.fill(px, PAD, px + pw, height - PAD, PANEL);
        g.outline(px, PAD, pw, height - 2 * PAD, Theme.LINE);
        g.fill(px + 1, PAD + HEAD - 3, px + pw - 1, PAD + HEAD - 2, Theme.LINE);
        g.fill(px + 1, height - PAD - FOOT, px + pw - 1, height - PAD - FOOT + 1, Theme.LINE);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.text(font, Component.literal("Viewmodel").withStyle(ChatFormatting.BOLD), px + IN, PAD + 5, Theme.TEXT);
        int n = changedCount();
        String status = n == 0 ? "Minecraft's pose" : n == 1 ? "1 change from Minecraft's" : n + " changes from Minecraft's";
        g.text(font, status, px + IN, PAD + 17, Theme.MUTED, false);
    }

    // --------------------------------------------------------- test hooks ---

    /** The pose's sliders, for tests: in order, Position first. */
    public List<AbstractWidget> sliders() {
        return List.copyOf(sliders);
    }

    public int panelX() {
        return px;
    }

    public int panelWidth() {
        return pw;
    }
}
