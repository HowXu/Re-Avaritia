# Item Effect Pass Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild Avaritia item effect rendering so 26.1.2 restores the 1.21.1 base-first/effect-second flush semantics for cosmic-style item overlays.

**Architecture:** Remove the failed first-person queue experiment and introduce a dedicated item effect render queue. `LayeredEffectItemModel` submits base/halo/trident/arc normally, enqueues effect calls separately, and context-specific flush hooks render effect quads after normal item features complete.

**Tech Stack:** Java 25, NeoForge 26.1.2 item model API, Sponge Mixin, Minecraft client render pipelines, JUnit where feasible.

## Global Constraints

- Do not solve this by forcing all effect GUI items into the oversized/offscreen branch.
- Do not tune blend, depth, overlay offset, or mask textures as the primary fix.
- Preserve current item JSON/datagen schema where possible.
- Keep trident OBJ geometry and arc rendering separate from the flat item effect mask pass.
- Do not revert unrelated worktree changes such as `AvaritiaShaders.java` or `InfinitySwordGameTests.java` unless explicitly requested.
- Prefer compiling with `gradle compileJava`; do not rely on executable permission for `./gradlew`.

---

## File Structure

- Create `src/main/java/committee/nova/mods/avaritia/api/client/render/item/ItemEffectRenderCall.java`
  - Immutable data holder for one deferred item effect pass.
- Create `src/main/java/committee/nova/mods/avaritia/api/client/render/item/ItemEffectRenderQueue.java`
  - Central queue for collecting and flushing effect calls.
- Modify `src/main/java/committee/nova/mods/avaritia/client/model/item/LayeredEffectItemModel.java`
  - Remove first-person experiment code and enqueue effect calls instead of submitting effect custom geometry.
- Modify `src/main/java/committee/nova/mods/avaritia/client/model/loader/AvaritiaItemModelRenderers.java`
  - Remove `QueuedEffectSpecialRenderer`; keep a low-level `renderEffectLayer(...)` helper used by the new queue.
- Delete or replace `src/main/java/committee/nova/mods/avaritia/api/client/render/CosmicRenderQueue.java`
  - The old name should not remain as a misleading first-person experiment unless no references remain.
- Delete `src/main/java/committee/nova/mods/avaritia/mixin/client/ItemInHandRendererMixin.java`
  - Remove the tail flush experiment.
- Modify `src/main/resources/avaritia.mixins.json`
  - Remove `client.ItemInHandRendererMixin`; add only targeted final flush mixins if implementation proves they are required.
- Potentially create small final flush mixins after inspecting descriptors:
  - `src/main/java/committee/nova/mods/avaritia/mixin/client/FeatureRenderDispatcherMixin.java`
  - `src/main/java/committee/nova/mods/avaritia/mixin/client/GuiGraphicsMixin.java`

---

### Task 1: Remove Failed First-Person Queue Experiment

**Files:**
- Modify: `src/main/java/committee/nova/mods/avaritia/client/model/item/LayeredEffectItemModel.java`
- Modify: `src/main/java/committee/nova/mods/avaritia/client/model/loader/AvaritiaItemModelRenderers.java`
- Modify: `src/main/resources/avaritia.mixins.json`
- Delete: `src/main/java/committee/nova/mods/avaritia/mixin/client/ItemInHandRendererMixin.java`

**Interfaces:**
- Consumes: current failed queue experiment.
- Produces: clean baseline where effect submission is either old normal `EFFECT` special renderer or no first-person trial code remains.

- [ ] **Step 1: Remove `ItemInHandRendererMixin` registration**

Edit `src/main/resources/avaritia.mixins.json` so the `client` array does not include `client.ItemInHandRendererMixin`.

Expected client array:

```json
  "client": [
    "compat.JeiModIdHelperMixin",
    "client.RenderPassMixin",
    "client.RenderSystemMixin",
    "client.RenderTypeMixin"
  ],
```

- [ ] **Step 2: Delete the failed mixin file**

Delete `src/main/java/committee/nova/mods/avaritia/mixin/client/ItemInHandRendererMixin.java`.

- [ ] **Step 3: Remove queued effect trial renderer**

In `src/main/java/committee/nova/mods/avaritia/client/model/loader/AvaritiaItemModelRenderers.java`, remove these declarations and classes:

```java
public static final SpecialModelRenderer<QueuedEffectLayerArgument> QUEUED_EFFECT = new QueuedEffectSpecialRenderer();

public record QueuedEffectLayerArgument(CosmicRenderable model, ItemStack stack, ItemDisplayContext displayContext) {
}

private static final class QueuedEffectSpecialRenderer implements SpecialModelRenderer<QueuedEffectLayerArgument> {
    ...
}
```

Also remove imports that only served that trial code:

```java
import committee.nova.mods.avaritia.api.client.render.CosmicRenderCall;
import committee.nova.mods.avaritia.api.client.render.CosmicRenderQueue;
import committee.nova.mods.avaritia.api.iface.transform.CosmicRenderable;
```

- [ ] **Step 4: Remove first-person queued branch from `LayeredEffectItemModel`**

In `LayeredEffectItemModel.update(...)`, temporarily restore the effect append to a single path:

```java
if (shouldRenderEffectLayer(tridentGeometry)) {
    appendEffectLayer(renderState, displayContext,
            this.effect.createArgument(this.effectQuads, level, owner, displayContext, stack),
            this.effectExtents);
}
```

Remove these members from `LayeredEffectItemModel`:

```java
implements CosmicRenderable
renderCosmicLayer(...)
appendQueuedFirstPersonEffectLayer(...)
```

Remove imports that only served trial queue rendering:

```java
import committee.nova.mods.avaritia.api.iface.transform.CosmicRenderable;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
```

- [ ] **Step 5: Compile baseline**

Run: `gradle compileJava`

Expected: compile succeeds or only fails due to unrelated existing worktree changes. If it fails on deleted symbols from this task, remove the stale references before continuing.

- [ ] **Step 6: Commit if requested**

Do not commit automatically. If committing is requested, use:

```bash
git add src/main/resources/avaritia.mixins.json \
  src/main/java/committee/nova/mods/avaritia/client/model/item/LayeredEffectItemModel.java \
  src/main/java/committee/nova/mods/avaritia/client/model/loader/AvaritiaItemModelRenderers.java \
  src/main/java/committee/nova/mods/avaritia/mixin/client/ItemInHandRendererMixin.java
git commit -m "refactor: remove failed item effect queue experiment"
```

---

### Task 2: Add Dedicated Item Effect Queue Data Structures

**Files:**
- Create: `src/main/java/committee/nova/mods/avaritia/api/client/render/item/ItemEffectRenderCall.java`
- Create: `src/main/java/committee/nova/mods/avaritia/api/client/render/item/ItemEffectRenderQueue.java`
- Modify: `src/main/java/committee/nova/mods/avaritia/client/model/loader/AvaritiaItemModelRenderers.java`

**Interfaces:**
- Consumes: `AvaritiaItemModelRenderers.EffectLayerArgument` and `AvaritiaItemModelRenderers.renderEffectLayer(...)`.
- Produces:
  - `ItemEffectRenderCall(AvaritiaItemModelRenderers.EffectLayerArgument argument, PoseStack poseStack, ItemStack stack, ItemDisplayContext displayContext, int light, int overlay)`
  - `ItemEffectRenderQueue.enqueue(ItemEffectRenderCall call)`
  - `ItemEffectRenderQueue.flushAll()`
  - `ItemEffectRenderQueue.clear()`

- [ ] **Step 1: Create `ItemEffectRenderCall`**

Create `src/main/java/committee/nova/mods/avaritia/api/client/render/item/ItemEffectRenderCall.java`:

```java
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
```

- [ ] **Step 2: Create `ItemEffectRenderQueue`**

Create `src/main/java/committee/nova/mods/avaritia/api/client/render/item/ItemEffectRenderQueue.java`:

```java
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
```

- [ ] **Step 3: Ensure `renderEffectLayer(...)` is low-level and reusable**

In `AvaritiaItemModelRenderers.java`, keep or add this helper near the end of the file:

```java
public static void renderEffectLayer(EffectLayerArgument argument, PoseStack poseStack,
                                     MultiBufferSource source, int lightCoords, int overlayCoords) {
    if (argument.quads().isEmpty()) {
        return;
    }

    argument.applyUniforms();
    VertexConsumer buffer = source.getBuffer(argument.renderType());
    QuadInstance instance = new QuadInstance();
    instance.setColor(-1);
    instance.setLightCoords(lightCoords);
    instance.setOverlayCoords(overlayCoords);

    for (BakedQuad quad : argument.quads()) {
        buffer.putBakedQuad(poseStack.last(), quad, instance);
    }

    if (source instanceof MultiBufferSource.BufferSource bufferSource) {
        bufferSource.endBatch(argument.renderType());
    }
}
```

Required imports if absent:

```java
import net.minecraft.client.renderer.MultiBufferSource;
```

- [ ] **Step 4: Compile queue structures**

Run: `gradle compileJava`

Expected: compile succeeds or reports only missing integration because the queue is not used yet. Fix import/package errors before continuing.

---

### Task 3: Enqueue Effect Calls From `LayeredEffectItemModel`

**Files:**
- Modify: `src/main/java/committee/nova/mods/avaritia/client/model/item/LayeredEffectItemModel.java`
- Modify: `src/main/java/committee/nova/mods/avaritia/client/model/loader/AvaritiaItemModelRenderers.java`

**Interfaces:**
- Consumes: `ItemEffectRenderQueue.enqueue(ItemEffectRenderCall call)` and `ItemEffectRenderCall(...)` constructor from Task 2.
- Produces: `AvaritiaItemModelRenderers.EFFECT_QUEUE`, `AvaritiaItemModelRenderers.EffectQueueLayerArgument`, and a model update path that no longer submits effect quads through normal custom geometry.

- [ ] **Step 1: Add imports**

Add imports to `LayeredEffectItemModel.java`:

```java
import committee.nova.mods.avaritia.api.client.render.item.ItemEffectRenderCall;
import committee.nova.mods.avaritia.api.client.render.item.ItemEffectRenderQueue;
```

Add imports to `AvaritiaItemModelRenderers.java`:

```java
import committee.nova.mods.avaritia.api.client.render.item.ItemEffectRenderCall;
import committee.nova.mods.avaritia.api.client.render.item.ItemEffectRenderQueue;
```

- [ ] **Step 2: Replace effect special layer with enqueue layer**

Change the effect section in `update(...)` from:

```java
if (shouldRenderEffectLayer(tridentGeometry)) {
    appendEffectLayer(renderState, displayContext,
            this.effect.createArgument(this.effectQuads, level, owner, displayContext, stack),
            this.effectExtents);
}
```

to:

```java
if (shouldRenderEffectLayer(tridentGeometry)) {
    appendQueuedEffectLayer(renderState, displayContext,
            this.effect.createArgument(this.effectQuads, level, owner, displayContext, stack),
            this.effectExtents,
            stack);
}
```

- [ ] **Step 3: Replace `appendEffectLayer` implementation**

Replace the existing `appendEffectLayer(...)` method with this method:

```java
private void appendQueuedEffectLayer(ItemStackRenderState renderState, ItemDisplayContext displayContext,
                                     AvaritiaItemModelRenderers.EffectLayerArgument argument,
                                     Vector3fc[] extents, ItemStack stack) {
    ItemStackRenderState.LayerRenderState layer = renderState.newLayer();
    layer.setExtents(() -> extents);
    layer.setLocalTransform(this.transformation);
    layer.setupSpecialModel(AvaritiaItemModelRenderers.EFFECT_QUEUE,
            new AvaritiaItemModelRenderers.EffectQueueLayerArgument(argument, stack.copy(), displayContext));
    this.properties.applyToLayer(layer, displayContext);
    renderState.setAnimated();
    renderState.appendModelIdentityElement(this.effect);
    renderState.appendModelIdentityElement(Float.floatToIntBits(argument.opacity()));
}
```

This deliberately creates a tiny special layer whose only job is to capture the post-transform pose and enqueue the call during normal layer submission.

- [ ] **Step 4: Add renderer field**

Add beside existing renderer fields in `AvaritiaItemModelRenderers.java`:

```java
public static final SpecialModelRenderer<EffectQueueLayerArgument> EFFECT_QUEUE = new EffectQueueSpecialRenderer();
```

- [ ] **Step 5: Add argument record**

Add near `EffectLayerArgument`:

```java
public record EffectQueueLayerArgument(EffectLayerArgument effect, ItemStack stack, ItemDisplayContext displayContext) {
}
```

- [ ] **Step 6: Add queue special renderer**

Add this class near the other special renderers:

```java
private static final class EffectQueueSpecialRenderer implements SpecialModelRenderer<EffectQueueLayerArgument> {
    @Override
    public void submit(@Nullable EffectQueueLayerArgument argument, PoseStack poseStack,
                       SubmitNodeCollector submitNodeCollector, int lightCoords, int overlayCoords,
                       boolean hasFoil, int outlineColor) {
        if (argument == null || argument.effect().quads().isEmpty()) {
            return;
        }

        ItemEffectRenderQueue.enqueue(new ItemEffectRenderCall(
                argument.effect(),
                poseStack,
                argument.stack(),
                argument.displayContext(),
                lightCoords,
                overlayCoords
        ));
    }

    @Override
    public void getExtents(Consumer<Vector3fc> output) {
    }

    @Override
    public @Nullable EffectQueueLayerArgument extractArgument(ItemStack stack) {
        return null;
    }
}
```

- [ ] **Step 7: Compile and fix exact method names**

Run: `gradle compileJava`

Expected: compile succeeds. If it fails because `SpecialModelRenderer` signature differs, update only the method signature to match existing renderers in `AvaritiaItemModelRenderers.java`.

---

### Task 4: Flush Effect Queue After Feature Rendering

**Files:**
- Create if needed: `src/main/java/committee/nova/mods/avaritia/mixin/client/FeatureRenderDispatcherMixin.java`
- Modify: `src/main/resources/avaritia.mixins.json`
- Modify if needed: `src/main/java/committee/nova/mods/avaritia/api/client/render/item/ItemEffectRenderQueue.java`

**Interfaces:**
- Consumes: `ItemEffectRenderQueue.flushAll()`.
- Produces: effect pass flush after Neo item features render base geometry.

- [ ] **Step 1: Inspect exact dispatcher class and method descriptor**

Use source/jar inspection to locate the 26.1.2 class that runs item/custom feature rendering. Search terms:

```bash
grep -R "class FeatureRenderDispatcher\|renderAllFeatures\|renderSolid\|renderTranslucent" -n ~/.gradle/caches/neoformruntime ~/.gradle/caches/modules-2 | head
```

Expected: identify the exact class and method where base item features and custom geometry features have completed for a frame/context.

- [ ] **Step 2: Create mixin only after descriptor is known**

If the method is `FeatureRenderDispatcher.renderAllFeatures()V`, create:

```java
package committee.nova.mods.avaritia.mixin.client;

import committee.nova.mods.avaritia.api.client.render.item.ItemEffectRenderQueue;
import net.minecraft.client.renderer.FeatureRenderDispatcher;
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
```

If the class or method name differs, use the exact class/method identified in Step 1 and keep the body identical.

- [ ] **Step 3: Register mixin**

Add the final mixin to `src/main/resources/avaritia.mixins.json` `client` array:

```json
"client.FeatureRenderDispatcherMixin"
```

- [ ] **Step 4: Add defensive cleanup if required**

If the dispatcher clears feature queues before the mixin tail can render, move the injection to the last safe point before clear. The injected body remains:

```java
ItemEffectRenderQueue.flushAll();
```

- [ ] **Step 5: Compile mixin descriptor**

Run: `gradle compileJava`

Expected: compile succeeds. Then launch client once to confirm there is no mixin descriptor crash.

---

### Task 5: Remove Or Retire Old `CosmicRenderQueue`

**Files:**
- Delete or modify: `src/main/java/committee/nova/mods/avaritia/api/client/render/CosmicRenderQueue.java`
- Modify: `src/main/java/committee/nova/mods/avaritia/api/client/render/CosmicRenderCall.java`
- Modify: `src/main/java/committee/nova/mods/avaritia/client/AvaritiaClient.java`
- Search all Java references.

**Interfaces:**
- Consumes: no active references to the old queue.
- Produces: no misleading old queue code remains.

- [ ] **Step 1: Search old queue references**

Run:

```bash
grep -R "CosmicRenderQueue\|CosmicRenderCall" -n src/main/java
```

Expected: only old references remain in files that should be changed in this task.

- [ ] **Step 2: Remove old render-level flush**

If `AvaritiaClient.onRenderLevel(...)` only calls `CosmicRenderQueue.renderAll()`, delete that method and its import:

```java
@SubscribeEvent
public static void onRenderLevel(RenderLevelStageEvent.AfterLevel event) {
    CosmicRenderQueue.renderAll();
}
```

Remove imports that become unused:

```java
import committee.nova.mods.avaritia.api.client.render.CosmicRenderQueue;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
```

If `RenderLevelStageEvent` is used elsewhere in `AvaritiaClient`, remove only the old queue call.

- [ ] **Step 3: Delete old queue classes if unused**

Delete:

```text
src/main/java/committee/nova/mods/avaritia/api/client/render/CosmicRenderQueue.java
src/main/java/committee/nova/mods/avaritia/api/client/render/CosmicRenderCall.java
```

- [ ] **Step 4: Compile after old queue removal**

Run: `gradle compileJava`

Expected: compile succeeds. If references remain, update them to `ItemEffectRenderQueue` or remove obsolete code.

---

### Task 6: Runtime Verification Pass

**Files:**
- No planned source edits unless verification identifies compile/startup blockers.

**Interfaces:**
- Consumes: completed Tasks 1-6.
- Produces: verified behavior or a concrete failing observation for the next debugging cycle.

- [ ] **Step 1: Compile**

Run:

```bash
gradle compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Launch client**

Run the project’s normal client run config. If using Gradle command line, run:

```bash
gradle runClient
```

Expected: client reaches main menu without mixin apply errors.

- [ ] **Step 3: Inspect GUI item rendering**

In creative inventory or an equivalent controlled inventory screen, inspect:

```text
avaritia:eternal_singularity
avaritia:infinity_helmet
avaritia:infinity_trident
avaritia:matter_cluster empty
avaritia:matter_cluster partial
avaritia:matter_cluster full
```

Expected:

```text
Eternal Singularity remains correct.
Infinity Helmet base pixels remain visible with effect overlay.
Infinity Trident base pixels remain visible with effect overlay.
Matter Cluster base pixels remain visible at every fill amount.
Matter Cluster overlay intensity can vary with fill amount.
```

- [ ] **Step 4: Inspect hand/world contexts**

Inspect first-person and third-person held/display contexts for:

```text
Infinity Sword
Infinity Helmet if visible through armor/item context
Infinity Trident
Matter Cluster
```

Expected: first-person and third-person use the same base/effect ordering semantics; no context makes the base texture disappear behind the effect pass.

- [ ] **Step 5: Capture remaining failures with exact context**

If any item still fails, record:

```text
item id:
display context:
base visible yes/no:
effect visible yes/no:
GUI/first-person/third-person:
shader pack enabled yes/no:
```

Use this record as the input to the next systematic debugging cycle.

---

## Self-Review

- Spec coverage: Tasks remove failed trial code, introduce a dedicated effect queue, enqueue from `LayeredEffectItemModel`, flush after feature rendering, retire old queue, and verify GUI/hand/world contexts.
- Placeholder scan: no `TBD`, `TODO`, or vague implementation-only steps remain; the one descriptor-dependent step explicitly requires inspection before writing the mixin.
- Type consistency: `ItemEffectRenderCall`, `ItemEffectRenderQueue`, `EffectQueueLayerArgument`, and `EFFECT_QUEUE` names are consistent across tasks.
