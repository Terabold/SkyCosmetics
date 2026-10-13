package io.github.terabold.skycosmetics.slots;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.gui.StudioScreen;
import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Hub;
import io.github.terabold.skycosmetics.hub.Option;
import io.github.terabold.skycosmetics.hub.Section;
import io.github.terabold.skycosmetics.mixin.slots.ItemStackRenderStateAccess;
import io.github.terabold.skycosmetics.mixin.slots.LayerRenderStateAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.PlayerHeadSpecialRenderer;
import net.minecraft.client.renderer.special.SkullSpecialRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Head Size: player heads and mob skulls drawn bigger or smaller in inventory slots (the "larger heads" of older
 * SkyBlock mods). Purely a drawing change: slots, their click areas and the item stay as they are.
 *
 * Applied once per GUI item, right after its model is built ({@code ItemModelResolver.updateForTopItem}): each
 * layer drawn by a skull renderer is scaled around the head's own center, so it grows in place. The size goes into
 * the item's model identity, so the GUI item atlas never reuses an image drawn at another size; above 100% the item
 * is flagged oversized and Minecraft draws it on its own canvas once it outgrows the 16x16 cell (from about 120%).
 * At 100% the hook is a single comparison.
 */
public final class HeadSize {
    public static final int MIN = 50, MAX = 200, STEP = 1, DEFAULT = 100;

    /** Five adjustment units per actual percent keep the ordinary slider gentle and uniformly precise. */
    private static final int UNITS = 5;
    static int sizeUnits = DEFAULT * UNITS;
    static boolean inventory = true, menus = true, hotbar = true, elsewhere = false, skyblockOnly = false;

    /** One identity element per size, made once: appended to the model identity of every resized head. */
    private record Key(int units) {}

    private static final Key[] KEYS = new Key[MAX * UNITS + 1];
    /** The last extents seen and the head center they give: heads share one model, so this is nearly always a hit. */
    private static Supplier<Vector3fc[]> pivotFor;
    private static final Vector3f PIVOT = new Vector3f(), SCRATCH = new Vector3f();
    /** Vanilla's head center in block space, for a layer without extents. */
    private static final Vector3fc FALLBACK = new Vector3f(0.5f, 0.25f, 0.5f);

    static {
        for (int i = MIN * UNITS; i <= MAX * UNITS; i++) KEYS[i] = new Key(i);
    }

    private HeadSize() {}

    public static void init() {
        Settings.register("headSize", HeadSize::read, HeadSize::write);
        Hub.add(new Section("headSize", Section.FEATURE, Component.translatable("skycosmetics.headSize"),
            Component.translatable("skycosmetics.headSize.sub"), Component.translatable("skycosmetics.headSize.tooltip"),
            () -> new ItemStack(Items.PLAYER_HEAD), HeadSize::rows));
    }

    public static double percent() {
        return sizeUnits / (double) UNITS;
    }

    /** Actual size, including legacy values; rounded to one fifth of a percent. */
    public static void setPercent(double p) {
        sizeUnits = (int)Math.round(Math.clamp(Double.isFinite(p) ? p : DEFAULT, MIN, MAX) * UNITS);
    }

    /** For tests: where heads are resized, and which. */
    public static void where(boolean inventory, boolean menus, boolean hotbar, boolean elsewhere, boolean skyblockOnly) {
        HeadSize.inventory = inventory;
        HeadSize.menus = menus;
        HeadSize.hotbar = hotbar;
        HeadSize.elsewhere = elsewhere;
        HeadSize.skyblockOnly = skyblockOnly;
    }

    /** Every setting back to its default. */
    public static void reset() {
        sizeUnits = DEFAULT * UNITS;
        where(true, true, true, false, false);
    }

    /** For tests: whether an item's model identity element is a Head Size key. */
    public static boolean isKey(Object o) {
        return o instanceof Key;
    }

    // ------------------------------------------------------------- the hook ---

    /** Resizes the head layers of a GUI item just built. Never throws: the mixin catches. */
    public static void apply(ItemStackRenderState out, ItemStack stack, ItemDisplayContext context) {
        if (sizeUnits == DEFAULT * UNITS || context != ItemDisplayContext.GUI) return;
        int area = SlotArea.current();
        if (!applies(area)) return;
        ItemStackRenderStateAccess state = (ItemStackRenderStateAccess) out;
        ItemStackRenderState.LayerRenderState[] layers = state.skycosmetics$layers();
        int n = Math.min(state.skycosmetics$activeLayerCount(), layers.length);
        boolean any = false;
        for (int i = 0; i < n && !any; i++) any = isHead(((LayerRenderStateAccess) layers[i]).skycosmetics$specialRenderer());
        if (!any) return;
        if (skyblockOnly && area != SlotArea.PREVIEW && !ItemFacts.of(stack).skyblock()) return;
        float s = sizeUnits / (100f * UNITS);
        // The key first: a failure below never leaves a resized image under an unresized key.
        out.appendModelIdentityElement(KEYS[sizeUnits]);
        if (s > 1) out.setOversizedInGui(true);
        for (int i = 0; i < n; i++) {
            LayerRenderStateAccess layer = (LayerRenderStateAccess) layers[i];
            if (!isHead(layer.skycosmetics$specialRenderer())) continue;
            Matrix4f local = layer.skycosmetics$localTransform();
            Vector3fc c = pivot(layer.skycosmetics$extents(), local);
            local.scaleAroundLocal(s, c.x(), c.y(), c.z());
        }
    }

    private static boolean applies(int area) {
        return switch (area) {
            case SlotArea.INVENTORY -> inventory;
            case SlotArea.MENU -> menus;
            case SlotArea.HOTBAR -> hotbar;
            case SlotArea.PREVIEW -> true;
            // The studio sizes its own icons; scaling them again would break its cards.
            default -> elsewhere && !(Minecraft.getInstance().screen instanceof StudioScreen);
        };
    }

    private static boolean isHead(SpecialModelRenderer<?> r) {
        return r instanceof PlayerHeadSpecialRenderer || r instanceof SkullSpecialRenderer;
    }

    /**
     * The center of the layer's bounding box in the item's block space, before scaling: scaling around it keeps
     * the head where it was on screen, whatever shape the skull or the resource pack's model.
     */
    private static Vector3fc pivot(Supplier<Vector3fc[]> extents, Matrix4f local) {
        if (extents != null && extents == pivotFor) return PIVOT;
        Vector3fc[] points = extents == null ? null : extents.get();
        if (points == null || points.length == 0) return FALLBACK;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (Vector3fc p : points) {
            local.transformPosition(p, SCRATCH);
            minX = Math.min(minX, SCRATCH.x);
            minY = Math.min(minY, SCRATCH.y);
            minZ = Math.min(minZ, SCRATCH.z);
            maxX = Math.max(maxX, SCRATCH.x);
            maxY = Math.max(maxY, SCRATCH.y);
            maxZ = Math.max(maxZ, SCRATCH.z);
        }
        pivotFor = extents;
        return PIVOT.set((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
    }

    // ------------------------------------------------------------- settings ---

    private static void read(JsonElement v) {
        JsonObject o = Settings.obj(v);
        setPercent(Settings.num(o.get("percent"), percent(), MIN, MAX));
        inventory = Settings.bool(o.get("inventory"), inventory);
        menus = Settings.bool(o.get("menus"), menus);
        hotbar = Settings.bool(o.get("hotbar"), hotbar);
        elsewhere = Settings.bool(o.get("elsewhere"), elsewhere);
        skyblockOnly = Settings.bool(o.get("skyblockOnly"), skyblockOnly);
    }

    private static JsonElement write() {
        JsonObject o = new JsonObject();
        o.addProperty("percent", percent());
        o.addProperty("inventory", inventory);
        o.addProperty("menus", menus);
        o.addProperty("hotbar", hotbar);
        o.addProperty("elsewhere", elsewhere);
        o.addProperty("skyblockOnly", skyblockOnly);
        return o;
    }

    private static List<Option> rows(Screen settings) {
        List<Option> rows = new ArrayList<>();
        rows.add(Option.of("headSize.size", Component.translatable("skycosmetics.headSize.size"),
                Component.translatable("skycosmetics.headSize.size.tooltip"),
                new Control.Slider(() -> 100 + (percent() - 100) * UNITS,
                    v -> setPercent(100 + (v - 100) / UNITS), 50, 200, STEP, HeadSize::sizeLabel))
            .search(Component.translatable("skycosmetics.headSize.search").getString()));
        rows.add(Option.of("headSize.preview", Component.empty(), Component.empty(),
            new Control.Custom((host, w) -> new SlotPreview(w, SlotPreview.Kind.HEADS))));
        rows.add(Option.header("headSize.groupWhere", Component.translatable("skycosmetics.headSize.group.where")));
        BooleanSupplier on = () -> sizeUnits != DEFAULT * UNITS;
        rows.add(toggle("inventory", () -> inventory, v -> inventory = v).enabledWhen(on));
        rows.add(toggle("menus", () -> menus, v -> menus = v).enabledWhen(on));
        rows.add(toggle("hotbar", () -> hotbar, v -> hotbar = v).enabledWhen(on));
        rows.add(toggle("elsewhere", () -> elsewhere, v -> elsewhere = v).enabledWhen(on));
        rows.add(Option.header("headSize.groupWhich", Component.translatable("skycosmetics.headSize.group.which")));
        rows.add(toggle("skyblockOnly", () -> skyblockOnly, v -> skyblockOnly = v).enabledWhen(on));
        return rows.stream().map(o -> o.control() instanceof Control.Toggle ? o.beside() : o.compact()).toList();
    }

    /** Adjustment units, not percentages: 150 means 110% actual size. */
    static Component sizeLabel(double v) {
        // A legacy size beyond the gentler adjustment range stays visible until the player adjusts it.
        if (percent() < 90 || percent() > 120) return Component.literal(Math.round(percent()) + "%");
        return Component.literal(Integer.toString((int)Math.round(v)));
    }

    private static Option toggle(String id, BooleanSupplier get, Consumer<Boolean> set) {
        String key = "skycosmetics.headSize." + id;
        return Option.of("headSize." + id, Component.translatable(key), Component.translatable(key + ".tooltip"),
            new Control.Toggle(get, set));
    }
}
