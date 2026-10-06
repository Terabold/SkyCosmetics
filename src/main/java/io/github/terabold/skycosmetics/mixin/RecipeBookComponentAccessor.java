package io.github.terabold.skycosmetics.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The recipe book keeps its search box to itself; the open key must not fire while it is typed in. */
@Mixin(RecipeBookComponent.class)
public interface RecipeBookComponentAccessor {
    /** Null until the book is first initialised. */
    @Accessor("searchBox")
    EditBox skycosmetics$searchBox();
}
