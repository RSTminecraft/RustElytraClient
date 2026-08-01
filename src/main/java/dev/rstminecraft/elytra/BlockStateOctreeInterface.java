package dev.rstminecraft.elytra;

import dev.rstminecraft.elytra.NetherPathfinderContext;
import dev.babbaj.pathfinder.NetherPathfinder;
import dev.babbaj.pathfinder.Octree;

public final class BlockStateOctreeInterface {

    private final long contextPtr;
    transient long chunkPtr;

    // Guarantee that the first lookup will fetch the context by setting MAX_VALUE
    private int prevChunkX = Integer.MAX_VALUE;
    private int prevChunkZ = Integer.MAX_VALUE;

    public BlockStateOctreeInterface(final NetherPathfinderContext context) {
        this.contextPtr = context.context;
    }

    public boolean get0(final int x, final int y, final int z) {
        if ((y | 127 - y) < 0) {
            return false;
        }
        final int chunkX = x >> 4;
        final int chunkZ = z >> 4;
        if (this.chunkPtr == 0 | (chunkX ^ this.prevChunkX | chunkZ ^ this.prevChunkZ) != 0) {
            this.prevChunkX = chunkX;
            this.prevChunkZ = chunkZ;
            this.chunkPtr = NetherPathfinder.getOrCreateChunk(this.contextPtr, chunkX, chunkZ);
        }
        return Octree.getBlock(this.chunkPtr, x & 0xF, y & 0x7F, z & 0xF);
    }
}
