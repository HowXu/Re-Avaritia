package committee.nova.mods.avaritia.api.client.render.item;

import committee.nova.mods.avaritia.client.model.loader.AvaritiaItemModelRenderers;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public record ItemEffectRenderCall(
        AvaritiaItemModelRenderers.EffectLayerArgument argument,
        ItemStack stack,
        ItemDisplayContext displayContext,
        Matrix4f pose,
        Matrix3f normal,
        int light,
        int overlay
) {
    public ItemEffectRenderCall(AvaritiaItemModelRenderers.EffectLayerArgument argument, PoseStack poseStack,
                                ItemStack stack, ItemDisplayContext displayContext, int light, int overlay) {
        this(argument, stack.copy(), displayContext,
                new Matrix4f(poseStack.last().pose()),
                new Matrix3f(poseStack.last().normal()),
                light, overlay);
    }

    public PoseStack toPoseStack() {
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(this.pose);
        poseStack.last().normal().set(this.normal);
        return poseStack;
    }
}
