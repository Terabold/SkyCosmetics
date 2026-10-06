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
    public static final int MIN = 50, MAX = 150, STEP = 5, DEFAULT = 100;

    /** The size in percent; 100 is off. */
    static int percent = DEFAULT;
    static boolean inventory = true, menus = true, hotbar = true, elsewhere = false, skyblockOnly = false;

    /** One identity element per size, made once: appended to the model identity of every resized head. */
    private record Key(int percent) {}

    private static final Key[] KEYS = new Key[MAX + 1];
    /** The last extents seen and the head center they give: heads share one model, so this is nearly always a hit. */
    private static Supplier<Vector3fc[]> pivotFor;
    private static final Vector3f PIVOT = new Vector3f(), SCRATCH = new Vector3f();
    /** Vanilla's head center in block space, for a layer without extents. */
    private static final Vector3fc FALLBACK = new Vector3f(0.5f, 0.25f, 0.5f);

    static {
        for (int i = MIN; i <= MAX; i++) KEYS[i] = new Key(i);
    }

    private HeadSize() {}

    public static void init() {
        Settings.register("headSize", HeadSize::read, HeadSize::write);
        Hub.add(new Section("headSize", Section.FEATURE, Component.translatable("skycosmetics.headSize"),
            Component.translatable("skycosmetics.headSize.sub"), Component.translatable("skycosmetics.headSize.tooltip"),
            () -> new ItemStack(Items.PLAYER_HEAD), HeadSize::rows));
    }

    public static int percent() {
        return percent;
    }

    /** For the settings and tests; clamped to the range and rounded to the step. */
    public static void setPercent(int p) {
        percent = Math.clamp(Math.round(p / (float) STEP) * STEP, MIN, MAX);
    }

    // ------------------------------------------------------------- the hook ---

    /** Resizes the head layers of a GUI item just built. Never throws: the mixin catches. */
    public static void apply(ItemStackRenderState out, ItemStack stack, ItemDisplayContext context) {
        if (percent == DEFAULT || context != ItemDisplayContext.GUI) return;
        int area = SlotArea.current();
        if (!applies(area)) return;
        ItemStackRenderStateAccess state = (ItemStackRenderStateAccess) out;
        ItemStackRenderState.LayerRenderState[] layers = state.skycosmetics$layers();
        int n = Math.min(state.skycosmetics$activeLayerCount(), layers.length);
        boolean any = false;
        for (int i = 0; i < n && !any; i++) any = isHead(((LayerRenderStateAccess) layers[i]).skycosmetics$specialRenderer());
        if (!any) return;
        if (skyblockOnly && area != SlotArea.PREVIEW && !ItemFacts.of(stack).skyblock()) return;
        float s = percent / 100f;
        // The key first: a failure below never leaves a resized image under an unresized key.
        out.appendModelIdentityElement(KEYS[percent]);
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
        setPercent((int) Math.round(Settings.num(o.get("percent"), percent, MIN, MAX)));
        inventory = Settings.bool(o.get("inventory"), inventory);
        menus = Settings.bool(o.get("menus"), menus);
        hotbar = Settings.bool(o.get("hotbar"), hotbar);
        elsewhere = Settings.bool(o.get("elsewhere"), elsewhere);
        skyblockOnly = Settings.bool(o.get("skyblockOnly"), skyblockOnly);
    }

    private static JsonElement write() {
        JsonObject o = new JsonObject();
        o.addProperty("percent", percent);
        o.addProperty("inventory", inventory);
        o.addProperty("menus", menus);
        o.addProperty("hotbar", hotbar);
        o.addProperty("elsewhere", elsewhere);
        o.addProperty("skyblockOnly", skyblockOnly);
        return o;
    }

    private static List<Option> rows(Screen settings) {
        List<Option> rows = new ArrayList<>();
        rows.add(Option.of("headSize", Component.translatable("skycosmetics.headSize.size"),
                Component.translatable("skycosmetics.headSize.size.tooltip"),
                new Control.Slider(() -> percent, v -> setPercent((int) Math.round(v)), MIN, MAX, STEP, HeadSize::sizeLabel))
            .search(Component.translatable("skycosmetics.headSize.search").getString()));
        rows.add(Option.of("headSizePreview", Component.empty(), Component.empty(),
            new Control.Custom((host, w) -> new SlotPreview(w, SlotPreview.Kind.HEADS))));
        rows.add(Option.header("headSizeWhere", Component.translatable("skycosmetics.headSize.group.where")));
        BooleanSupplier on = () -> percent != DEFAULT;
        rows.add(toggle("inventory", () -> inventory, v -> inventory = v).enabledWhen(on));
        rows.add(toggle("menus", () -> menus, v -> menus = v).enabledWhen(on));
        rows.add(toggle("hotbar", () -> hotbar, v -> hotbar = v).enabledWhen(on));
        rows.add(toggle("elsewhere", () -> elsewhere, v -> elsewhere = v).enabledWhen(on));
        rows.add(Option.header("headSizeWhich", Component.translatable("skycosmetics.headSize.group.which")));
        rows.add(toggle("skyblockOnly", () -> skyblockOnly, v -> skyblockOnly = v).enabledWhen(on));
        return rows;
    }

    /** "Default" at 100%, else "125%". */
    static Component sizeLabel(double v) {
        int p = (int) Math.round(v);
        return p == DEFAULT ? Component.translatable("skycosmetics.menu.default") : Component.literal(p + "%");
    }

    private static Option toggle(String id, BooleanSupplier get, Consumer<Boolean> set) {
        String key = "skycosmetics.headSize." + id;
        return Option.of("headSize." + id, Component.translatable(key), Component.translatable(key + ".tooltip"),
            new Control.Toggle(get, set));
    }
}
