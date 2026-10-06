package io.github.terabold.skycosmetics.hand;

import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Io;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
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

/**
 * The live hand editor: a panel at the side of the screen, opposite your main hand, while the game keeps drawing
 * your real first-person hand next to it. Every slider applies at once, so what you see is exactly what you get.
 * The game isn't paused and nothing is dimmed or blurred.
 *
 * Edits one pose at a time (arrows switch between Everything and your rules). While it is open your main hand
 * shows that pose, holding a fitting item when the rule is for another kind (a bow for the Bow pose): drawn only,
 * your inventory is never touched. Test Swing plays a swing on screen only.
 */
public final class HandScreen extends Screen {
    private static final int PAD = 6, IN = 6, HEAD = 40, FOOT = 48;
    private static final int PANEL = 0xE81B1B22, LINE = 0xFF34343F, TEXT = 0xFFE8E8EE, MUTED = 0xFF8C8C9A,
        ACCENT = 0xFFD58CFF;
    /** Ticks between swings while Repeat is on: a swing, then a short rest to see the pose still. */
    private static final int REPEAT_REST = 12;

    private final Screen parent;
    private String key;
    private boolean repeat;
    private int restTicks;

    // Built in init().
    private int px, pw;
    private final List<PoseSlider> sliders = new ArrayList<>();
    private Button pivot, swing, repeatButton;

    /** Opens on the pose for {@code key} (a rule's key, or {@link Hand#EVERYTHING}). */
    public HandScreen(Screen parent, String key) {
        super(Component.translatable("skycosmetics.hand.editor"));
        this.parent = parent;
        this.key = Hand.pose(key) != null ? key : Hand.EVERYTHING;
    }

    public String key() {
        return key;
    }

    // ------------------------------------------------------------- layout ---

    @Override
    protected void init() {
        sliders.clear();
        pw = Math.clamp(width * 2 / 5, Math.min(150, width - 2 * PAD), 240);
        boolean rightHanded = minecraft.player == null || minecraft.player.getMainArm() == HumanoidArm.RIGHT;
        px = rightHanded ? PAD : width - PAD - pw;
        int inner = pw - 2 * IN;

        // Header: which pose, with arrows to the others.
        addRenderableWidget(Button.builder(Component.literal("<"), b -> step(-1)).bounds(px + IN, PAD + 16, 20, 20)
            .tooltip(Tooltip.create(Component.translatable("skycosmetics.hand.editor.previous"))).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> step(1)).bounds(px + pw - IN - 20, PAD + 16, 20, 20)
            .tooltip(Tooltip.create(Component.translatable("skycosmetics.hand.editor.next"))).build());

        // The pose's numbers, in a list that scrolls.
        int top = PAD + HEAD + 2, bottom = height - PAD - FOOT;
        int rw = inner - 8; // room for the scrollbar
        LinearLayout rows = LinearLayout.vertical().spacing(2);
        rows.addChild(label(Component.translatable("skycosmetics.hand.editor.hint"), rw, MUTED));
        group(rows, "position", rw, HandPose.Field.X, HandPose.Field.Y, HandPose.Field.Z);
        group(rows, "rotation", rw, HandPose.Field.ROT_X, HandPose.Field.ROT_Y, HandPose.Field.ROT_Z);
        pivot = rows.addChild(Button.builder(Component.empty(), b -> cyclePivot()).width(rw).build());
        group(rows, "size", rw, HandPose.Field.SIZE, HandPose.Field.SIZE_X, HandPose.Field.SIZE_Y, HandPose.Field.SIZE_Z);
        rows.addChild(label(Component.translatable("skycosmetics.hand.editor.group.swing"), rw, ACCENT));
        swing = rows.addChild(Button.builder(Component.empty(), b -> cycleSwing()).width(rw).build());
        for (HandPose.Field f : new HandPose.Field[]{HandPose.Field.SPEED, HandPose.Field.SWING_TURN,
            HandPose.Field.SWING_X, HandPose.Field.SWING_Y, HandPose.Field.SWING_Z}) {
            sliders.add(rows.addChild(new PoseSlider(rw, f, this::key, Hand::save)));
        }
        ScrollableLayout list = new ScrollableLayout(minecraft, rows, Math.max(40, bottom - top));
        list.setY(top);
        list.arrangeElements();
        list.setX(px + IN);
        list.arrangeElements();
        list.visitWidgets(this::addRenderableWidget);

        // Footer: try it, and leave.
        int half = (inner - 4) / 2, fy = height - PAD - FOOT + 4;
        addRenderableWidget(Button.builder(Component.translatable("skycosmetics.hand.editor.test"), b -> HandSwing.test())
            .bounds(px + IN, fy, half, 20).tooltip(Tooltip.create(Component.translatable("skycosmetics.hand.editor.test.tooltip"))).build());
        repeatButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            repeat = !repeat;
            restTicks = 0;
            refresh();
        }).bounds(px + IN + half + 4, fy, inner - half - 4, 20)
            .tooltip(Tooltip.create(Component.translatable("skycosmetics.hand.editor.repeat.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("skycosmetics.hand.editor.reset"), b -> resetPose())
            .bounds(px + IN, fy + 22, half, 20).tooltip(Tooltip.create(Component.translatable("skycosmetics.hand.editor.reset.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done").withStyle(ChatFormatting.GREEN), b -> onClose())
            .bounds(px + IN + half + 4, fy + 22, inner - half - 4, 20).build());

        preview();
        refresh();
    }

    private void group(LinearLayout rows, String name, int w, HandPose.Field... fields) {
        rows.addChild(label(Component.translatable("skycosmetics.hand.editor.group." + name), w, ACCENT));
        for (HandPose.Field f : fields) sliders.add(rows.addChild(new PoseSlider(w, f, this::key, Hand::save)));
    }

    private StringWidget label(Component text, int w, int color) {
        return new StringWidget(w, 11, text.copy().withColor(color & 0xFFFFFF), font);
    }

    /** Values and labels again: another pose picked, a choice cycled, a reset. */
    private void refresh() {
        HandPose p = Hand.pose(key);
        if (p == null) {
            key = Hand.EVERYTHING;
            p = Hand.pose(key);
        }
        for (PoseSlider s : sliders) {
            s.refresh();
            boolean swingDistance = s.field() == HandPose.Field.SWING_X || s.field() == HandPose.Field.SWING_Y
                || s.field() == HandPose.Field.SWING_Z;
            s.active = !(swingDistance && p.swing != HandPose.Swing.MOVING)
                && !(p.swing == HandPose.Swing.NONE && (s.field() == HandPose.Field.SPEED || s.field() == HandPose.Field.SWING_TURN));
        }
        pivot.setMessage(Component.translatable("skycosmetics.hand.pivot", Component.translatable("skycosmetics.hand.pivot." + p.pivot.key)));
        pivot.setTooltip(Tooltip.create(Component.translatable("skycosmetics.hand.pivot." + p.pivot.key + ".tooltip")));
        swing.setMessage(Component.translatable("skycosmetics.hand.swing", Component.translatable("skycosmetics.hand.swing." + p.swing.key)));
        swing.setTooltip(Tooltip.create(Component.translatable("skycosmetics.hand.swing." + p.swing.key + ".tooltip")));
        repeatButton.setMessage(Component.translatable("skycosmetics.hand.editor.repeat",
            Component.translatable(repeat ? "skycosmetics.menu.on" : "skycosmetics.menu.off")));
    }

    // ------------------------------------------------------------ actions ---

    private void step(int dir) {
        List<Hand.Rule> rules = Hand.rules();
        int i = 0;
        for (int j = 0; j < rules.size(); j++) if (rules.get(j).key().equals(key)) i = j;
        key = rules.get(Math.floorMod(i + dir, rules.size())).key();
        preview();
        refresh();
    }

    private void cyclePivot() {
        HandPose p = Hand.pose(key);
        HandPose.Pivot[] all = HandPose.Pivot.values();
        Hand.setPose(key, p.with(all[(p.pivot.ordinal() + 1) % all.length]));
        Hand.save();
        refresh();
    }

    private void cycleSwing() {
        HandPose p = Hand.pose(key);
        HandPose.Swing[] all = HandPose.Swing.values();
        Hand.setPose(key, p.with(all[(p.swing.ordinal() + 1) % all.length]));
        Hand.save();
        refresh();
    }

    private void resetPose() {
        Hand.setPose(key, HandPose.VANILLA);
        Hand.save();
        refresh();
    }

    /** Shows the pose being edited on your main hand, holding something it applies to. */
    private void preview() {
        try {
            Hand.preview(key, sample(key));
        } catch (RuntimeException e) {
            Io.failed("Previewing a hand pose", e);
            Hand.preview(key, null);
        }
    }

    /**
     * What the main hand holds while a pose is edited: the held item when the pose applies to it, else an item of
     * yours it applies to, else a plain Minecraft one of that kind. Null keeps the held item.
     */
    static ItemStack sample(String key) {
        LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
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
        g.outline(px, PAD, pw, height - 2 * PAD, LINE);
        g.fill(px + 1, PAD + HEAD, px + pw - 1, PAD + HEAD + 1, LINE);
        g.fill(px + 1, height - PAD - FOOT, px + pw - 1, height - PAD - FOOT + 1, LINE);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.text(font, Component.translatable("skycosmetics.hand.editor").withStyle(ChatFormatting.BOLD), px + IN, PAD + 4, TEXT);
        String name = clip(Hand.label(key).getString(), pw - 2 * IN - 48);
        g.centeredText(font, name, px + pw / 2, PAD + 22, ACCENT);
    }

    private String clip(String s, int px) {
        if (font.width(s) <= px) return s;
        return font.plainSubstrByWidth(s, Math.max(0, px - font.width("..."))) + "...";
    }

    // --------------------------------------------------------- test hooks ---

    /** The sliders, for tests: in order, Position X first. */
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
