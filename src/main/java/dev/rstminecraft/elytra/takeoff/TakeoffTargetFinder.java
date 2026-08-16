package dev.rstminecraft.elytra.takeoff;

import dev.rstminecraft.RustClientCore.MinecraftContext;
import dev.rstminecraft.RustClientCore.utils.BetterBlockPos;
import dev.rstminecraft.RustClientCore.utils.Pair;
import dev.rstminecraft.elytra.*;
import dev.rstminecraft.elytra.path.NetherPathfinderContext;
import dev.rstminecraft.elytra.path.PathManager;
import dev.rstminecraft.utils.SilentRotation;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.EmptyBlockView;

import java.util.*;

public class TakeoffTargetFinder {
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};
    private final AngleSolver solver;
    private final NetherPathfinderContext npf;
    private final BlockStateUtils bsu;
    private final PathManager pathManager;

    public TakeoffTargetFinder(AngleSolver solver, NetherPathfinderContext npf, BlockStateUtils bsu, PathManager pathManager) {
        this.solver = solver;
        this.npf = npf;
        this.bsu = bsu;
        this.pathManager = pathManager;
    }

    private static List<BetterBlockPos> reconstructPath(Map<BetterBlockPos, BetterBlockPos> parentMap, BetterBlockPos targetPos) {
        List<BetterBlockPos> path = new ArrayList<>();
        if (targetPos == null)
            return path;

        BetterBlockPos current = targetPos;
        while (current != null) {
            path.add(current); // 向上隐式转型为原版 BlockPos
            current = parentMap.get(current);
        }

        Collections.reverse(path);
        return path;
    }

    private boolean isSafeToWalkOn(BetterBlockPos target, BetterBlockPos from) {
        if (!bsu.getFromOctree(target.x, target.y - 1, target.z))
            return false;

        BlockState floor = MinecraftContext.world().getBlockState(target.down());

        if (floor.isAir() || floor.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN) != VoxelShapes.fullCube() ||
            floor.getBlock() == Blocks.MAGMA_BLOCK)
            return false;

        BlockState body = MinecraftContext.world().getBlockState(target);
        BlockState head = MinecraftContext.world().getBlockState(target.up());
        if (!isPassable(body) || !isPassable(head))
            return false;

        // 如果是向上跳，确保头顶空间
        if (target.y > from.y) {
            BlockState fromHead = MinecraftContext.world().getBlockState(from.up(2));
            return isPassable(fromHead);
        }

        if (target.y < from.y) {
            BlockState targetHead = MinecraftContext.world().getBlockState(target.up(2));
            return isPassable(targetHead);
        }
        return true;
    }

    private boolean isAvailableTarget(BetterBlockPos pos, List<Pair<Vec3d, Integer>> candidates) {
        if (bsu.getFromOctree(pos.x, pos.y + 2, pos.z))
            return false;
        Vec3d start = pos.up().toCenterPos();
        AngleSolution solution = solver.findSolutionInCandidate(start, Vec3d.ZERO, false, new AngleSolver.FireworkBoost(null, 10),
                0, candidates, pathManager.playerNear,
                SilentRotation.isActive() ? SilentRotation.getTargetPitch() : MinecraftContext.player().getPitch(), true);
        return solution != null;
    }

    public List<BetterBlockPos> findPath(BetterBlockPos start) {
        Queue<BetterBlockPos> queue = new LinkedList<>();

        Map<BetterBlockPos, BetterBlockPos> parentMap = new HashMap<>();

        // 包装起点
        queue.add(start);
        parentMap.put(start, null);

        BetterBlockPos targetPos = null;
        int maxNodes = 65535;
        int visited = 0;


        while (!queue.isEmpty() && visited++ < maxNodes) {

            BetterBlockPos current = queue.poll();

            if (isAvailableTarget(current, List.of(new Pair<>(pathManager.path.getVec(pathManager.playerNear), 0)))) {
                targetPos = current;
                break;
            }

            if (!bsu.getFromOctree(current.x, current.y - 1, current.z))
                continue;

            for (int i = 0; i < 4; i++) {
                int nextX = current.x + DX[i];
                int nextZ = current.z + DZ[i];

                if (Math.abs(nextX - start.x) > 25 || Math.abs(nextZ - start.z) > 25) {
                    continue;
                }

                for (int dy = -1; dy <= 1; dy++) {
                    int nextY = current.y + dy;
                    BetterBlockPos nextStep = new BetterBlockPos(nextX, nextY, nextZ);

                    if (parentMap.containsKey(nextStep))
                        continue;

                    // 物理可达性检查
                    if (isSafeToWalkOn(nextStep, current)) {
                        parentMap.put(nextStep, current);
                        queue.add(nextStep);
                    }
                }

                if (!bsu.getFromOctree(nextX, current.y - 1, nextZ) &&
                    !bsu.getFromOctree(nextX, current.y - 2, nextZ) &&
                    !bsu.getFromOctree(nextX, current.y - 3, nextZ)) {
                    for (int k = 2; k < 10; k++) {
                        if (bsu.getFromOctree(nextX, current.y - k - 2, nextZ))
                            break;
                        BetterBlockPos nextStep = new BetterBlockPos(nextX, current.y - k, nextZ);
                        parentMap.put(nextStep, current);
                        queue.add(nextStep);
                    }
                }
            }
        }

        // 回溯路径
        return reconstructPath(parentMap, targetPos);
    }

    public static boolean isPassable(BlockState state) {
        if (state.isAir()) {
            return true;
        }

        if (!state.isReplaceable()) {
            return false;
        }


        return !state.isOf(Blocks.SWEET_BERRY_BUSH) &&   // 甜浆果
               !state.isOf(Blocks.WITHER_ROSE) &&        // 凋零玫瑰
               !state.isOf(Blocks.LAVA) &&               // 岩浆源
               !state.isOf(Blocks.WATER) &&              // 水
               !state.isOf(Blocks.FIRE) &&               // 火
               !state.isOf(Blocks.SOUL_FIRE) &&          // 灵魂火
               !state.isOf(Blocks.POWDER_SNOW);          // 细雪
    }

}
