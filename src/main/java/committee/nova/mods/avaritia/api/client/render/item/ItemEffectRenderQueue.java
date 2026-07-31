package committee.nova.mods.avaritia.api.client.render.item;

import committee.nova.mods.avaritia.client.model.loader.AvaritiaItemModelRenderers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;

import java.util.ArrayList;
import java.util.List;

public final class ItemEffectRenderQueue {
    private static final List<ItemEffectRenderCall> QUEUE = new ArrayList<>();

    private ItemEffectRenderQueue() {
    }

    public static void enqueue(ItemEffectRenderCall call) {
        QUEUE.add(call);
    }

    public static boolean hasQueuedCalls() {
        return !QUEUE.isEmpty();
    }

    public static void flushAll() {
        if (QUEUE.isEmpty()) {
            return;
        }

        MultiBufferSource.BufferSource source = Minecraft.getInstance().renderBuffers().bufferSource();
        for (ItemEffectRenderCall call : List.copyOf(QUEUE)) {
            AvaritiaItemModelRenderers.renderEffectLayer(call.argument(), call.toPoseStack(), source, call.light(), call.overlay());
        }
        source.endBatch();
        QUEUE.clear();
    }

    public static void clear() {
        QUEUE.clear();
    }
}
