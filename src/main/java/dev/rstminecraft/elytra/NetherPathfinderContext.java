package dev.rstminecraft.elytra;

import baritone.api.event.events.BlockChangeEvent;
import dev.babbaj.pathfinder.NetherPathfinder;
import dev.babbaj.pathfinder.Octree;
import dev.babbaj.pathfinder.PathSegment;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.collection.PaletteStorage;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PaletteResizeListener;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;

import java.lang.ref.SoftReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static dev.rstminecraft.RustElytraClient.elytraPredictTerrain;

/**
 * 包装NetherPathFinder的上下文
 * 部分代码来自Baritone
 */
public final class NetherPathfinderContext {
    private static final BlockState AIR_BLOCK_STATE = Blocks.AIR.getDefaultState();

    public final Object cullingLock = new Object();


    final long context;
    public final long seed;
    private final ExecutorService executor;

    public NetherPathfinderContext(long seed) {
        context = NetherPathfinder.newContext(seed);
        this.seed = seed;
        executor = Executors.newSingleThreadExecutor();
    }

    private static void writeChunkData(WorldChunk chunk, long ptr) {
        try {
            ChunkSection[] chunkInternalStorageArray = chunk.getSectionArray();
            for (int y0 = 0; y0 < 8; y0++) {
                final ChunkSection extendedBlockStorage = chunkInternalStorageArray[y0];
                if (extendedBlockStorage == null) {
                    continue;
                }
                final PalettedContainer<BlockState> bsc = extendedBlockStorage.getBlockStateContainer();
                int airId = -1;
                if (bsc.data.palette().hasAny(state -> state.equals(AIR_BLOCK_STATE))) {
                    airId = bsc.data.palette().index(AIR_BLOCK_STATE, PaletteResizeListener.throwing());
                }
                // pasted from FasterWorldScanner
                final PaletteStorage array = bsc.data.storage();
                if (array == null)
                    continue;
                final long[] longArray = array.getData();
                final int arraySize = array.getSize();
                int bitsPerEntry = array.getElementBits();
                long maxEntryValue = (1L << bitsPerEntry) - 1L;

                final int yReal = y0 << 4;
                for (int i = 0, idx = 0; i < longArray.length && idx < arraySize; ++i) {
                    long l = longArray[i];
                    for (int offset = 0; offset <= 64 - bitsPerEntry && idx < arraySize; offset += bitsPerEntry, ++idx) {
                        int value = (int) (l >> offset & maxEntryValue);
                        int x = idx & 15;
                        int y = yReal + (idx >> 8);
                        int z = idx >> 4 & 15;
                        Octree.setBlock(ptr, x, y, z, value != airId);
                    }
                }
            }
            Octree.setIsFromJava(ptr);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public static boolean isSupported() {
        return NetherPathfinder.isThisSystemSupported();
    }

    public boolean hasChunk(ChunkPos pos) {
        return NetherPathfinder.hasChunkFromJava(context, pos.x, pos.z);
    }

    public void queueCacheCulling(int chunkX, int chunkZ, int maxDistanceBlocks, BlockStateUtils boi) {
        executor.execute(() -> {
            synchronized (cullingLock) {
                boi.chunkPtr = 0L;
                NetherPathfinder.cullFarChunks(context, chunkX, chunkZ, maxDistanceBlocks);
            }
        });
    }

    public void queueForPacking(final WorldChunk chunkIn) {
        final SoftReference<WorldChunk> ref = new SoftReference<>(chunkIn);
        executor.execute(() -> {

            final WorldChunk chunk = ref.get();
            if (chunk != null) {
                long ptr = NetherPathfinder.getOrCreateChunk(context, chunk.getPos().x, chunk.getPos().z);
                writeChunkData(chunk, ptr);
            }
        });
    }

    public void queueBlockUpdate(BlockChangeEvent event) {
        executor.execute(() -> {
            ChunkPos chunkPos = event.getChunkPos();
            long ptr = NetherPathfinder.getChunkPointer(context, chunkPos.x, chunkPos.z);
            if (ptr == 0)
                return; // this shouldn't ever happen
            event.getBlocks().forEach(pair -> {
                BlockPos pos = pair.first();
                if (pos.getY() >= 128)
                    return;
                boolean isSolid = pair.second() != AIR_BLOCK_STATE;
                Octree.setBlock(ptr, pos.getX() & 15, pos.getY(), pos.getZ() & 15, isSolid);
            });
        });
    }

    public CompletableFuture<PathSegment> pathFindAsync(final BlockPos src, final BlockPos dst) {
        return CompletableFuture.supplyAsync(() -> {
            final PathSegment segment = NetherPathfinder.pathFind(context, src.getX(), src.getY(), src.getZ(), dst.getX(), dst.getY(),
                                                                  dst.getZ(), true, false, 10000, !elytraPredictTerrain.get());
            if (segment == null) {
                throw new PathCalculationException("Path calculation failed");
            }
            return segment;
        }, executor);
    }

    /**
     * Performs a raytrace from the given start position to the given end position, returning {@code true} if there is
     * visibility between the two points.
     *
     * @param startX The start X coordinate
     * @param startY The start Y coordinate
     * @param startZ The start Z coordinate
     * @param endX   The end X coordinate
     * @param endY   The end Y coordinate
     * @param endZ   The end Z coordinate
     * @return {@code true} if there is visibility between the points
     */
    private boolean raytrace(final double startX, final double startY, final double startZ, final double endX, final double endY, final double endZ) {
        return NetherPathfinder.isVisible(context, NetherPathfinder.CACHE_MISS_SOLID, startX, startY, startZ, endX, endY, endZ);
    }

    /**
     * Performs a raytrace from the given start position to the given end position, returning {@code true} if there is
     * visibility between the two points.
     *
     * @param start The starting point
     * @param end   The ending point
     * @return {@code true} if there is visibility between the points
     */
    public boolean raytrace(final Vec3d start, final Vec3d end) {
        if (start.equals(end))
            return true;
        return NetherPathfinder.isVisible(context, NetherPathfinder.CACHE_MISS_SOLID, start.x, start.y, start.z, end.x, end.y, end.z);
    }

    public boolean raytrace(final int count, final double[] src, final double[] dst, final int visibility) {
        return switch (visibility) {
            case Visibility.ALL -> NetherPathfinder.isVisibleMulti(context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, false) == -1;
            case Visibility.NONE -> NetherPathfinder.isVisibleMulti(context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, true) == -1;
            case Visibility.ANY -> NetherPathfinder.isVisibleMulti(context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, true) != -1;
            default -> throw new IllegalArgumentException("lol");
        };
    }

    public void raytrace(final int count, final double[] src, final double[] dst, final boolean[] hitsOut, final double[] hitPosOut) {
        NetherPathfinder.raytrace(context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, hitsOut, hitPosOut);
    }

    public void cancel() {
        NetherPathfinder.cancel(context);
    }

    public void destroy() {
        cancel();
        // Ignore anything that was queued up, just shutdown the executor
        executor.shutdownNow();

        try {
            while (!executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        NetherPathfinder.freeContext(context);
    }


    public static final class Visibility {

        public static final int ALL = 0;
        public static final int NONE = 1;
        public static final int ANY = 2;

        private Visibility() {
        }
    }
}
