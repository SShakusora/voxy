package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.BlockAppearance;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

public class VoxelIngestService {
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private final Service service;
    private record IngestSection(int cx, int cy, int cz, WorldEngine world,
                                 PalettedContainer<BlockState> states,
                                 PalettedContainerRO<Holder<Biome>> biomes,
                                 @Nullable BlockAppearance[] materialStates,
                                 boolean onlyAir,
                                 DataLayer blockLight, DataLayer skyLight){}
    private final ConcurrentLinkedDeque<IngestSection> ingestQueue = new ConcurrentLinkedDeque<>();

    public VoxelIngestService(ServiceManager pool) {
        this.service = pool.createServiceNoCleanup(()->this::processJob, 5000, "Ingest service");
    }

    private void processJob() {
        var task = this.ingestQueue.pop();
        task.world.markActive();

        var vs = SECTION_CACHE.get().setPosition(task.cx, task.cy, task.cz);

        if (task.onlyAir && task.blockLight==null && task.skyLight==null) {//If the chunk section has lighting data, propagate it
            WorldUpdater.insertUpdate(task.world, vs.zero());
        } else {
            VoxelizedSection csec = WorldConversionFactory.convert(
                    vs,
                    task.world.getMapper(),
                    task.states,
                    task.biomes,
                    getLightingSupplier(task),
                    task.materialStates
            );
            WorldVoxilizedSectionMipper.mipSection(csec, task.world.getMapper());
            WorldUpdater.insertUpdate(task.world, csec);
        }
    }

    private static IngestSection snapshot(int cx, int cy, int cz, WorldEngine world,
                                          LevelChunkSection section,
                                          @Nullable LevelChunk chunk,
                                          DataLayer blockLight, DataLayer skyLight) {
        var sourceBiomes = section.getBiomes();
        var copiedBiomes = sourceBiomes.recreate();
        // PalettedContainerRO.recreate() intentionally creates a single-value
        // container; copy the 4x4x4 biome cells explicitly instead of losing
        // biome transitions at the section boundary.
        for (int by = 0; by < 4; by++) {
            for (int bz = 0; bz < 4; bz++) {
                for (int bx = 0; bx < 4; bx++) {
                    copiedBiomes.set(bx, by, bz, sourceBiomes.get(bx, by, bz));
                }
            }
        }
        return new IngestSection(cx, cy, cz, world,
                section.getStates().copy(),
                copiedBiomes,
                chunk == null ? null : snapshotMaterialStates(chunk, cy),
                section.hasOnlyAir(), blockLight, skyLight);
    }

    /**
     * Copycat appearance is block-entity state, not part of the section
     * palette. Capture it on the client thread before handing the section to
     * the ingest worker. Reflection keeps the common code independent of
     * Create/Copycats+ and preserves both single and named multi-state parts.
     */
    @Nullable
    private static BlockAppearance[] snapshotMaterialStates(LevelChunk chunk, int sectionY) {
        BlockAppearance[] materials = null;
        for (var entry : chunk.getBlockEntities().entrySet()) {
            BlockPos pos = entry.getKey();
            if ((pos.getY() >> 4) != sectionY) continue;
            BlockAppearance appearance = getCopycatAppearance(entry.getValue());
            if (appearance == null) continue;
            if (materials == null) materials = new BlockAppearance[16 * 16 * 16];
            int x = pos.getX() & 15;
            int y = pos.getY() & 15;
            int z = pos.getZ() & 15;
            materials[x | (z << 4) | (y << 8)] = appearance;
        }
        return materials;
    }

    @Nullable
    private static BlockAppearance getCopycatAppearance(BlockEntity blockEntity) {
        if (!blockEntity.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("copycat")) {
            return null;
        }
        try {
            for (String methodName : new String[]{"getMaterialMap", "getMaterials"}) {
                try {
                    Object rawMaterials = blockEntity.getClass().getMethod(methodName).invoke(blockEntity);
                    BlockAppearance appearance = appearanceFromMaterialMap(rawMaterials, Map.of());
                    if (appearance != null) return appearance;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Try the storage-backed protocol below.
                }
            }
            // Copycats+ multi-state entities expose a MaterialItemStorage whose
            // material map is exactly the ModelData map expected by their model.
            Method storageMethod = blockEntity.getClass().getMethod("getMaterialItemStorage");
            Object storage = storageMethod.invoke(blockEntity);
            if (storage != null) {
                Object rawMaterials = storage.getClass().getMethod("getMaterialMap").invoke(storage);
                if (rawMaterials instanceof Map<?, ?> rawMap && !rawMap.isEmpty()) {
                    Map<String, BlockState> materials = new HashMap<>();
                    Map<String, Boolean> connectedTextures = new HashMap<>();
                    Method itemMethod = storage.getClass().getMethod("getMaterialItem", String.class);
                    for (var materialEntry : rawMap.entrySet()) {
                        if (!(materialEntry.getValue() instanceof BlockState material)) continue;
                        String property = String.valueOf(materialEntry.getKey());
                        materials.put(property, material);
                        Object item = itemMethod.invoke(storage, property);
                        if (item != null) {
                            try {
                                Object enableCT = item.getClass().getMethod("enableCT").invoke(item);
                                if (enableCT instanceof Boolean value) {
                                    connectedTextures.put(property, value);
                                }
                            } catch (ReflectiveOperationException ignored) {
                                // Older Copycats+ builds did not expose CT per part.
                            }
                        }
                    }
                    BlockAppearance appearance = BlockAppearance.of(materials, connectedTextures);
                    if (appearance != null) return appearance;
                }
            }
            Method method = blockEntity.getClass().getMethod("getMaterial");
            Object material = method.invoke(blockEntity);
            return material instanceof BlockState state ? BlockAppearance.single(state) : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            try {
                Method method = blockEntity.getClass().getMethod("getMaterial");
                Object material = method.invoke(blockEntity);
                return material instanceof BlockState state ? BlockAppearance.single(state) : null;
            } catch (ReflectiveOperationException | RuntimeException ignoredAgain) {
                return null;
            }
        }
    }

    private static BlockAppearance appearanceFromMaterialMap(Object rawMaterials, Map<String, Boolean> connectedTextures) {
        if (!(rawMaterials instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) return null;
        Map<String, BlockState> materials = new HashMap<>();
        for (var entry : rawMap.entrySet()) {
            if (entry.getValue() instanceof BlockState state) {
                materials.put(String.valueOf(entry.getKey()), state);
            }
        }
        return BlockAppearance.of(materials, connectedTextures);
    }

    @NotNull
    private static ILightingSupplier getLightingSupplier(IngestSection task) {
        ILightingSupplier supplier = (x,y,z) -> (byte) 0;
        var sla = task.skyLight;
        var bla = task.blockLight;
        boolean sl = sla != null && !sla.isEmpty();
        boolean bl = bla != null && !bla.isEmpty();
        if (sl || bl) {
            if (sl && bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            } else if (bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = 0;
                    return (byte) (sky|(block<<4));
                };
            } else {
                supplier = (x,y,z)-> {
                    int block = 0;
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            }
        }
        return supplier;
    }

    private static boolean shouldIngestSection(LevelChunkSection section, int cx, int cy, int cz) {
        return true;
    }

    public boolean enqueueIngest(WorldEngine engine, LevelChunk chunk) {
        if (!this.service.isLive()) {
            return false;
        }
        if (!engine.isLive()) {
            throw new IllegalStateException("Tried inserting chunk into WorldEngine that was not alive");
        }

        engine.markActive();

        var lightingProvider = chunk.getLevel().getLightEngine();
        boolean gotLighting = false;

        int i = chunk.getMinSection() - 1;
        boolean allEmpty = true;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            allEmpty&=section.hasOnlyAir();
            //if (section.isEmpty()) continue;
            var pos = SectionPos.of(chunk.getPos(), i);
            if (lightingProvider.getDebugSectionType(LightLayer.SKY, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA && lightingProvider.getDebugSectionType(LightLayer.BLOCK, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA)
                continue;
            gotLighting = true;
        }

        if (allEmpty&&!gotLighting) {
            //Special case all empty chunk columns, we need to clear it out
            i = chunk.getMinSection() - 1;
            for (var section : chunk.getSections()) {
                i++;
                if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
                engine.markActive();
                this.ingestQueue.add(snapshot(chunk.getPos().x, i, chunk.getPos().z, engine, section, chunk, null, null));
                try {
                    this.service.execute();
                } catch (Exception e) {
                    Logger.error("Executing had an error: assume shutting down, aborting",e);
                    break;
                }
            }
        }

        if (!gotLighting) {
            return false;
        }

        var blp = lightingProvider.getLayerListener(LightLayer.BLOCK);
        var slp = lightingProvider.getLayerListener(LightLayer.SKY);


        i = chunk.getMinSection() - 1;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            //if (section.isEmpty()) continue;
            var pos = SectionPos.of(chunk.getPos(), i);

            var bl = blp.getDataLayerData(pos);
            if (bl != null) {
                bl = bl.copy();
            }

            var sl = slp.getDataLayerData(pos);
            if (sl != null) {
                sl = sl.copy();
            }

            //If its null for either, assume failure to obtain lighting and ignore section
            //if (blNone && slNone) {
            //    continue;
            //}
            engine.markActive();
            this.ingestQueue.add(snapshot(chunk.getPos().x, i, chunk.getPos().z, engine, section, chunk, bl, sl));
            try {
                this.service.execute();
            } catch (Exception e) {
                Logger.error("Executing had an error: assume shutting down, aborting",e);
                break;
            }
        }
        return true;
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }

    public void shutdown() {
        this.service.shutdown();
    }

    //Utility method to ingest a chunk into the given WorldIdentifier or world
    public static boolean tryIngestChunk(WorldIdentifier worldId, LevelChunk chunk) {
        if (worldId == null) return false;
        var instance = VoxyCommon.getInstance();
        if (instance == null) return false;
        if (!instance.isIngestEnabled(worldId)) return false;
        var engine = instance.getOrCreate(worldId);
        if (engine == null) return false;
        return instance.getIngestService().enqueueIngest(engine, chunk);
    }

    //Try to automatically ingest the chunk into the correct world
    public static boolean tryAutoIngestChunk(LevelChunk chunk) {
        return tryIngestChunk(WorldIdentifier.of(chunk.getLevel()), chunk);
    }

    private boolean rawIngest0(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        this.ingestQueue.add(snapshot(x, y, z, engine, section, null, bl, sl));
        try {
            this.service.execute();
            return true;
        } catch (Exception e) {
            Logger.error("Executing had an error: assume shutting down, aborting",e);
            return false;
        }
    }

    private boolean rawIngest0(WorldEngine engine, LevelChunk chunk, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        this.ingestQueue.add(snapshot(x, y, z, engine, section, chunk, bl, sl));
        try {
            this.service.execute();
            return true;
        } catch (Exception e) {
            Logger.error("Executing had an error: assume shutting down, aborting",e);
            return false;
        }
    }

    public static boolean rawIngest(WorldIdentifier id, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (id == null) return false;
        var engine = id.getOrCreateEngine();
        if (engine == null) return false;
        return rawIngest(engine, section, x, y, z, bl, sl);
    }

    public static boolean rawIngest(WorldIdentifier id, LevelChunk chunk, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (id == null) return false;
        var engine = id.getOrCreateEngine();
        if (engine == null) return false;
        return rawIngest(engine, chunk, section, x, y, z, bl, sl);
    }

    public static boolean rawIngest(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (!shouldIngestSection(section, x, y, z)) return false;
        if (engine.instanceIn == null) return false;
        if (!engine.instanceIn.isIngestEnabled(null)) return false;//TODO: dont pass in null
        return engine.instanceIn.getIngestService().rawIngest0(engine, section, x, y, z, bl, sl);
    }

    public static boolean rawIngest(WorldEngine engine, LevelChunk chunk, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (!shouldIngestSection(section, x, y, z)) return false;
        if (engine.instanceIn == null) return false;
        if (!engine.instanceIn.isIngestEnabled(null)) return false;//TODO: dont pass in null
        return engine.instanceIn.getIngestService().rawIngest0(engine, chunk, section, x, y, z, bl, sl);
    }
}
