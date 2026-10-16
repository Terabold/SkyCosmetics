package io.github.terabold.skycosmetics.mixin;

import io.github.terabold.skycosmetics.ChatNames;
import io.github.terabold.skycosmetics.nametag.Nametags;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** A line as it goes into the chat box, after every listener read Hypixel's text: your item names and your own name in it. */
@Mixin(ChatComponent.class)
public abstract class ChatNamesMixin {
    @ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At("HEAD"), argsOnly = true, require = 0)
    private Component skycosmetics$names(Component line) {
        return Nametags.ownName(ChatNames.apply(line)); // neither throws
    }
}
