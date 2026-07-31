# Now.md — 寰宇支配之剑 / 星空物品渲染问题排查记录

## 1. 当前状态（最新一次改动后）

- 用户的当前观察：星空层“有玻璃质感”，但原始问题仍然存在 —— **GUI / 第一人称手持下，星空层周围半透明区域把底层双 layer 贴图也一起透明化**；**掉落物 / 物品展示框 / 第三人称** 渲染正常。
- 已知表现一致的现象：
  - 同一星空 effect 路径在三叉戟、无尽头盔上也出现 GUI / 第一人称“透明”。
  - 物质团、永恒奇点（带 halo，触发 `oversized_in_gui`）经过 alpha 修复后已能正常显示。
- 残留代码改动（仅本会话保留的诊断/修复，其他文件已 `git checkout` 回到 main）：
  - `src/main/java/committee/nova/mods/avaritia/client/shader/AvaritiaShaders.java`：item effect pipeline（`cosmic / hell / eternal / unstable`）使用 `BlendFunction.LIGHTNING + ColorTargetState.WRITE_COLOR`；其它 pipeline（`cosmic_armor` 等）仍是 `TRANSLUCENT + WRITE_ALL`。
  - `src/main/java/committee/nova/mods/avaritia/gametest/InfinitySwordGameTests.java`：被删除（这是用户最初就删除的，不是这次会话引入，git 仍标记为 D）。

## 2. 关键文件 & 代码入口（研究时反复用到）

- `src/main/java/committee/nova/mods/avaritia/client/model/item/ItemEffect.java`
  - `renderType()` / `newRenderType()` / `pipeline()` / `uniformEffect()` / `uvs()` / `createArgument(...)` / `opacity(...)`
  - `createArgument` 在 `displayContext == GUI` 时把 `externalScale` 设为 100，其它上下文为 1。
  - `newRenderType()` 每帧 `incrementAndGet()` 新建独立 RenderType，避免同一帧多物品共用 uniform。
- `src/main/java/committee/nova/mods/avaritia/client/model/item/LayeredEffectItemModel.java`
  - `update(...)`：wrapped.update → halo/pulse/trident/arc → effect 顺序追加 layer。
  - `appendEffectLayer(...)`：用 `setupSpecialModel(AvaritiaItemModelRenderers.EFFECT, ...)`。
- `src/main/java/committee/nova/mods/avaritia/client/model/loader/AvaritiaItemModelRenderers.java`
  - `ITEM_EFFECT_OVERLAY_SUBMIT_ORDER = 1`，`ITEM_EFFECT_BACKGROUND_SUBMIT_ORDER = -1`。
  - `EffectSpecialRenderer.submit(...)` 调 `submitNodeCollector.order(1).submitCustomGeometry(...)`。
  - `applyUniforms()` 调 `AvaritiaShaderUniforms.set(renderType, effect, ...)`。
- `src/main/java/committee/nova/mods/avaritia/client/shader/AvaritiaShaders.java`
  - `cosmic / hell / eternal / unstable` 通过 `registerItemPipeline` 注册，深度 `LESS_THAN_OR_EQUAL` + 写深度 `true`。
  - `cosmic_armor` 走 `registerPipeline`，深度写 `false`。
  - 颜色目标：默认 `TRANSLUCENT + WRITE_ALL`；item effect pipeline 现在 `LIGHTNING + WRITE_COLOR`。
- `src/main/java/committee/nova/mods/avaritia/client/shader/AvaritiaShaderUniforms.java`
  - `RENDER_TYPE_SLICES`（`IdentityHashMap<RenderType, GpuBufferSlice>`）+ `CURRENT_SLICES`（`EnumMap<Effect, ...>`）。
  - `bindIfAvaritia(renderPass)` 由 `RenderSystemMixin` 在 `bindDefaultUniforms` TAIL 触发。
  - `setActiveRenderType`/`clearActiveRenderType` 由 `RenderTypeMixin` 在 `RenderType.draw` HEAD/TAIL 触发。
- `src/main/java/committee/nova/mods/avaritia/mixin/client/RenderSystemMixin.java`、`RenderPassMixin.java`、`RenderTypeMixin.java`：负责把 `AvaritiaCosmic` uniform 绑定到对应 render pass。
- `src/main/java/committee/nova/mods/avaritia/api/client/model/ItemQuadBakery.java`
  - `bakeItemOverlay(...)`：烘 effect quad，前/后两面、z=8.52 / z=7.48，offset=0.02，inset=0.25 像素。
  - 注释明确指出：在 26.1.2 下 `TextureAtlasSprite#getU/getV` 用 0..1 归一化 UV，所以这里 `uv * MODEL_UNIT`（1/16）把 0..16 模型空间转回 0..1。
- `src/main/java/committee/nova/mods/avaritia/client/model/loader/utils/EffectItemModelBaker.java`
  - `bake(...)` 组合 `CuboidItemModelWrapper`（wrapped）+ effect quads + halo + trident，构造 `LayeredEffectItemModel`。
- `src/main/java/committee/nova/mods/avaritia/init/data/provider/AvaritiaModelProvider.java`
  - `clientItem(...)`：`hasHaloLayer(model)` → `new ClientItem.Properties(true, true, 1.0F)`（含 `oversizedInGui=true`），否则 `DEFAULT`（`oversizedInGui=false`）。
  - 关键模型注册：`infinity_sword`（`Cosmic/Hell` + 双 layer `layeredHandheldModel`）、`infinity_helmet/...`（`Cosmic` 单 layer）、`singularity / eternal_singularity`（`Halo/HaloCosmic`，`oversizedInGui=true`）、`matter_cluster / full_matter_cluster`（`Cosmic/HaloCosmic`，`oversizedInGui=true`）、`infinity_trident`（`CosmicArc` + `displayContextDispatch`）。
- `src/main/resources/assets/avaritia/shaders/core/cosmic.fsh`
  - mask 采样、16 层循环、按 mult 调亮度，`col.a *= mask.r * opacity`，最终 `fragColor = apply_fog(col * ColorModulator, ...)`。

## 3. 渲染链对照（25.1.215 vs 26.1.2）

| 维度 | 1.21.1 (25.1.215) | 26.1.2 |
| --- | --- | --- |
| Item 渲染入口 | `ItemRenderer.render` 直接遍历 `BakedModel.getQuads(...)` → `renderQuadList` | `ItemStackRenderState.submit` + `LayerRenderState.submit`，分 layer |
| 多 layer 模型 quad 提取 | `ItemModelGenerator.bakeGeneratedSprite` 等 | `ResolvedModel.bakeTopGeometry(...)`，按 model JSON 的 layer 顺序生成 quad |
| 物品 effect 自定义 | `BakedModel` 重写 `getQuads` | `ItemModel` interface + `SpecialModelRenderer`（`submitCustomGeometry(order, renderType, ...)`） |
| GUI 物品绘制 | `GuiGraphics.renderItem` → `ItemRenderer.render` | 离屏 `OversizedItemRenderer` + `MultiBufferSource.endBatch`，或普通 GUI 路径 |
| First-person 手持 | `ItemInHandRenderer.renderHandsWithItems` → `ItemRenderer.render` | `ItemInHandRenderer.renderHandsWithItems` → `ItemRenderer.renderStatic` → `render(itemStack, ItemDisplayContext.FIRST_PERSON_*, ...)`（基本一致，但 uniform 缓存受 `RenderType` identity 影响） |
| 物品展示框 / 掉落物 / 第三人称 | entity renderer 通过 `MultiBufferSource` 提交 | entity renderer 通过 `MultiBufferSource` 提交 |
| atlas / oversized GUI 路径 | `BakedModel` 渲染 | `ClientItem.Properties.oversizedInGui` 触发 `OversizedItemRenderer.renderToTexture` → `featureRenderDispatcher.getSubmitNodeStorage()` → `itemStackRenderState.submit(...)` + `renderAllFeatures()` |

差异点：
- 1.21.1 时代自定义模型可以直接改 quad；26.1.2 必须走 `ItemModel` + `SpecialModelRenderer`。
- 26.1.2 用 `SubmitNodeStorage.submitsPerOrder: Int2ObjectAVLTreeMap` 排序；`submitCustomGeometry(order(1), ...)` 单独一个桶。
- 26.1.2 中 `LayerRenderState.submit` 调用顺序就是 layer 索引 0,1,2...；每层可能走 `submitItem`（quads）或 `specialRenderer.submit`。

## 4. 历次改动 & 用户反馈（按顺序压缩）

1. **初始研究（仅研究）**：定位 `AvaritiaShaders.java:76-86` 用 `texturedForwardOffset`，会被 view-space offset 在某些角度反向；改用 `withDepthStencilState((LESS_THAN_OR_EQUAL, true))` + 关闭 view offset + `OVERLAY_DEPTH_OFFSET=0.02`。`ceea7e18`/`e612af73` 提交里能看到 `INFINITY_FORWARD_OFFSET` 的删除与 depth offset 调整。
2. **layer1 单独贴图测试**：把 `infinity_sword.json` 改成只有 `layer0`，或换成 `infinity_bow/idle.png`，用户报告“仍然有问题”。
3. **极端 debug**：`LayeredEffectItemModel.update` 包 `if (false)` 屏蔽 effect/halo/pulse/arc/trident；用户确认仅剑基础模型可见。
4. **缩小范围**：仅屏蔽 `InfinitySwordItem` 的 effect 层 → 用户确认是剑自身的 effect 路径问题，不是公共代码。
5. **改 `order(1) → 0` 让 effect 与 wrapped 同桶**：无效果。
6. **改 `ITEM_EFFECT_OVERLAY_DEPTH = (ALWAYS_PASS, false)`**：无星空；用户说“星空渲染顺序出现问题”。
7. **改回 `(LESS_THAN_OR_EQUAL, false)`**：仍无星空。
8. **改回 `(LESS_THAN_OR_EQUAL, true)` + `order(1)`**：回到最初。
9. **第一轮大改：把 effect quad 合并到 baked quad 路径**（新建 `CombinedItemModel.java`、`LayeredEffectItemModel` 不再调 `appendEffectLayer`）：GUI 下 `OversizedItemAtlas` 出现 `Missing uniform AvaritiaCosmic`，原因是 baked quad 路径不会再调 `EffectSpecialRenderer.applyUniforms()`，uniform 未绑定。
10. **在 `CombinedItemModel.update` 主动调 `AvaritiaShaderUniforms.set(effect, ...)`** + `ItemEffect.uniformEffect()` 改成 public：星空出现但“非常密”。怀疑 GUI 下 `externalScale=1`（应是 100）让星空过密，或 effect quad 被额外提交多次。
11. **撤掉 `CombinedItemModel`，回到 `SpecialModelRenderer` 路径 + `cacheSliceForRenderType` mixin hack**：仍出现 `Missing uniform AvaritiaCosmic`，说明 uniform 缓存写入时机不对。
12. **撤掉 mixin hack，恢复干净基线**：回到用户描述的“GUI/第一人称透明，展示框/第三人称正常”原始问题。
13. **新一轮诊断：把 `Mask` 渲染放在屏幕 vs GUI 路径上的差异比对** → 怀疑是 alpha 写入导致 GUI atlas 的 alpha 被冲掉。
14. **第一处 alpha 修复**：`ColorTargetState.WRITE_COLOR` 让 effect pipeline 不写 alpha → 物质团、永恒奇点在 GUI 正常（因为它们带 halo，触发了 `OversizedItemRenderer` 重合成路径，独立 alpha），但剑/三叉戟/无尽头盔 GUI 变成“整个物品透明”（不走 oversized 时 atlas 直接拿到 `alpha=0`）。
15. **当前 alpha 修复**：item effect pipeline 改用 `BlendFunction.LIGHTNING + WRITE_COLOR`：
    - RGB 混合 `(SRC_ALPHA, ONE)` —— 颜色 = 底层 + effect·alpha，仍是叠加；
    - alpha 写 1 —— GUI atlas 上 effect 区域 alpha 永远是 1，整件物品不会再被 blend 透明掉；
    - 装备（`cosmic_armor`）维持原 `TRANSLUCENT + WRITE_ALL`，避免影响装备透明感。
16. **用户反馈**：星空层“有玻璃质感”，但 GUI/第一人称下底层双 layer 仍透明。
17. **下一步思路**（用户尚未确认）：
    - `LIGHTNING` 仍会修改 RGB，叠加后暗部被冲淡。GUI atlas 里底层贴图本身是先画好的，`LIGHTNING` 只在 effect 区域加色；如果 atlas 自身已经是半透明叠加（item base layer alpha<1），那么“玻璃质感”=叠加效果一致，但仍会出现整体偏亮/偏冷。
    - 如果要彻底修，方向是：让 item base layer 也走 oversized + 离屏重合成（保证 alpha=1），或者在 `OversizedItemRenderer` 里把 effect quad 走一个独立的 alpha=1 的 pipeline；GUI 非 oversized 路径需要重新思考是否要强制走 oversized。
    - 当前最值得验证：`mask` 周围 alpha = 0 是否还让 alpha 写入路径在 atlas 里把物品 alpha 改掉？可以通过把 `cosmic.fsh` 的 `fragColor.a = 1.0` 强写 alpha=1 验证（仅作诊断，不留作修复）。

## 5. 当前最佳猜测根因

`ItemInHandRenderer` 和普通 GUI（非 oversized）路径都通过 `ItemStackRenderState.submit` → `LayerRenderState.submit` 顺序提交所有 layer。Effect layer 的 `submitCustomGeometry(order(1), ...)` 把 quad 推到 `SubmitNodeStorage` 的 order=1 桶。`effect pipeline` 的 alpha 输出和混合策略直接决定了 GUI atlas / 第一人称 framebuffer 的合成结果：

- 26.1.2 在 GUI 非 oversized 路径下，物品 atlas 的 alpha 通道是“物品 alpha”；
- effect pipeline 用 TRANSLUCENT 时，其 alpha blend `(ONE, ONE_MINUS_SRC_ALPHA)` 会把 atlas 上已画好物品的 alpha 拉低 → 整件物品看上去半透明；
- 改成 `WRITE_COLOR` 后 effect quad 不写 alpha，但 atlas 的 alpha 仍可能在某些路径下被反推为 0（例如 fragment 输出的 alpha 直接通过 `FragData` 缓存写入默认 0），导致整个物品 GUI 全透明；
- 改成 `LIGHTNING + WRITE_COLOR` 后 alpha 写 1 修掉透明问题，但叠加颜色让“星空层 + 底层贴图”看起来像玻璃质感，底层双 layer 的细节仍被遮盖。

真正要修复，需要让 **底层贴图本身** 在 GUI / 第一人称下也是 alpha=1（不透明），而不是依赖 effect 的 alpha 输出。最直接的路径是：让 `infinity_sword` / `infinity_helmet` / `infinity_trident` 等也带上 `oversizedInGui=true`，让 GUI 走 `OversizedItemRenderer` 离屏路径；或给 `baked quad` 路径（item base）调 `minecraft:item/generated` 的切面渲染，用 `Sheets.translucentItemSheet()` 的标准 alpha=1 路径。

## 6. 已尝试但确认无效的方向（避免重复）

- 改 `OVERLAY_DEPTH_OFFSET` 0.02 / 0.05 / 0.2：仅修复剑/特定物品首人称显示，但其它上下文仍异常，且与原始问题无关。
- 改剑的 model `parent`（`handheld` ↔ `generated`）：无效果。
- 改剑为单 layer：用户确认仍异常。
- 换贴图为 `infinity_bow/idle`：仅证明贴图本身不是问题。
- 关闭 `viewOffset` / 关闭 `sortOnUpload`：无效果。
- 改 effect layer `order(1) → 0` / `(−1)`：无效果。
- 改 depth state 为 `(ALWAYS_PASS, false)` / `(LESS_THAN_OR_EQUAL, false)` / `(LESS_THAN_OR_EQUAL, true)`：无持久效果。
- `CombinedItemModel` 把 effect quad 合并进 baked quad 路径：导致 uniform 缺失 crash。
- 反射读 `ItemStackRenderState.activeLayerCount` 注入 effect quad：依然 GUI 全透明（用户已撤掉方案）。
- mixin hack `cacheSliceForRenderType` / `cacheSliceForActiveRenderType`：仅在没有 effect quads 时崩溃修复有效，但 baked quad 路径依然缺 uniform。

## 7. 当前 git diff（唯一未还原改动）

```
src/main/java/committee/nova/mods/avaritia/client/shader/AvaritiaShaders.java | 17 ++++++++++++-----
1 file changed, 12 insertions(+), 5 deletions(-)

D src/main/java/committee/nova/mods/avaritia/gametest/InfinitySwordGameTests.java
```

`AvaritiaShaders.java` 实际改动（基线 `git checkout` 后的 17 行）：
1. 新增 `private static final ColorTargetState EFFECT_OVERLAY_COLOR_TARGET = new ColorTargetState(Optional.of(BlendFunction.LIGHTNING), ColorTargetState.WRITE_COLOR);`
2. 新增 `private static final ColorTargetState TRANSLUCENT_COLOR_AND_ALPHA = new ColorTargetState(BlendFunction.TRANSLUCENT);`
3. `registerPipeline(...)` 三处都加上 `ColorTargetState` 参数，item pipeline 用 `EFFECT_OVERLAY_COLOR_TARGET`，其它用 `TRANSLUCENT_COLOR_AND_ALPHA`。
4. 内部 `withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))` 改为 `withColorTargetState(colorTargetState)`（参数化）。

## 8. 下一步推荐行动

1. **检查 GUI 非 oversized 路径下的 atlas 合成**：确认 `minecraft:item/generated` / `minecraft:item/handheld` 在 atlas 里的 fragment alpha 默认值。如果 item base layer 的 fragment alpha < 1（半透明模板），则 effect 任何 alpha 改动都会被放大。
2. **强制剑/三叉戟/无尽头盔走 oversizedInGui**：在 `clientItem(...)` 里把 `Cosmic/Hell/CosmicArc` 都视为 `oversizedInGui=true`，让 GUI 走离屏纹理 → 与物质团、永恒奇点走同一合成路径。
3. **改 cosmic shader 强写 alpha=1** 作为诊断：在 `cosmic.fsh` 末尾加 `fragColor.a = 1.0;`，看 GUI 下整件物品是否恢复不透明。如果是，则进一步把 effect pipeline 的 alpha 写入策略拆开（oversized 与非 oversized 用不同 BlendFunction）。
4. **保留 LIGHTNING + WRITE_COLOR** 作为基础修复，再叠加方案 2 或 3。