package committee.nova.mods.avaritia.mixin.client;

import committee.nova.mods.avaritia.api.client.render.item.ItemEffectRenderQueue;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FeatureRenderDispatcher.class)
public abstract class FeatureRenderDispatcherMixin {
    @Inject(method = "renderAllFeatures", at = @At("TAIL"))
    private void avaritia$flushItemEffects(CallbackInfo ci) {
        ItemEffectRenderQueue.flushAll();
    }
}
