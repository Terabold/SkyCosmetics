package io.github.terabold.skycosmetics.mixin;

import io.github.terabold.skycosmetics.data.BlinkGuesser;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.server.Services;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.concurrent.Executor;

/**
 * Tells {@link BlinkGuesser} where Minecraft caches downloaded skins, so it can read the frames of an
 * animation from disk. Optional: without it, blinks are just not estimated.
 */
@Mixin(SkinManager.class)
public class SkinManagerMixin {
    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void skycosmetics$skins(Path skins, Services services, SkinTextureDownloader downloader, Executor executor, CallbackInfo ci) {
        BlinkGuesser.skinsAt(skins);
    }
}
