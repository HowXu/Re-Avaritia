# Now2.md — 星空渲染问题排查记录（压缩总结）

## 1. 当前状态（最新一次改动后）

- 用户的最近观察：使用最新代码后，未再给具体反馈，期望通过压缩记录帮后续接手者快速续上。
- 已改的代码（仍保留改动，未恢复）：
  - `AvaritiaShaders.java`：item effect pipeline 用 `BlendFunction.LIGHTNING + WRITE_COLOR`，其余 pipeline 用 `TRANSLUCENT + WRITE_ALL`。
  - `AvaritiaItemModelRenderers.java`：`EffectLayerArgument.applyUniforms()` 改为 `public`。
  - `LayeredEffectItemModel.java`：新增 ThreadLocal `FirstPersonPhase`（NONE / BASE / EFFECT）；`update(...)` 在 phase 阶段只提交对应子层；新增 `appendFirstPersonEffectItemLayer(...)`，把 effect quads 的 `MaterialInfo.itemRenderType` 重写到当前帧独立 RenderType，再 `prepareQuadList().addAll(...)`，让 effect 以 item quads 形式进入 `ItemFeatureRenderer`；非第一人称路径未改。
  - `mixin/client/ItemInHandRendererMixin.java`（新建）：HEAD 注入 `ItemInHandRenderer.renderItem(...)`；仅 `displayContext.firstPerson()` 尝试接管；两次 `submitFirstPersonPhase`，先 BASE 后 EFFECT，最后 `ci.cancel()`；不再调 `renderAllFeatures()`。
  - `avaritia.mixins.json`：注册 `client.ItemInHandRendererMixin`。
  - `gametest/InfinitySwordGameTests.java`：D（用户此前已删除，本次未改）。
  - `generated/resources/assets/avaritia/items/*.json`：M（datagen 输出，不是源变更）。
- `Now.md` 仍保存有上一阶段更详细的日志。

## 2. 关键文件入口

- `ItemEffect.java`：当前仅作 `newRenderType()` / `uniformEffect()` 的运行时包装，无第一人称分支（已撤掉）。
- `LayeredEffectItemModel.java`：第一人称两阶段（BASE / EFFECT）逻辑在这里。普通上下文保持单次 `wrapped.update` + `appendEffectLayer`。
- `AvaritiaItemModelRenderers.EffectLayerArgument.applyUniforms()`：被第一人称 effect phase 主动调用，写 `AvaritiaShaderUniforms.set(renderType, effect, ...)`。
- `mixin/client/ItemInHandRendererMixin.java`：唯一改动点。成功 mixing 已在 debug log 中确认（`ItemInHandRendererMixin ... does use it's CallbackInfo`）。
- `AvaritiaShaders.java`：item pipeline = `LIGHTNING + WRITE_COLOR`。未使用 first-person 专属 pipeline。
- `AvaritiaShaderUniforms.Effect` / `AvaritiaShaderUniforms.bindIfAvaritia`：依赖 `RenderType` identity 的 `IdentityHashMap`。
- `ItemQuadBakery.bakeItemOverlay(...)`：仍是原 `OVERLAY_DEPTH_OFFSET=0.02` 的 overlay。
- `EffectItemModelBaker.java`：只烘一套 `effectQuads`，第一/第三人称都用同一批 quad（只是 `MaterialInfo.itemRenderType` 在第一人称 effect phase 被动态重写）。
- `AvaritiaModelProvider.clientItem(...)`：保留 `hasHaloLayer(model)` 决定 `oversizedInGui`，不触发 `EffectFields`。

## 3. 26.1.2 渲染链要点

- `ItemStackRenderState.submit(...)` 遍历 `layers`，每层交给 `ItemStackRenderState.LayerRenderState.submit(...)`：有 `SpecialModelRenderer` 就走 special，没有就走 `SubmitNodeCollector.submitItem(...)`。
- `SubmitNodeStorage` 按 `order(int)` 分桶（同 bucket 内用 List）。
- `ItemFeatureRenderer.renderSolid / renderTranslucent` 遍历 `itemSubmits`，按 submit list 顺序写入 `BufferSource`。
- `CustomFeatureRenderer` 处理 `customGeometrySubmits`，按 RenderType 分桶后再写入 `BufferSource`。
- `FeatureRenderDispatcher.renderAllFeatures()`：solid → translucent → particles → clear；不直接 flush framebuffer，但 `renderTranslucent` 会把 `translucentCustomGeometrySubmits` 写到 `BufferSource` 的同一份 `BufferBuilder`。
- GUI oversized 走 `OversizedItemRenderer.renderToTexture(...)`，单独 framebuffer。
- 第一人称路径：`ItemInHandRenderer.renderItem(...)` 构造新 `ItemStackRenderState` → `ItemModelResolver.updateForTopItem(...)` → `ItemStackRenderState.submit(pose, collector, light, overlay, 0)`，之后 frame 阶段才被画。
- `BakedQuad.MaterialInfo` 在 26.1.2 是 public 构造：`(TextureAtlasSprite, ChunkSectionLayer, RenderType, int tintIndex, boolean shade, int lightEmission)`。`ItemFeatureRenderer` 按 `materialInfo.itemRenderType()` 决定走 `renderSolid` 还是 `renderTranslucent`，并按 `RenderType.hasBlending()` 选择队列。

## 4. 历次改动（压缩）

1. blend/depth/order 调参 → 多版本轮流试，均证伪或副作用更大。
2. 把 `effect item pipeline` 改为 `LIGHTNING + WRITE_COLOR`：GUI/物质团/无尽奇点 GUI 恢复正常，但第一人称仍坏。
3. 撤 first-person 专属 pipeline + 专用 uniform enum（先前路径）→ 反而不稳定，被回退。
4. 尝试 `EffectSpecialRenderer` 用 `EQUAL` 深度 + depth-matched quads → 第一人称星空完全消失。
5. 放宽到 `LESS_THAN_OR_EQUAL` + 保留 offset overlay → 仍未恢复星空。
6. `ClientItem.Properties.oversizedInGui = needsOversizedGui(model)` 强制 effect 物品 oversized：物质团/无尽奇点 GUI 恢复，但第一/第三人称星空消失。
7. 回退 oversized 方案；回到 `(LIGHTNING + WRITE_COLOR)` 单路径，物质团/无尽奇点 GUI 正常，第一人称仍坏。
8. Mixin 路线（current）：
   - 8.1 第一次实现：两次 phase update + `renderAllFeatures()` → 表现“完全没变化”。
   - 8.2 排查发现 `clearFirstPersonPhase()` 把 handled 也清掉，导致 Mixin 误判“非 Avaritia effect model”，回退到 vanilla。修复后用户反馈“还是没用”。
   - 8.3 移除 `renderAllFeatures()` 调用（时机不对，会污染当前提交），改将 effect phase 做成 item quads（重写 `MaterialInfo.itemRenderType`），让 effect 走 `ItemFeatureRenderer` 与 base 同列。
   - 8.4 当前文件状态见 §1，未再继续迭代。

## 5. 当前最佳猜测根因

- 26.1.2 第一人称渲染已不是即时顺序：base 与 effect 走 `SubmitNodeStorage` 后再被 `ItemFeatureRenderer` / `CustomFeatureRenderer` 各自处理；两条路径不是同一个 storage list。
- effect 的 alpha write 策略和 blend 公式在 `ItemFeatureRenderer.renderTranslucent` 路径上，会和底图 item quads 一起参与 framebuffer 合成；星空 shader 在 mask 透明区输出 `col.a = mask.r * opacity`，当 mask.r 较低时这些 fragment 会把“本应是不透明的底图区域”也拉低 alpha。
- 之前尝试通过 blend/depth/order 直接修的都是症状，且每次都会破坏其它上下文。
- 现在的方法（Mixin 两阶段 + effect 改为 item quads）是想利用 `ItemFeatureRenderer.renderTranslucent` 内部仍按 submit list 顺序处理 item quads，让 base 在 effect 之前画入 framebuffer，从而让 effect 看起来是“覆盖在底图上”。
- 风险：若 `ItemFeatureRenderer` 实际按 RenderType 分桶而不是按 item quads 列表严格顺序提交，这条路径也不会复刻 1.21.1 的“base 先刷出，cosmic 后 flush”效果。

## 6. 下一轮推荐验证（不需要新设计，直接测）

- 看第一人称剑/头盔/三叉戟是否重新出现星空。
- 物质团 / 无尽奇点第一人称是否仍整体透明（如果还有，说明改成 item quads 后仍然走 `renderTranslucent` 把星空透明 fragment 拉低 alpha）。
- GUI / 第三人称 / 展示框是否仍是当前已知正常状态（重要：这条路径不能受影响）。
- 如第一人称星空恢复但仍和底图互相污染，下一步在 shader 内把 `col.a = 1.0` 强写（仅作诊断），验证 alpha 是否就是真正的污染源。
- 如第一/第三人称星空彻底消失，要回退 Mixin，进一步把第一/第三人称的 effect 路径隔离，而不是再继续改 Mixin。

## 7. 已尝试但确认无效或破坏更大的方向（避免重复）

- 改 `OVERLAY_DEPTH_OFFSET` 0.02 / 0.05 / 0.2：仅修复剑/特定物品首人称显示，但其它上下文仍异常。
- 改剑的 model `parent`（`handheld` ↔ `generated`）：无效果。
- 改剑为单 layer：用户确认仍异常。
- 换贴图为 `infinity_bow/idle`：仅证明贴图本身不是问题。
- 关闭 `viewOffset` / 关闭 `sortOnUpload`：无效果。
- 改 effect layer `order(1) → 0 / (-1)`：无效果。
- 改 depth state 为 `(ALWAYS_PASS, false)` / `(LESS_THAN_OR_EQUAL, false)` / `(LESS_THAN_OR_EQUAL, true)`：无持久效果。
- `CombinedItemModel` 把 effect quad 合并进 baked quad 路径：导致 uniform 缺失。
- Mixin `cacheSliceForRenderType` / `cacheSliceForActiveRenderType`：仅在没有 effect quads 时崩溃修复有效，但 baked quad 路径依然缺 uniform。
- first-person 专属 shader pipeline（`*_FIRST_PERSON_SHADER`）：反而破坏 GUI uniform / RenderType 匹配。
- first-person 路径 depth-matched quads + `EQUAL` 深度：星空完全不画。
- 第一人称路径直接 `endBatch()` / `renderAllFeatures()` flush：仍未解决问题；已被回退。