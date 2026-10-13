package io.github.terabold.skycosmetics.gui;

import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.gui.hub.ThemedButton;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Tips;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A cached native armor render state: no player mutation or world entity is needed for a trim icon. */
final class TrimIcon extends ThemedButton {
    private final boolean selected;
    private AvatarRenderState state;
    private float center, height;
    TrimIcon(Component name, ItemStack armor, int size, boolean selected, Runnable action) {
        super(size, size, name, b -> action.run()); this.selected = selected;
        Tips.set(this, name);
        try {
            var mc = Minecraft.getInstance();
            if (mc.player == null) return;
            state = (AvatarRenderState) mc.getEntityRenderDispatcher().getRenderer(mc.player).createRenderState(mc.player, 1);
            state.headEquipment = state.chestEquipment = state.legsEquipment = state.feetEquipment = ItemStack.EMPTY;
            EquipmentSlot slot = armor.get(DataComponents.EQUIPPABLE).slot();
            switch (slot) {
                case HEAD -> { state.headEquipment = armor; center = 1.75f; height = .65f; }
                case CHEST -> { state.chestEquipment = armor; center = 1.2f; height = .85f; }
                case LEGS -> { state.legsEquipment = armor; center = .65f; height = 1.1f; }
                case FEET -> { state.feetEquipment = armor; center = .3f; height = .65f; }
                default -> { state = null; return; }
            }
            state.isInvisible = state.isInvisibleToPlayer = true;
            state.showCape = state.showExtraEars = false;
            state.nameTag = state.scoreText = null; state.shadowPieces.clear(); state.outlineColor = 0;
            state.rightHandItemState.clear(); state.leftHandItemState.clear(); state.heldOnHead.clear(); state.headItem.clear();
            state.wornHeadType = null; state.wornHeadProfile = null;
            state.attackTime = state.walkAnimationPos = state.walkAnimationSpeed = 0;
            state.isCrouching = state.isFallFlying = false;
            state.bodyRot = 160; state.yRot = state.xRot = 0;
            state.scale = state.ageScale = 1;
            state.boundingBoxWidth = .7f; state.boundingBoxHeight = 1.9f;
            state.lightCoords = net.minecraft.util.LightCoordsUtil.FULL_BRIGHT;
        } catch (RuntimeException e) { state = null; Io.failed("Preparing a trim icon", e); }
    }
    @Override protected void draw(GuiGraphicsExtractor g, int mx, int my, float hover) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        Shapes.round(g, x, y, w, h, Theme.SMALL_RADIUS, selected ? Theme.ACCENT_BG : Theme.SURFACE);
        Shapes.frame(g, x, y, w, h, Theme.SMALL_RADIUS, selected ? Theme.ACCENT : Theme.LINE);
        if (state != null) try {
            g.entity(state, (h - 5) / height, new Vector3f(0, center, 0), new Quaternionf().rotateZ((float)Math.PI),
                null, x + 2, y + 2, x + w - 2, y + h - 2);
        } catch (RuntimeException e) { Io.failed("Drawing a trim icon", e); }
        if (Ui.keyboardFocus(this)) Ui.focusRing(g,x,y,w,h,Theme.SMALL_RADIUS);
    }
}
