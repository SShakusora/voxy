package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.world.other.BlockAppearance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
//? if forge {
import net.minecraft.client.resources.model.BakedModel;
//?}
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
//? if forge {
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;
//?}
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.lwjgl.opengl.ARBDirectStateAccess.glGetTextureImage;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL12.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

public class SoftwareModelTextureBakery {
    // Note: the first bit of metadata is if alpha discard is enabled
    private static final Matrix4f[] VIEWS = new Matrix4f[6];

    private final ReuseVertexConsumer opaqueVC = new ReuseVertexConsumer();
    private final ReuseVertexConsumer translucentVC = new ReuseVertexConsumer(1/*has discard*/);
    private final SoftwareRasterizer rasterizer = new SoftwareRasterizer(ModelFactory.MODEL_TEXTURE_SIZE);


    public SoftwareModelTextureBakery() {
    }

    public void setupTexture() {
        var texture = Minecraft.getInstance().getTextureManager().getTexture(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"));

        int textureId = texture.getId();

        if (!RenderSystem.isOnRenderThread()) {
            CompletableFuture<Void> future = new CompletableFuture<>();

            RenderSystem.recordRenderCall(() -> {
                try {
                    _doSetupTexture(textureId);
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });

            future.join();
        } else {
            _doSetupTexture(textureId);
        }
    }

    private void _doSetupTexture(int glId) {
        glBindTexture(GL_TEXTURE_2D, glId);
        int width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
        int height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);

        int[] pixels = new int[width * height];
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

        this.rasterizer.setSamplerTexture(pixels, width, height);
    }

    private void bakeBlockModel(BlockState state, @Nullable BlockAppearance appearance, RenderType fallbackLayer) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return;// Dont bake if invisible
        }
        var model = Minecraft.getInstance()
                .getModelManager()
                .getBlockModelShaper()
                .getBlockModel(state);

        // Forge's extended model API is required for models whose geometry is
        // supplied by block-entity ModelData (Create Copycats are one such
        // model).  The old three-argument call deliberately remains the
        // Fabric/vanilla path.
        //? if forge {
        ModelData modelData = createForgeModelData(model, state, appearance);
        SingleThreadedRandomSource random = new SingleThreadedRandomSource(42L);
        List<RenderType> renderTypes = new ArrayList<>();
        // Copycat wrappers compute the union of all part render types from the
        // same ModelData that is later passed to getQuads.  This matters for a
        // Copycats+ block containing, for example, both glass and stone parts.
        try {
            for (RenderType type : model.getRenderTypes(state, random, modelData)) {
                renderTypes.add(type);
            }
        } catch (RuntimeException ignored) {
            // Some older Forge models do not implement the extended query.
        }
        if (renderTypes.isEmpty() && appearance != null) {
            for (BlockState material : appearance.materials().values()) {
                var materialModel = Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(material);
                for (RenderType type : materialModel.getRenderTypes(material, random, ModelData.EMPTY)) {
                    renderTypes.add(type);
                }
            }
        }
        if (renderTypes.isEmpty()) {
            renderTypes.add(fallbackLayer);
        }
        for (RenderType layer : renderTypes) {
            random.setSeed(42L);
            for (Direction direction : new Direction[] { Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
                    Direction.WEST, Direction.EAST, null }) {
                var quads = model.getQuads(state, direction, random, modelData, layer);
                for (var quad : quads) {
                    (layer == RenderType.translucent() ? this.translucentVC : this.opaqueVC)
                            .quad(quad, isLeafMaterial(quad, appearance, state), layer);
                }
            }
        }
        //? } else {
        for (Direction direction : new Direction[] { Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
                Direction.WEST, Direction.EAST, null }) {
            var quads = model.getQuads(state, direction, new SingleThreadedRandomSource(42L));
            for (var quad : quads) {
                (fallbackLayer == RenderType.translucent() ? this.translucentVC : this.opaqueVC)
                        .quad(quad, state.is(BlockTags.LEAVES), fallbackLayer);
            }
        }
        //?}
    }

    // Create exposes its material property from CopycatModel.  Resolve it at
    // runtime so Voxy still loads when Create is absent and does not add a hard
    // common-source dependency on Create.
    //? if forge {
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ModelData createForgeModelData(BakedModel model, BlockState state, @Nullable BlockAppearance appearance) {
        if (appearance == null || appearance.isEmpty()) {
            return ModelData.EMPTY;
        }
        try {
            ModelData.Builder builder = ModelData.builder();
            boolean injected = false;
            Class<?> type = model.getClass();
            while (type != null && type != Object.class) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    String fieldName = field.getName();
                    if (!fieldName.equals("MATERIALS_PROPERTY") && !fieldName.equals("MATERIAL_PROPERTY")) continue;
                    if (!field.canAccess(null) && !field.trySetAccessible()) continue;
                    if (!(field.get(null) instanceof ModelProperty property)) continue;
                    if (fieldName.equals("MATERIALS_PROPERTY")) {
                        builder.with(property, appearance.materials());
                        injected = true;
                    } else if (fieldName.equals("MATERIAL_PROPERTY")) {
                        BlockState material = appearance.primaryMaterial();
                        if (material != null) {
                            builder.with(property, material);
                            injected = true;
                        }
                    }
                }
                type = type.getSuperclass();
            }
            // A wrapper may hide its property class behind a generated model
            // implementation.  These optional lookups keep the dependency
            // soft while covering Create and Copycats+ Forge 1.20.1 builds.
            injected |= injectOptionalProperty(builder, "com.simibubi.create.content.decoration.copycat.CopycatModel", "MATERIAL_PROPERTY", appearance.primaryMaterial());
            injected |= injectOptionalProperty(builder, "com.copycatsplus.copycats.foundation.copycat.model.forge.CopycatModelForge", "MATERIALS_PROPERTY", appearance.materials());
            if (!injected) return ModelData.EMPTY;
            ModelData initial = builder.build();
            // CopycatModel uses getModelData() to add wrapped material data and
            // its occlusion mask.  Keep model-data evaluation deterministic by
            // using a stable one-block view instead of touching the live world.
            SingleBlockModelWorld world = new SingleBlockModelWorld(state);
            ModelData generated = model.getModelData(world, BlockPos.ZERO, state, initial);
            return applyCopycatsConnectedTextureData(model, state, appearance, world, generated);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return ModelData.EMPTY;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean injectOptionalProperty(ModelData.Builder builder, String className, String fieldName, Object value) {
        if (value == null) return false;
        try {
            java.lang.reflect.Field field = Class.forName(className).getField(fieldName);
            if (!(field.get(null) instanceof ModelProperty property)) return false;
            builder.with(property, value);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isLeafMaterial(net.minecraft.client.renderer.block.model.BakedQuad quad,
                                          @Nullable BlockAppearance appearance, BlockState fallback) {
        if (appearance == null) return fallback.is(BlockTags.LEAVES);
        BlockState material = materialForQuad(quad, appearance);
        return material == null ? fallback.is(BlockTags.LEAVES) : material.is(BlockTags.LEAVES);
    }

    @Nullable
    private static BlockState materialForQuad(net.minecraft.client.renderer.block.model.BakedQuad quad,
                                              BlockAppearance appearance) {
        try {
            java.lang.reflect.Field property = quad.getClass().getField("property");
            Object key = property.get(quad);
            if (key != null) {
                BlockState material = appearance.materials().get(String.valueOf(key));
                if (material != null) return material;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Vanilla/Create quads do not carry a named Copycats+ part.
        }
        return appearance.primaryMaterial();
    }

    /**
     * Copycats+ reads the CT flag from its live block entity while building the
     * wrapped ModelData.  The LoD baker intentionally has no live entity, so
     * rebuild the wrapped material data against a deterministic one-block view
     * and apply the snapshotted per-part CT flags through the public model
     * interfaces.  All references stay reflective so Copycats+ remains an
     * optional dependency.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ModelData applyCopycatsConnectedTextureData(BakedModel model, BlockState state,
                                                                BlockAppearance appearance, SingleBlockModelWorld world,
                                                                ModelData generated) {
        if (appearance.connectedTextures().isEmpty()) return generated;
        try {
            Class<?> multiStateClass = Class.forName("com.copycatsplus.copycats.foundation.copycat.multistate.IMultiStateCopycatBlock");
            if (!multiStateClass.isInstance(state.getBlock())) return generated;

            Class<?> modelClass = Class.forName("com.copycatsplus.copycats.foundation.copycat.model.forge.CopycatModelForge");
            java.lang.reflect.Field wrappedField = modelClass.getDeclaredField("WRAPPED_DATA_PROPERTY");
            if (!wrappedField.trySetAccessible()) return generated;
            Object wrappedPropertyValue = wrappedField.get(null);
            if (!(wrappedPropertyValue instanceof ModelProperty wrappedProperty)) return generated;

            Class<?> scaledClass = Class.forName("com.copycatsplus.copycats.foundation.copycat.model.forge.ScaledBlockAndTintGetterForge");
            Class<?> filteredClass = Class.forName("com.copycatsplus.copycats.foundation.copycat.model.forge.FilteredBlockAndTintGetterForge");
            var scaledConstructor = scaledClass.getConstructor(String.class, BlockAndTintGetter.class, BlockPos.class,
                    net.minecraft.core.Vec3i.class, net.minecraft.core.Vec3i.class, java.util.function.Predicate.class);
            var filteredConstructor = filteredClass.getConstructor(BlockAndTintGetter.class, java.util.function.Predicate.class);
            var vectorMethod = multiStateClass.getMethod("getVectorFromProperty", BlockState.class, String.class);
            var scaleMethod = multiStateClass.getMethod("vectorScale", BlockState.class);
            var connectMethod = multiStateClass.getMethod("canConnectTexturesToward", String.class,
                    BlockAndTintGetter.class, BlockPos.class, BlockPos.class, BlockState.class);

            Map<String, ModelData> wrappedData = new java.util.HashMap<>();
            for (var entry : appearance.materials().entrySet()) {
                String property = entry.getKey();
                Object inner = vectorMethod.invoke(state.getBlock(), state, property);
                Object scale = scaleMethod.invoke(state.getBlock(), state);
                Object scaledWorld = scaledConstructor.newInstance(property, world, BlockPos.ZERO, inner, scale,
                        (java.util.function.Predicate<BlockPos>) target -> true);
                java.util.function.Predicate<BlockPos> filter = target -> {
                    if (!appearance.enableConnectedTextures(property)) return false;
                    try {
                        return Boolean.TRUE.equals(connectMethod.invoke(state.getBlock(), property, scaledWorld,
                                BlockPos.ZERO, target, state));
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        return true;
                    }
                };
                Object filteredWorld = filteredConstructor.newInstance(scaledWorld, filter);
                BakedModel materialModel = Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(entry.getValue());
                ModelData materialData = materialModel.getModelData((BlockAndTintGetter) filteredWorld,
                        BlockPos.ZERO, entry.getValue(), ModelData.EMPTY);
                wrappedData.put(property, materialData);
            }
            return generated.derive().with(wrappedProperty, wrappedData).build();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return generated;
        }
    }

    private static final class SingleBlockModelWorld implements BlockAndTintGetter {
        private final BlockState state;

        private SingleBlockModelWorld(BlockState state) {
            this.state = state;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return null;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            return -1;
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return pos.equals(BlockPos.ZERO) ? this.state : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return this.getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 1;
        }

        @Override
        public int getMinBuildHeight() {
            return 0;
        }

        @Override
        public float getShade(Direction direction, boolean shaded) {
            return 0;
        }
    }
    //?}

    private void bakeFluidState(BlockState state, int face, RenderType layer) {
        BlockAndTintGetter getter = new BlockAndTintGetter() {
            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }
            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                //This is such a stupid and bad hack, we can inject tinting state here since this is called
                // before the quad is added
                //TODO: need to make a quad once tinting thing
                translucentVC.setDefaultMeta(translucentVC.getDefaultMeta()|4);//Tinting
                opaqueVC.setDefaultMeta(opaqueVC.getDefaultMeta()|4);//Tinting
                return -1;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState();
                }

                //Fixme:
                // This makes it so that the top face of water is always air, if this is commented out
                //  the up block will be a liquid state which makes the sides full
                // if this is uncommented, that issue is fixed but e.g. stacking water layers ontop of eachother
                //  doesnt fill the side of the block

                //if (pos.getY() == 1) {
                //    return Blocks.AIR.getDefaultState();
                //}
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState().getFluidState();
                }

                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinBuildHeight() {
                return 0;
            }

            @Override
            public float getShade(Direction direction, boolean bl) {
                return 0;
            }
        
        };
        
        VertexConsumer vc = this.opaqueVC;;

        if (layer == RenderType.translucent()) vc = this.translucentVC;
        if (layer == RenderType.cutout()) {
            this.opaqueVC.setDefaultMeta(this.opaqueVC.getDefaultMeta()|1);//set discard
        } else {
            this.opaqueVC.setDefaultMeta(this.opaqueVC.getDefaultMeta()&~1);//remove discard
        }
        Minecraft.getInstance().getBlockRenderer().renderLiquid(BlockPos.ZERO, getter, vc, state, state.getFluidState());
        this.translucentVC.setDefaultMeta(0);//Reset default meta
        this.opaqueVC.setDefaultMeta(0);//Reset default meta
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
        var fv = Direction.from3DDataValue(face).getNormal();
        int dot = fv.getX() * pos.getX() + fv.getY() * pos.getY() + fv.getZ() * pos.getZ();
        return dot >= 1;
    }

    public void free() {
        this.opaqueVC.free();
        this.translucentVC.free();
    }

    private static final long SINGLE_FACE_OUTPUT_SIZE = (ModelFactory.MODEL_TEXTURE_SIZE
            * ModelFactory.MODEL_TEXTURE_SIZE) * 8;
    // The outputBuffer layout is different from the non software rasterized
    // ModelTextureBakery
    // in this version the values are simply appended
    // (0,0),(1,0),(2,0),(0,1),(1,1),(2,1)

    public int renderToOutput(BlockState state, long outputBuffer) {
        return this.renderToOutput(state, null, outputBuffer);
    }

    public int renderToOutput(BlockState state, @Nullable BlockAppearance appearance, long outputBuffer) {
        MemoryUtil.memSet(outputBuffer, 0, 16 * 16 * 8 * 6);

        boolean isBlock = true;
        if (state.getBlock() instanceof LiquidBlock) {
            isBlock = false;
        }

        RenderType blockRenderLayer = null;
        BlockState renderState = appearance == null || appearance.primaryMaterial() == null
                ? state : appearance.primaryMaterial();
        if (state.getBlock() instanceof LiquidBlock) {
            blockRenderLayer = ItemBlockRenderTypes.getRenderLayer(state.getFluidState());
        } else {
            if (renderState.getBlock() instanceof LeavesBlock) {
                blockRenderLayer = RenderType.solid();
            } else {
                blockRenderLayer = ItemBlockRenderTypes.getChunkRenderType(renderState);
            }
        }

        // TODO: support block model entities
        // BakedBlockEntityModel bbem = null;
        if (state.hasBlockEntity()) {
            // bbem = BakedBlockEntityModel.bake(state);
        }

        boolean isAnyShaded = false;
        boolean isAnyDarkend = false;
        boolean anyTranslucent = false;
        boolean anyDiscard = false;
        if (isBlock) {
            this.opaqueVC.reset();
            this.translucentVC.reset();
            this.bakeBlockModel(state, appearance, blockRenderLayer);
            isAnyShaded |= this.opaqueVC.anyShaded | this.translucentVC.anyShaded;
            isAnyDarkend |= this.opaqueVC.anyDarkendTex | this.translucentVC.anyDarkendTex;
            anyTranslucent |= !this.translucentVC.isEmpty();
            anyDiscard |= this.opaqueVC.anyDiscard;
            if (!(this.opaqueVC.isEmpty() && this.translucentVC.isEmpty())) {// only render if there... is shit to
                                                                             // render
                for (int i = 0; i < VIEWS.length; i++) {
                    this.rasterizer.setFaceCull(i == 1 || i == 2 || i == 4);
                    this.rasterizer.clear();
                    this.rasterizer.setBlending(false);
                    this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                    this.rasterizer.setBlending(true);
                    this.rasterizer.raster(VIEWS[i], this.translucentVC);
                    UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(),
                            outputBuffer + (SINGLE_FACE_OUTPUT_SIZE * i));
                }
            }
        } else {// Is fluid, slow path :(

            if (!(state.getBlock() instanceof LiquidBlock))
                throw new IllegalStateException();
            for (int i = 0; i < VIEWS.length; i++) {
                this.opaqueVC.reset();
                this.translucentVC.reset();
                this.bakeFluidState(state, i, blockRenderLayer);
                if (this.opaqueVC.isEmpty() && this.translucentVC.isEmpty())
                    continue;
                isAnyShaded |= this.opaqueVC.anyShaded | this.translucentVC.anyShaded;
                isAnyDarkend |= this.opaqueVC.anyDarkendTex | this.translucentVC.anyDarkendTex;
                anyTranslucent |= !this.translucentVC.isEmpty();
                anyDiscard |= this.opaqueVC.anyDiscard;

                this.rasterizer.setFaceCull(i == 1 || i == 2 || i == 4);

                // The projection matrix
                this.rasterizer.clear();
                this.rasterizer.setBlending(false);
                this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                this.rasterizer.setBlending(true);
                this.rasterizer.raster(VIEWS[i], this.translucentVC);
                UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer + (SINGLE_FACE_OUTPUT_SIZE * i));
            }
        }

        return (isAnyShaded ? 1 : 0) | (isAnyDarkend ? 2 : 0) | (anyTranslucent ? 4 : 0) | (anyDiscard ? 8 : 0);
    }

    static {
        // the face/direction is the face (e.g. down is the down face)
        addView(0, -90, 0, 0, 0);// Direction.DOWN
        addView(1, 90, 0, 0, 0b100);// Direction.UP

        addView(2, 0, 180, 0, 0b001);// Direction.NORTH
        addView(3, 0, 0, 0, 0);// Direction.SOUTH

        addView(4, 0, 90, 270, 0b100);// Direction.WEST
        addView(5, 0, 270, 270, 0);// Direction.EAST
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        var stack = new PoseStack();
        stack.translate(0.5f, 0.5f, 0.5f);
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 0, 1), rotation));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(1, 0, 0), pitch));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 1, 0), yaw));
        stack./*? if 1.20.1 { */mulPoseMatrix/*? } else { */mulPose/*? } */(
            new Matrix4f().scale(
                1 - 2 * (flip & 1),
                1 - (flip & 2),
                1 - ((flip >> 1) & 2)
            )
        );
        stack.translate(-0.5f, -0.5f, -0.5f);
        var mat = new Matrix4f(stack.last().pose());

        mat = new Matrix4f().set(
                2, 0, 0, 0,
                0, 2, 0, 0,
                0, 0, -2, 0,
                -1, -1, 1, 1)
                .mul(mat);
        VIEWS[i] = mat;
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1 / Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                Math.cos(hangle));
    }
}
