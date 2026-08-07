package dev.rstminecraft.elytra;

import baritone.api.BaritoneAPI;
import baritone.api.event.events.BlockChangeEvent;
import baritone.api.event.events.ChunkEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.BetterBlockPos;
import dev.babbaj.pathfinder.PathSegment;
import dev.rstminecraft.RustClientCore.MinecraftContext;
import dev.rstminecraft.RustClientCore.messenger.MsgLevel;
import dev.rstminecraft.RustClientCore.task.TaskManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkManager;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dev.rstminecraft.RustClientCore.task.TaskManager.delay;
import static dev.rstminecraft.RustElytraClient.msg;

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

    public void run() {
        AtomicReference<NetherPathfinderContext> npfRef = new AtomicReference<>(npf);
        BaritoneAPI.getProvider().getPrimaryBaritone().getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onBlockChange(BlockChangeEvent blockChangeEvent) {
                if (npfRef.get() == null)
                    return;
                npf.queueBlockUpdate(blockChangeEvent);
            }

            @Override
            public void onChunkEvent(ChunkEvent event) {
                if (npfRef.get() == null)
                    return;
                if (event.isPostPopulate()) {
                    final WorldChunk chunk = MinecraftContext.world().getChunk(event.getX(), event.getZ());
                    npf.queueForPacking(chunk);
                }
            }
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

        Thread cullingThread = TaskManager.runTask(() -> {
            while (true) {
                MinecraftContext.client().execute(() -> msg.SendMsg("culling!", MsgLevel.info));
                npf.queueCacheCulling(MinecraftContext.player().getChunkPos().x, MinecraftContext.player().getChunkPos().z, 5000, bsu);
                delay(3600);
            }
        }, "culling", true, false);

        try {
            while (true) {
                delay(1);
                if (!doPathManagerTick)
                    continue;


                synchronized (npf.cullingLock) {
                    updatePlayerNear();
                    pathFindAroundObstacles();

                    int last = path.size() - 1;
                    if (!path.complete && MinecraftContext.world().isPosLoaded(path.get(last)))
                        pathNextSegment(last);
                }


            }
        } finally {
            npfRef.set(null);
            cullingThread.interrupt();
            npf.destroy();
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
            PathSegment segment = npf.pathFindAsync(pathStart, destination).get();
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
        final List<BetterBlockPos> path = segment.collect(Collectors.toList());

        // Remove backtracks
        final Map<BetterBlockPos, Integer> positionFirstSeen = new HashMap<>();
        for (int i = 0; i < path.size(); i++) {
            BetterBlockPos pos = path.get(i);
            if (positionFirstSeen.containsKey(pos)) {
                int j = positionFirstSeen.get(pos);
                while (i > j) {
                    path.remove(i);
                    i--;
                }
            } else {
                positionFirstSeen.put(pos, i);
            }
        }

        this.path = new NetherPath(path, complete);
        playerNear = 0;
    }

    public void pathRecalculateSegment(final int upToIncl) {
        final List<BetterBlockPos> after = path.subList(upToIncl + 1, path.size());

        try {
            PathSegment segment = npf.pathFindAsync(MinecraftContext.player().getBlockPos(), path.get(upToIncl)).get();
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
            PathSegment segment = npf.pathFindAsync(MinecraftContext.player().getBlockPos(), destination).get();
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