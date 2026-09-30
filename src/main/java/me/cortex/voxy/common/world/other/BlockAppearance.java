package me.cortex.voxy.common.world.other;

import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable block-entity supplied rendering state.
 *
 * Copycat implementations are not required to use the same ModelProperty, and
 * multi-state Copycats store one material per named part.  Keeping this value
 * separate from the vanilla BlockState lets the world importer, the live
 * ingest path, and the model baker share the exact same appearance key.
 */
public final class BlockAppearance {
    private final Map<String, BlockState> materials;
    private final Map<String, Boolean> connectedTextures;

    private BlockAppearance(Map<String, BlockState> materials, Map<String, Boolean> connectedTextures) {
        var orderedMaterials = new TreeMap<String, BlockState>();
        orderedMaterials.putAll(materials);
        var orderedCt = new TreeMap<String, Boolean>();
        orderedCt.putAll(connectedTextures);
        this.materials = Collections.unmodifiableMap(new LinkedHashMap<>(orderedMaterials));
        this.connectedTextures = Collections.unmodifiableMap(new LinkedHashMap<>(orderedCt));
    }

    public static BlockAppearance single(BlockState material) {
        return of(Map.of("material", material), Map.of());
    }

    @Nullable
    public static BlockAppearance of(Map<String, BlockState> materials) {
        return of(materials, Map.of());
    }

    @Nullable
    public static BlockAppearance of(Map<String, BlockState> materials, Map<String, Boolean> connectedTextures) {
        if (materials.isEmpty()) {
            return null;
        }
        return new BlockAppearance(materials, connectedTextures);
    }

    public Map<String, BlockState> materials() {
        return this.materials;
    }

    public Map<String, Boolean> connectedTextures() {
        return this.connectedTextures;
    }

    public boolean isEmpty() {
        return this.materials.isEmpty();
    }

    public boolean enableConnectedTextures(String property) {
        return this.connectedTextures.getOrDefault(property, true);
    }

    /**
     * Returns the default material used for legacy tint/layer decisions.  The
     * actual Copycats+ model receives the complete map during model-data
     * construction.
     */
    @Nullable
    public BlockState primaryMaterial() {
        BlockState material = this.materials.get("material");
        if (material != null) {
            return material;
        }
        return this.materials.values().stream().findFirst().orElse(null);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof BlockAppearance other)) return false;
        return this.materials.equals(other.materials) && this.connectedTextures.equals(other.connectedTextures);
    }

    @Override
    public int hashCode() {
        return 31 * this.materials.hashCode() + this.connectedTextures.hashCode();
    }

    @Override
    public String toString() {
        return "BlockAppearance" + this.materials;
    }
}
