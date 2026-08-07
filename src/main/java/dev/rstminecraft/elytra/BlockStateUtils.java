package dev.rstminecraft.elytra;

import dev.babbaj.pathfinder.NetherPathfinder;
import dev.babbaj.pathfinder.Octree;
import net.minecraft.block.AirBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

public final class BlockStateUtils {

    private static final BlockState AIR = Blocks.AIR.getDefaultState();
    private final long contextPtr;
    private final ClientChunkManager provider;
    private final World world;
    transient long chunkPtr;
    private int prevChunkX = Integer.MAX_VALUE;
    private int prevChunkZ = Integer.MAX_VALUE;
    private WorldChunk prev = null;

    public BlockStateUtils(final NetherPathfinderContext context, final World world) {
        contextPtr = context.context;
        this.world = world;
        provider = (ClientChunkManager) world.getChunkManager();
    }

    public boolean getFromOctree(final int x, final int y, final int z) {
        if ((y | 127 - y) < 0) {
            return false;
        }
        final int chunkX = x >> 4;
        final int chunkZ = z >> 4;
        if (chunkPtr == 0 || (chunkX ^ prevChunkX | chunkZ ^ prevChunkZ) != 0) {
            prevChunkX = chunkX;
            prevChunkZ = chunkZ;
            chunkPtr = NetherPathfinder.getOrCreateChunk(contextPtr, chunkX, chunkZ);
        }
        return Octree.getBlock(chunkPtr, x & 0xF, y & 0x7F, z & 0xF);
    }

    public BlockState getFromClient(int x, int y, int z) {
        y -= world.getDimension().minY();
        if (y < 0 || y >= world.getDimension().height()) {
            return AIR;
        }

        WorldChunk cached = prev;

        if (cached != null && cached.getPos().x == x >> 4 && cached.getPos().z == z >> 4) {
            return getFromChunk(cached, x, y, z);
        }
        WorldChunk chunk = provider.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (chunk != null && !chunk.isEmpty()) {
            prev = chunk;
            return getFromChunk(chunk, x, y, z);
        }

        return AIR;
    }

    public static BlockState getFromChunk(WorldChunk chunk, int x, int y, int z) {
        ChunkSection section = chunk.getSectionArray()[y >> 4];
        if (section.isEmpty()) {
            return AIR;
        }
        return section.getBlockState(x & 15, y & 15, z & 15);
    }

    public boolean passable(int x, int y, int z, boolean ignoreLava) {
        if (ignoreLava) {
            final BlockState state = getFromClient(x, y, z);
            return state.getBlock() instanceof AirBlock || isLava(state);
        } else {
            return !getFromOctree(x, y, z);
        }
    }

    static boolean isLava(BlockState state) {
        Fluid f = state.getFluidState().getFluid();
        return f == Fluids.LAVA || f == Fluids.FLOWING_LAVA;
    }
}
