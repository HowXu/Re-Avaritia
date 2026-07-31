# Item Effect Pass Refactor Design

## Problem

The 26.1.2 item effect renderer no longer preserves the 1.21.1 rendering contract for cosmic-style item overlays.

In 1.21.1, every effect item used an immediate sequence:

1. render the wrapped/base item model
2. flush the vanilla/base buffer
3. render the cosmic/effect mask pass
4. flush the effect RenderType

The current 26.1.2 port submits the base item layer and the effect custom geometry layer into Neo's deferred item feature system. The base layer is consumed by item feature rendering, while the effect layer is consumed by custom geometry rendering. That removes the explicit base-flush/effect-flush boundary and lets the effect pass interact incorrectly with the base item's alpha, ordering, and framebuffer composition.

Visible symptoms include normal inventory rendering for `halo_cosmic` items such as Eternal Singularity, but incorrect rendering for bare `cosmic` / `cosmic_arc` items such as Infinity Helmet and Infinity Trident. Eternal Singularity is not proof the effect path is correct; it is taking a halo/oversized GUI path that happens to isolate the render enough to hide the broken pass ordering.

## Goals

- Rebuild the runtime effect item pipeline to match the 1.21.1 semantic order: base first, effect second, with an explicit flush boundary.
- Apply the same effect pass mechanism to `cosmic`, `halo_cosmic`, `eternal`, `hell`, `unstable`, and `cosmic_arc` item models.
- Stop relying on `halo` or `oversized_in_gui` as an accidental fix for GUI rendering.
- Preserve current item JSON/datagen schema where possible.
- Keep trident OBJ geometry and arc rendering separate from the flat item effect mask pass.

## Non-Goals

- Do not solve this by forcing all effect GUI items into the oversized/offscreen branch.
- Do not tune blend, depth, overlay offset, or mask textures as the primary fix.
- Do not change item textures, mask resources, or data component storage.
- Do not rewrite armor entity rendering unless it is directly required by item rendering compilation.

## Proposed Architecture

Introduce a dedicated item effect pass queue that owns effect overlay rendering separately from normal item model layer submission.

### Components

- `ItemEffectRenderCall`
  - Immutable call data for one effect pass.
  - Stores the effect quads, generated `EffectLayerArgument`, pose snapshot, display context, stack copy, light, overlay, and optional model identity data.

- `ItemEffectRenderQueue`
  - Collects effect calls emitted during item model submission.
  - Exposes targeted flush methods for GUI, first-person, and world/third-person render phases.
  - Flushes effect calls by rendering their quads to the effect RenderType and ending that RenderType batch.

- `AvaritiaItemModelRenderers.renderEffectLayer(...)`
  - Becomes the shared low-level draw helper.
  - Applies uniforms, writes quads, and flushes the specific effect RenderType.

- `LayeredEffectItemModel`
  - Continues to submit the wrapped/base model through the normal 26.1.2 item model path.
  - Stops appending effect layers as normal `SpecialModelRenderer` custom geometry.
  - Enqueues effect calls when an item has effect quads.
  - Keeps halo, pulse, trident, and arc layer responsibilities separate.

### Removed Trial Code

The previous first-person experiment should be removed rather than patched in place:

- `QueuedEffectSpecialRenderer`
- `QueuedEffectLayerArgument`
- first-person-only queue branch in `LayeredEffectItemModel`
- the current `ItemInHandRendererMixin` tail flush experiment
- the current `CosmicRenderQueue` trial path, unless it is renamed and rebuilt around item effect pass semantics

## Data Flow

### Model Update

1. `LayeredEffectItemModel.update(...)` submits the base item model with `wrapped.update(...)`.
2. It submits non-effect visual layers that are safe in the normal feature system: halo, pulse, trident, arc.
3. If an effect mask exists, it constructs an effect call and enqueues it in `ItemEffectRenderQueue` instead of submitting it as custom geometry.

### Flush

Flush sites must recreate the 1.21.1 ordering boundary:

1. vanilla/Neo item features complete their base rendering for the current context
2. Avaritia flushes queued effect calls for that context
3. each effect call applies uniforms and draws mask quads
4. each effect RenderType is explicitly ended/flushed

The initial implementation should identify and wire the safest flush points for:

- GUI/inventory item rendering
- first-person held item rendering
- third-person/world item rendering

If a single dispatcher-level flush point can cover all contexts after base item features are complete, prefer it. If not, use small context-specific mixins with exact descriptors and no rendering takeover.

## Expected Behavioral Changes

- Eternal Singularity should continue rendering correctly in inventory.
- Infinity Helmet and Infinity Trident should render their base item pixels and effect mask consistently in inventory.
- First-person and third-person display should use the same base/effect ordering semantics.
- Empty or low-fill Matter Cluster may have weak/no cosmic overlay because opacity is content-driven, but the base item must remain visible.

## Testing And Verification

- Compile after removing the trial code and after adding the new queue.
- Launch client and inspect:
  - Eternal Singularity inventory render
  - Infinity Helmet inventory render
  - Infinity Trident inventory render
  - Matter Cluster empty, partial, and full inventory render
  - first-person and third-person held Infinity Helmet / Trident / Sword where applicable
- Confirm no mixin descriptor errors during startup.
- Confirm no `#` style render logs or shader uniform missing warnings if debug logging is enabled.

## Risks

- Flush points in 26.1.2 may differ by GUI, first-person, and world item paths.
- Effect calls need pose snapshots; stale or wrong pose capture can make overlays render offset from the base item.
- Uniform binding depends on RenderType identity, so effect RenderTypes must remain per-call or otherwise isolated.
- Existing unrelated worktree changes should not be reverted during the refactor.
