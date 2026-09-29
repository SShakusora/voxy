package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_COLOR_WRITEMASK;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_FUNC;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_WRITEMASK;
import static org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL11C.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11C.glColorMask;
import static org.lwjgl.opengl.GL11C.glDepthMask;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glGetBoolean;
import static org.lwjgl.opengl.GL11C.glGetBooleanv;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glGetIntegerv;
import static org.lwjgl.opengl.GL11C.glIsEnabled;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL45C.*;
import static org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING;

public class IrisVoxyRenderPipeline extends AbstractRenderPipeline {
    private final IrisVoxyRenderPipelineData data;
    private final FullscreenBlit depthBlit;
    public final DepthFramebuffer fbTranslucent = new DepthFramebuffer(this.fb.getFormat());

    private final FullscreenBlit shaderDepthHackFixTransformBlit;

    private final GlBuffer shaderUniforms;

    public IrisVoxyRenderPipeline(RenderProperties properties, IrisVoxyRenderPipelineData data, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        super(properties, nodeManager, nodeCleaner, traversal, frexSupplier, data.shouldDeferTranslucency());
        this.data = data;
        if (this.data.thePipeline != null) {
            throw new IllegalStateException("Pipeline data already bound");
        }
        this.data.thePipeline = this;

        //Bind the drawbuffers
        var oDT = this.data.opaqueDrawTargets;
        int[] binding = new int[oDT.length];
        for (int i = 0; i < oDT.length; i++) {
            binding[i] = GL30.GL_COLOR_ATTACHMENT0+i;
            glNamedFramebufferTexture(this.fb.framebuffer.id, GL30.GL_COLOR_ATTACHMENT0+i, oDT[i], 0);
        }
        glNamedFramebufferDrawBuffers(this.fb.framebuffer.id, binding);

        var tDT = this.data.translucentDrawTargets;
        binding = new int[tDT.length];
        for (int i = 0; i < tDT.length; i++) {
            binding[i] = GL30.GL_COLOR_ATTACHMENT0+i;
            glNamedFramebufferTexture(this.fbTranslucent.framebuffer.id, GL30.GL_COLOR_ATTACHMENT0+i, tDT[i], 0);
        }
        glNamedFramebufferDrawBuffers(this.fbTranslucent.framebuffer.id, binding);

        this.fb.framebuffer.verify();
        this.fbTranslucent.framebuffer.verify();

        if (data.getUniforms() != null) {
            this.shaderUniforms = new GlBuffer(data.getUniforms().size());
        } else {
            this.shaderUniforms = null;
        }

        if (!this.data.skipShaderDepthHackFix) {
            this.shaderDepthHackFixTransformBlit = new FullscreenBlit(properties, "voxy:post/fullscreen2.vert", "voxy:post/noop.frag");
        } else {
            this.shaderDepthHackFixTransformBlit = null;
        }

        this.depthBlit = new FullscreenBlit(properties, "voxy:post/blit_texture_depth_cutout.frag");
    }

    @Override
    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {
        modelService.factory.setCustomBlockStateMapping(WorldRenderingSettings.INSTANCE.getBlockStateIds());
    }

    @Override
    public void free() {
        if (this.data.thePipeline != this) {
            throw new IllegalStateException();
        }
        this.data.thePipeline = null;

        this.depthBlit.delete();
        this.fbTranslucent.free();

        if (this.shaderDepthHackFixTransformBlit != null) {
            this.shaderDepthHackFixTransformBlit.delete();
        }

        if (this.shaderUniforms != null) {
            this.shaderUniforms.free();
        }

        super.free0();
    }

    @Override
    public void preSetup(Viewport<?> viewport) {
        super.preSetup(viewport);
        if (this.shaderUniforms != null) {
            //Update the uniforms
            long ptr = UploadStream.INSTANCE.uploadTo(this.shaderUniforms);
            this.data.getUniforms().updater().accept(ptr);
            UploadStream.INSTANCE.commit();
        }
    }

    @Override
    protected int setup(Viewport<?> viewport, int sourceFramebuffer, int srcWidth, int srcHeight) {
        this.fb.resize(viewport.width, viewport.height);
        this.fbTranslucent.resize(viewport.width, viewport.height);

        if (false) {//TODO: only do this if shader specifies
            //Clear the colour component
            glBindFramebuffer(GL_FRAMEBUFFER, this.fb.framebuffer.id);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
        }

        if (!this.data.useViewportDims) {
            srcWidth = viewport.width;
            srcHeight = viewport.height;
        }
        this.initDepthStencil(viewport, sourceFramebuffer, this.fb.framebuffer.id, srcWidth, srcHeight, viewport.width, viewport.height);
        return this.fb.getDepthTex().id;
    }

    @Override
    protected void postOpaquePreTranslucent(Viewport<?> viewport, int sourceFrameBuffer) {
        // The depth setup now reconstructs the vanilla occluder with the Voxy
        // projection.  The legacy hack writes FAR over stencil==0 pixels and
        // would erase that reconstructed depth, so it is only valid for the
        // old sentinel-based setup.
        if (!this.usesDepthAwareVanillaDepth() && this.shaderDepthHackFixTransformBlit != null) {
            this.fb.bind();
            glEnable(GL_DEPTH_TEST);
            glColorMask(false, false, false, false);
            glDepthFunc(GL_ALWAYS);
            glStencilFunc(GL_EQUAL, 0, 0xFF);//set the depth to 1 where the mask is 0
            this.shaderDepthHackFixTransformBlit.blit();
            glStencilFunc(GL_EQUAL, 1, 0xFF);//revert the mask test
            glDepthFunc(this.properties.closerEqualDepthCompare());
            glColorMask(true, true, true, true);
        }

        glTextureBarrier();

        int msk = GL_DEPTH_BUFFER_BIT|GL_STENCIL_BUFFER_BIT;
        if (true) {//TODO: make shader specified
            if (false) {//TODO: only do this if shader specifies
                glBindFramebuffer(GL_FRAMEBUFFER, this.fbTranslucent.framebuffer.id);
                glClearColor(0, 0, 0, 0);
                glClear(GL_COLOR_BUFFER_BIT);
            }
        } else {
            msk |= GL_COLOR_BUFFER_BIT;
        }
        glBlitNamedFramebuffer(this.fb.framebuffer.id, this.fbTranslucent.framebuffer.id, 0,0, viewport.width, viewport.height, 0,0, viewport.width, viewport.height, msk, GL_NEAREST);
    }

    @Override
    public void prepareDepthForDynamicGeometry(Viewport<?> viewport, int outputFramebuffer, int outputWidth, int outputHeight) {
        // The shader pack requested that Voxy stay out of the vanilla depth
        // attachment.  Dynamic entities still need opaque LOD depth for their
        // normal depth test, so merge only the opaque framebuffer here.  The
        // translucent framebuffer is deliberately not used as it contains water
        // and other geometry that must not occlude Create/Flywheel parts.
        if (this.data.renderToVanillaDepth || viewport == null || outputFramebuffer == 0 || outputWidth <= 0 || outputHeight <= 0 || this.fb.getDepthTex() == null) {
            return;
        }

        int previousDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int previousVertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int previousElementArray = glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING);
        int previousTexture = org.lwjgl.opengl.GL30.glGetIntegeri(GL_TEXTURE_BINDING_2D, 0);
        int previousSampler = org.lwjgl.opengl.GL30.glGetIntegeri(GL_SAMPLER_BINDING, 0);
        int[] previousViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, previousViewport);

        int previousDepthFunction = glGetInteger(GL_DEPTH_FUNC);
        boolean previousDepthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean previousStencilTest = glIsEnabled(GL_STENCIL_TEST);
        boolean previousBlend = glIsEnabled(GL_BLEND);
        boolean previousCull = glIsEnabled(GL_CULL_FACE);
        boolean previousScissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean previousDepthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        boolean[] previousColorMask = new boolean[4];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var colorMask = stack.malloc(4);
            glGetBooleanv(GL_COLOR_WRITEMASK, colorMask);
            for (int i = 0; i < previousColorMask.length; i++) {
                previousColorMask[i] = colorMask.get(i) != 0;
            }
        }

        try {
            glBindFramebuffer(GL_FRAMEBUFFER, outputFramebuffer);
            glViewport(0, 0, outputWidth, outputHeight);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(this.properties.closerEqualDepthCompare());
            glDepthMask(true);
            glColorMask(false, false, false, false);

            AbstractRenderPipeline.transformBlitDepth(this.depthBlit,
                    this.fb.getDepthTex().id, outputFramebuffer,
                    viewport, new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView));
        } finally {
            glColorMask(previousColorMask[0], previousColorMask[1], previousColorMask[2], previousColorMask[3]);
            glDepthMask(previousDepthMask);
            glDepthFunc(previousDepthFunction);

            if (previousDepthTest) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
            if (previousStencilTest) glEnable(GL_STENCIL_TEST); else glDisable(GL_STENCIL_TEST);
            if (previousBlend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
            if (previousCull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
            if (previousScissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);

            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            glUseProgram(previousProgram);
            glBindVertexArray(previousVertexArray);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, previousElementArray);
            glBindTextureUnit(0, previousTexture);
            glBindSampler(0, previousSampler);
        }
    }

    @Override
    protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        if (this.data.renderToVanillaDepth && srcWidth == viewport.width  && srcHeight == viewport.height) {//We can only depthblit out if destination size is the same
            glColorMask(false, false, false, false);
            AbstractRenderPipeline.transformBlitDepth(this.depthBlit,
                    this.fbTranslucent.getDepthTex().id, sourceFrameBuffer,
                    viewport, new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView));
            glColorMask(true, true, true, true);
        } else {
            // normally disabled by AbstractRenderPipeline but since we are skipping it we do it here
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_DEPTH_TEST);
        }
    }


    @Override
    public void bindUniforms() {
        this.bindUniforms(UNIFORM_BINDING_POINT);
    }

    @Override
    public void bindUniforms(int bindingPoint) {
        if (this.shaderUniforms != null) {
            GL30.glBindBufferBase(GL_UNIFORM_BUFFER, bindingPoint, this.shaderUniforms.id);// todo: dont randomly select this to 5
        }
    }

    private void doBindings() {
        this.bindUniforms();
        if (this.data.getSsboSet() != null) {
            this.data.getSsboSet().bindingFunction().accept(10);
        }
        if (this.data.getImageSet() != null) {
            this.data.getImageSet().bindingFunction().accept(6);
        }
    }
    @Override
    public void setupAndBindOpaque(Viewport<?> viewport) {
        this.fb.bind();
        this.doBindings();
    }

    @Override
    public void setupAndBindTranslucent(Viewport<?> viewport) {
        this.fbTranslucent.bind();
        this.doBindings();
        if (this.data.getBlender() != null) {
            this.data.getBlender().run();
        }
    }

    @Override
    public void addDebug(List<String> debug) {
        debug.add("Using: " + this.getClass().getSimpleName());
        super.addDebug(debug);
    }

    private static final int UNIFORM_BINDING_POINT = 7;//TODO make ths binding point... not randomly 5

    private StringBuilder buildGenericShaderHeader(AbstractSectionRenderer<?, ?> renderer, String input) {
        StringBuilder builder = new StringBuilder(input).append("\n\n\n");

        if (this.data.getUniforms() != null) {
            builder.append("layout(binding = "+UNIFORM_BINDING_POINT+", std140) uniform ShaderUniformBindings ")
                    .append(this.data.getUniforms().layout())
                    .append(";\n\n");
        }

        if (this.data.getSsboSet() != null) {
            builder.append("#define BUFFER_BINDING_INDEX_BASE 10\n");//TODO: DONT RANDOMLY MAKE THIS 10
            builder.append(this.data.getSsboSet().layout()).append("\n\n");
        }

        if (this.data.getImageSet() != null) {
            builder.append("#define BASE_SAMPLER_BINDING_INDEX 6\n");//TODO: DONT RANDOMLY MAKE THIS 6
            builder.append(this.data.getImageSet().layout()).append("\n\n");
        }

        return builder.append("\n\n");
    }



    @Override
    public String patchOpaqueShader(AbstractSectionRenderer<?, ?> renderer, String input) {
        var builder = this.buildGenericShaderHeader(renderer, input);

        builder.append(this.data.opaqueFragPatch());

        return builder.toString();
    }

    @Override
    public String patchTranslucentShader(AbstractSectionRenderer<?, ?> renderer, String input) {
        if (this.data.translucentFragPatch() == null) return null;

        var builder = this.buildGenericShaderHeader(renderer, input);
        builder.append(this.data.translucentFragPatch());
        return builder.toString();
    }

    @Override
    public boolean hasTAA() {
        return this.data.TAA != null;
    }

    @Override
    public String taaFunction(String functionName) {
        return this.taaFunction(UNIFORM_BINDING_POINT, functionName);
    }

    @Override
    public String taaFunction(int uboBindingPoint, String functionName) {
        if (this.data.TAA == null) {
            return null;
        }

        var builder = new StringBuilder();

        if (this.data.getUniforms() != null) {
            builder.append("layout(binding = "+uboBindingPoint+", std140) uniform ShaderUniformBindings ")
                    .append(this.data.getUniforms().layout())
                    .append(";\n\n");
        }

        builder.append("vec2 ").append(functionName).append("()\n");
        builder.append(this.data.TAA);
        builder.append("\n");
        return builder.toString();
    }

    @Override
    public float[] getRenderScalingFactor() {
        return this.data.resolutionScale;
    }
}
