package me.cortex.voxy.common.world;

import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;
import me.cortex.voxy.common.world.other.Mapper;
import org.lwjgl.system.MemoryUtil;

public class SaveLoadSystem3 {
    /**
     * The block mapping now includes block-entity supplied visual materials
     * (for example Create Copycats).  Bump the section format so sections
     * written with the old visual mapping cannot survive a reload and render
     * stale Copycat geometry.  Sections with an older version are discarded by
     * SectionSerializationStorage and will be rebuilt from the live chunks.
     */
    public static final int STORAGE_VERSION = 1;

    private record SerializationCache(Long2ShortOpenHashMap lutMapCache, MemoryBuffer memoryBuffer) {
        public SerializationCache() {
            this(new Long2ShortOpenHashMap(1024), ThreadLocalMemoryBuffer.create(WorldSection.SECTION_VOLUME*2+WorldSection.SECTION_VOLUME*8+1024));
            this.lutMapCache.defaultReturnValue((short) -1);
        }
    }
    public static int lin2z(int i) {//y,z,x
        int x = i&0x1F;
        int y = (i>>10)&0x1F;
        int z = (i>>5)&0x1F;
        return Integer.expand(x,0b1001001001001)|Integer.expand(y,0b10010010010010)|Integer.expand(z,0b100100100100100);

        //zyxzyxzyxzyxzyx
    }

    public static int z2lin(int i) {
        int x = Integer.compress(i, 0b1001001001001);
        int y = Integer.compress(i, 0b10010010010010);
        int z = Integer.compress(i, 0b100100100100100);
        return x|(y<<10)|(z<<5);
    }

    private static final ThreadLocal<SerializationCache> CACHE = ThreadLocal.withInitial(SerializationCache::new);

    //TODO: Cache like long2short and the short and other data to stop allocs
    public static MemoryBuffer serialize(WorldSection section) {
        var cache = CACHE.get();
        var data = section.data;

        Long2ShortOpenHashMap LUT = cache.lutMapCache; LUT.clear();

        MemoryBuffer buffer = cache.memoryBuffer().createUntrackedUnfreeableReference();
        long ptr = buffer.address;

        MemoryUtil.memPutLong(ptr, section.key); ptr += 8;
        long metadataPtr = ptr; ptr += 8;

        long blockPtr = ptr; ptr += WorldSection.SECTION_VOLUME*2;
        long prev = data[0]; MemoryUtil.memPutLong(ptr, prev); ptr+=8; LUT.put(prev, (short) 0);
        short mapping = 0;
        for (long block : data) {
            if (prev != block) {
                prev = block;
                mapping = LUT.putIfAbsent(block, (short) LUT.size());
                if (mapping == -1) {
                    mapping = (short) (LUT.size()-1);
                    MemoryUtil.memPutLong(ptr, block); ptr+=8;
                }
            }
            MemoryUtil.memPutShort(blockPtr, mapping); blockPtr+=2;
        }
        if (LUT.size() >= 1<<16) {
            throw new IllegalStateException();
        }

        long metadata = 0;
        metadata |= Integer.toUnsignedLong(LUT.size());//Bottom 2 bytes
        metadata |= Byte.toUnsignedLong(section.getNonEmptyChildren())<<16;//Next byte
        metadata |= Integer.toUnsignedLong(STORAGE_VERSION & 0xFF)<<24;

        MemoryUtil.memPutLong(metadataPtr, metadata);
        //TODO: do hash

        return buffer.subSize(ptr-buffer.address);//Does not get freed
    }

    public static boolean deserialize(WorldSection section, MemoryBuffer data) {
        long ptr = data.address;
        long key = MemoryUtil.memGetLong(ptr); ptr += 8;

        if (section.key != key) {
            //throw new IllegalStateException("Decompressed section not the same as requested. got: " + key + " expected: " + section.key);
            Logger.error("Decompressed section not the same as requested. got: " + key + " expected: " + section.key);
            return false;
        }

        final long metadata = MemoryUtil.memGetLong(ptr); ptr += 8;
        int storageVersion = (int) ((metadata >>> 24) & 0xFF);
        if (storageVersion != STORAGE_VERSION) {
            Logger.warn("Discarding section with incompatible storage version " + storageVersion
                    + ", expected " + STORAGE_VERSION);
            return false;
        }
        section.nonEmptyChildren = (byte) ((metadata>>>16)&0xFF);
        final long lutBasePtr = ptr + WorldSection.SECTION_VOLUME * 2;

        final var blockData = section.data;
        for (int i = 0; i < WorldSection.SECTION_VOLUME; i++) {
            blockData[i] = MemoryUtil.memGetLong(lutBasePtr + Short.toUnsignedLong(MemoryUtil.memGetShort(ptr)) * 8L);ptr += 2;
        }

        if (section.lvl == 0) {
            int emptyBlockCount = 0;
            for (long block : blockData) {
                emptyBlockCount += Mapper.isAir(block) ? 1 : 0;
            }
            section.nonEmptyBlockCount = WorldSection.SECTION_VOLUME-emptyBlockCount;
        }

        ptr = lutBasePtr + (metadata & 0xFFFF) * 8L;
        return true;
    }
}
