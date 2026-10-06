package io.github.terabold.skycosmetics.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the inventory brush step aside when the recipe book covers the panel. */
@Mixin(AbstractRecipeBookScreen.class)
public interface RecipeBookScreenAccessor {
    @Accessor("recipeBookComponent")
    RecipeBookComponent<?> skycosmetics$recipeBook();

    @Accessor("widthTooNarrow")
    boolean skycosmetics$widthTooNarrow();
}
