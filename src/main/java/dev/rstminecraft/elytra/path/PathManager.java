package dev.rstminecraft.elytra.path;

import dev.babbaj.pathfinder.PathSegment;
import dev.rstminecraft.RustClientCore.MinecraftContext;
import dev.rstminecraft.RustClientCore.listener.ChunkListener;
import dev.rstminecraft.RustClientCore.listener.PacketListener;
import dev.rstminecraft.RustClientCore.messenger.MsgLevel;
import dev.rstminecraft.RustClientCore.renderer.BoxRenderer;
import dev.rstminecraft.RustClientCore.renderer.TrajectoryRenderer;
import dev.rstminecraft.RustClientCore.task.TaskManager;
import dev.rstminecraft.RustClientCore.task.TickPhase;
import dev.rstminecraft.RustClientCore.utils.BetterBlockPos;
import dev.rstminecraft.RustClientCore.utils.Pair;
import dev.rstminecraft.elytra.BlockChangeEvent;
import dev.rstminecraft.elytra.BlockStateUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.Colors;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkManager;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static dev.rstminecraft.RustClientCore.task.TaskManager.delay;
import static dev.rstminecraft.RustElytraClient.msg;
import static dev.rstminecraft.RustElytraClient.paused;

public final class PathManager {
    private final @NotNull NetherPathfinderContext npf;
    private final @NotNull BlockStateUtils bsu;
    private final @NotNull BetterBlockPos destination;
    @NotNull
    public NetherPath path;
    public volatile boolean doPathManagerTick = true;
    public int playerNear;

    public PathManager(@NotNull BetterBlockPos destination, @NotNull NetherPathfinderContext npf,
            @NotNull BlockStateUtils bsu) {
        this.npf = npf;
        this.bsu = bsu;
        this.destination = destination;
        path = NetherPath.emptyPath();
        playerNear = 0;
    }

    public void repackChunks() {
        ChunkManager chunkProvider = MinecraftContext.world().getChunkManager();

        BetterBlockPos playerPos = new BetterBlockPos(MinecraftContext.player().getBlockPos());

        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        int minX = playerChunkX - 40;
        int minZ = playerChunkZ - 40;
        int maxX = playerChunkX + 40;
        int maxZ = playerChunkZ + 40;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                WorldChunk chunk = chunkProvider.getWorldChunk(x, z, false);

                if (chunk != null && !chunk.isEmpty()) {
                    npf.queueForPacking(chunk);
                }
            }
        }
    }

    private void flagFluidLava(BlockChangeEvent e) {
        List<Pair<BlockPos, BlockState>> lavaUpdate = new ArrayList<>();
        e.getBlocks().forEach(pair -> {
            // 处理可能会下流的岩浆
            if (pair.second().isOf(Blocks.LAVA) && MinecraftContext.world().getBlockState(pair.first().down()).isAir()) {
                // 如果 Y 轴已经低于下界岩浆湖水面，避免处理岩浆湖
                if (pair.first().getY() > 32) {
                    BlockPos.Mutable mutablePos = pair.first().mutableCopy();
                    while (mutablePos.getY() > 32) {
                        mutablePos.move(Direction.DOWN);
                        // 禁止NetherPathFinder在可能的岩浆流动地寻找路径
                        if (MinecraftContext.world().getBlockState(mutablePos).isAir())
                            lavaUpdate.add(new Pair<>(mutablePos.toImmutable(), Blocks.BARRIER.getDefaultState()));
                        else
                            break;
                    }
                }
            }
        });
        if (!lavaUpdate.isEmpty())
            npf.queueBlockUpdate(new BlockChangeEvent(e.getChunkPos(), lavaUpdate));
    }

    public void run() {
        PacketListener BlockUpdateListener = PacketListener.create(packet -> {
            if (packet instanceof BlockUpdateS2CPacket singleUpdate) {
                Pair<BlockPos, BlockState> updatePos = new Pair<>(singleUpdate.getPos(),
                        singleUpdate.getState());
                BlockChangeEvent event = new BlockChangeEvent(new ChunkPos(updatePos.first()), List.of(updatePos));
                npf.queueBlockUpdate(event);
                flagFluidLava(event);
            } else if (packet instanceof ChunkDeltaUpdateS2CPacket deltaUpdate) {
                List<Pair<BlockPos, BlockState>> list = new ArrayList<>();
                deltaUpdate.visitUpdates((pos, state) -> list.add(new Pair<>(pos, state)));
                if (!list.isEmpty()) {
                    BlockChangeEvent event = new BlockChangeEvent(new ChunkPos(list.getFirst().first()), list);
                    npf.queueBlockUpdate(event);
                    flagFluidLava(event);
                }
            }
        });

        ChunkListener ChunkPackListener = ChunkListener.create((chunk, type) -> {
            if (type != ChunkListener.Type.LOAD)
                return;
            npf.queueForPacking(chunk);
        });


        MinecraftContext.client().execute(this::repackChunks);
        CompletableFuture<PathSegment> pf = npf.pathFindAsync(MinecraftContext.player().getBlockPos(), destination);
        while (!pf.isDone()) {
            delay(1);
        }
        PathSegment ps = pf.join();
        Stream<BetterBlockPos> unpacked = Arrays.stream(ps.packed).mapToObj(BetterBlockPos::deserializeFromLong);
        setPath(unpacked, ps.finished);
        delay(1);

        TrajectoryRenderer trajectory = TrajectoryRenderer.create(path.stream().map(BlockPos::toCenterPos).toList());
        BoxRenderer playerNearBox = BoxRenderer.create(BetterBlockPos.ORIGIN, Colors.RED);

        TaskManager.build(() -> {
            while (true) {
                MinecraftContext.client().execute(() -> msg.SendMsg("culling!", MsgLevel.info));
                npf.queueCacheCulling(MinecraftContext.player().getChunkPos().x, MinecraftContext.player().getChunkPos().z, 5000, bsu);
                delay(3600);
            }
        }).setPhase(TickPhase.POST).daemon().setName("path-culling").start();

        try {
            while (true) {
                delay(1);
                if (!doPathManagerTick || paused)
                    continue;

                updatePlayerNear();
                playerNearBox.update(path.get(playerNear), Colors.RED);
                pathFindAroundObstacles();

                int last = path.size() - 1;
                if (!path.complete && MinecraftContext.world().isPosLoaded(path.get(last)))
                    pathNextSegment(last);

                trajectory.update(path.stream().map(BlockPos::toCenterPos).toList());


            }
        } finally {
            BlockUpdateListener.remove();
            ChunkPackListener.remove();
            trajectory.remove();
            playerNearBox.remove();
        }
    }

    public void updatePlayerNear() {
        if (path.isEmpty()) {
            return;
        }

        int index = playerNear;
        final BetterBlockPos pos = new BetterBlockPos(MinecraftContext.player().getBlockPos());
        for (int i = index; i >= Math.max(index - 1000, 0); i -= 10) {
            if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                index = i; // intentional: this changes the bound of the loop
            }
        }
        for (int i = index; i < Math.min(index + 1000, path.size()); i += 10) {
            if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                index = i; // intentional: this changes the bound of the loop
            }
        }

        for (int i = index; i >= Math.max(index - 50, 0); i--) {
            if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                index = i; // intentional: this changes the bound of the loop
            }
        }
        for (int i = index; i < Math.min(index + 50, path.size()); i++) {
            if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                index = i; // intentional: this changes the bound of the loop
            }
        }

        playerNear = index;
    }

    public void pathNextSegment(final int afterIncl) {
        final List<BetterBlockPos> before = path.subList(0, afterIncl + 1);
        final BetterBlockPos pathStart = path.get(afterIncl);

        try {
            CompletableFuture<PathSegment> segmentFuture = npf.pathFindAsync(pathStart, destination);
            while (!segmentFuture.isDone()) {
                delay(1);
            }
            PathSegment segment = segmentFuture.get();
            Stream<BetterBlockPos> unpacked = Stream.concat(before.stream(),
                    Arrays.stream(segment.packed).mapToObj(BetterBlockPos::deserializeFromLong));
            MinecraftContext.client().execute(() -> setPath(unpacked, segment.finished));
        } catch (InterruptedException e) {
            throw new Error("计算中被中断!");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof PathCalculationException) {
                msg.SendMsg("计算路径出错!", MsgLevel.warning);

            }

        }
    }

    private void pathFindAroundObstacles() {
        int rangeStartIncl = playerNear;
        int rangeEndExcl = playerNear;

        while (rangeEndExcl < path.size() && npf.hasChunk(new ChunkPos(path.get(rangeEndExcl)))) {
            rangeEndExcl++;
        }
        if (rangeStartIncl >= rangeEndExcl) {
            return;
        }

        BetterBlockPos rangeStart = path.get(rangeStartIncl);
        if (bsu.getFromOctree(rangeStart.x, rangeStart.y, rangeStart.z))
            return;

        boolean canSeeAny = false;
        for (int i = rangeStartIncl; i < rangeEndExcl - 1; i++) {

            if (npf.raytrace(MinecraftContext.player().getEntityPos(), path.getVec(i)) ||
                npf.raytrace(MinecraftContext.player().getEyePos(), path.getVec(i))) {
                canSeeAny = true;
            }
            if (!npf.raytrace(path.getVec(i), path.getVec(i + 1))) {
                // obstacle. where do we return to pathing?
                // if the end of render distance is closer to goal, then that's fine, otherwise we'd be "digging our hole deeper" and making an already bad backtrack worse

                if (path.get(rangeEndExcl - 1).distanceSq(destination) <
                    MinecraftContext.player().getEntityPos().squaredDistanceTo(Vec3d.of(destination))) {
                    pathRecalculateSegment(rangeEndExcl - 1); // rejoin after current render distance
                } else {
                    pathRecalculateAll(); // large backtrack detected. ignore render distance, rejoin later on
                }
                return;
            }
        }
        if (!canSeeAny && rangeStartIncl < rangeEndExcl - 2 && MinecraftContext.player().isGliding()) {
            pathRecalculateSegment(rangeEndExcl - 1);
        }
    }

    private void setPath(final Stream<BetterBlockPos> segment, boolean complete) {
        final List<BetterBlockPos> path = segment.toList();

        // Remove backtracks
        Map<BetterBlockPos, Integer> positionLastSeen = new HashMap<>();
        for (int i = 0; i < path.size(); i++) {
            positionLastSeen.put(path.get(i), i);
        }
        List<BetterBlockPos> cleanPath = new ArrayList<>();
        for (int i = 0; i < path.size(); ) {
            BetterBlockPos pos = path.get(i);
            cleanPath.add(pos);
            i = positionLastSeen.get(pos) + 1;
        }

        this.path = new NetherPath(cleanPath, complete);
        playerNear = 0;
    }

    public void pathRecalculateSegment(final int upToIncl) {
        final List<BetterBlockPos> after = path.subList(upToIncl + 1, path.size());

        try {
            CompletableFuture<PathSegment> segmentFuture = npf.pathFindAsync(MinecraftContext.player().getBlockPos(), path.get(upToIncl));
            while (!segmentFuture.isDone()) {
                delay(1);
            }
            PathSegment segment = segmentFuture.get();
            Stream<BetterBlockPos> unpacked = Stream.concat(Arrays.stream(segment.packed).mapToObj(BetterBlockPos::deserializeFromLong),
                    after.stream());
            MinecraftContext.client().execute(() -> setPath(unpacked, path.complete));
        } catch (InterruptedException e) {
            throw new Error("计算中被中断!");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof PathCalculationException) {
                msg.SendMsg("计算路径出错!", MsgLevel.warning);
            }
        }
    }

    public void pathRecalculateAll() {
        try {
            CompletableFuture<PathSegment> segmentFuture = npf.pathFindAsync(MinecraftContext.player().getBlockPos(), destination);
            while (!segmentFuture.isDone()) {
                delay(1);
            }
            PathSegment segment = segmentFuture.get();
            Stream<BetterBlockPos> unpacked = Arrays.stream(segment.packed).mapToObj(BetterBlockPos::deserializeFromLong);
            MinecraftContext.client().execute(() -> setPath(unpacked, segment.finished));
        } catch (InterruptedException e) {
            throw new Error("计算中被中断!");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof PathCalculationException) {
                msg.SendMsg("计算路径出错!", MsgLevel.warning);
            }
        }
    }
}