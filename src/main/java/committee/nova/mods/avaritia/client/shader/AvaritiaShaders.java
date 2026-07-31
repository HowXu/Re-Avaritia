package committee.nova.mods.avaritia.client.shader;

import committee.nova.mods.avaritia.Const;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

/**
 * Registers Avaritia's custom effect render pipelines.
 */
public class AvaritiaShaders {
    private static final DepthStencilState TRANSLUCENT_EFFECT_DEPTH =
            new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false);
    private static final DepthStencilState ITEM_EFFECT_OVERLAY_DEPTH =
            new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true);
    // RGB-only additive overlay. Writing alpha from the mask makes GUI/first-person targets treat
    // the cosmic layer as the whole item pixel, so the item base remains responsible for alpha.
    private static final ColorTargetState EFFECT_OVERLAY_COLOR_TARGET =
            new ColorTargetState(java.util.Optional.of(BlendFunction.LIGHTNING), ColorTargetState.WRITE_COLOR);
    private static final ColorTargetState TRANSLUCENT_COLOR_AND_ALPHA =
            new ColorTargetState(BlendFunction.TRANSLUCENT);

    public static final float[] COSMIC_UVS = new float[40];
    public static TextureAtlasSprite[] COSMIC_SPRITES = new TextureAtlasSprite[10];
    public static final float[] ETERNAL_UVS = new float[40];
    public static TextureAtlasSprite[] ETERNAL_SPRITES = new TextureAtlasSprite[10];

    public static RenderPipeline COSMIC_SHADER;
    public static RenderPipeline COSMIC_ARMOR_SHADER;
    public static RenderPipeline HELL_SHADER;
    public static RenderPipeline ETERNAL_SHADER;
    public static RenderPipeline UNSTABLE_SHADER;
    public static RenderPipeline BLACK_HOLE_SHADER;

    public static void onRegisterShaders(RegisterRenderPipelinesEvent event) {
        COSMIC_SHADER = registerItemPipeline(event, "cosmic", DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS);
        COSMIC_ARMOR_SHADER = registerPipeline(event, "cosmic_armor", "cosmic", DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS);
        HELL_SHADER = registerItemPipeline(event, "hell", DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS);
        ETERNAL_SHADER = registerItemPipeline(event, "eternal", DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS);
        UNSTABLE_SHADER = registerItemPipeline(event, "unstable", DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS);
        BLACK_HOLE_SHADER = registerPipeline(event, "black_hole", DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS);
        AvaritiaRenderTypes.reloadEffectTypes();
    }

    private static RenderPipeline registerPipeline(RegisterRenderPipelinesEvent event, String name, VertexFormat vertexFormat, VertexFormat.Mode mode) {
        return registerPipeline(event, name, name, vertexFormat, mode, TRANSLUCENT_EFFECT_DEPTH, TRANSLUCENT_COLOR_AND_ALPHA);
    }

    private static RenderPipeline registerItemPipeline(RegisterRenderPipelinesEvent event, String name, VertexFormat vertexFormat, VertexFormat.Mode mode) {
        return registerPipeline(event, name, name, vertexFormat, mode, ITEM_EFFECT_OVERLAY_DEPTH, EFFECT_OVERLAY_COLOR_TARGET);
    }

    private static RenderPipeline registerPipeline(RegisterRenderPipelinesEvent event, String name, String shaderName, VertexFormat vertexFormat, VertexFormat.Mode mode) {
        return registerPipeline(event, name, shaderName, vertexFormat, mode, TRANSLUCENT_EFFECT_DEPTH, TRANSLUCENT_COLOR_AND_ALPHA);
    }

    private static RenderPipeline registerPipeline(RegisterRenderPipelinesEvent event, String name, String shaderName,
                                                   VertexFormat vertexFormat, VertexFormat.Mode mode,
                                                   DepthStencilState depthStencilState, ColorTargetState colorTargetState) {
        var shader = Const.rl("core/" + shaderName);
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
                .withLocation(Const.rl(name))
                .withVertexShader(shader)
                .withFragmentShader(shader)
                .withSampler("Sampler2")
                .withUniform(AvaritiaShaderUniforms.UNIFORM_NAME, UniformType.UNIFORM_BUFFER)
                .withColorTargetState(colorTargetState)
                .withDepthStencilState(depthStencilState)
                .withCull(false)
                .withVertexFormat(vertexFormat, mode)
                .build();
        event.registerPipeline(pipeline);
        return pipeline;
    }
}
