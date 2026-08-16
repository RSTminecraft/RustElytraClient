package dev.rstminecraft.elytra;

import dev.rstminecraft.RustClientCore.MinecraftContext;
import dev.rstminecraft.RustClientCore.utils.BetterBlockPos;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.util.math.BlockPos;

import java.util.*;
import java.util.stream.StreamSupport;

public class ElytraLanding {
    static final int LANDING_COLUMN_HEIGHT = 15;
    private static final Set<BetterBlockPos> badLandingSpots = new HashSet<>();

    private ElytraLanding() {}

    private static boolean isInBounds(BlockPos pos) {
        return pos.getY() >= 0 && pos.getY() < 128;
    }

    private static boolean isSafeBlock(Block block) {
        return block == Blocks.NETHERRACK || block == Blocks.GRAVEL;
    }

    private static boolean isSafeBlock(BlockPos pos) {
        return isSafeBlock(MinecraftContext.world().getBlockState(pos).getBlock());
    }

    private static boolean isAtEdge(BlockPos pos) {
        return !isSafeBlock(pos.north())
               || !isSafeBlock(pos.south())
               || !isSafeBlock(pos.east())
               || !isSafeBlock(pos.west())
               // corners
               || !isSafeBlock(pos.north().west())
               || !isSafeBlock(pos.north().east())
               || !isSafeBlock(pos.south().west())
               || !isSafeBlock(pos.south().east());
    }

    private static boolean isColumnAir(BlockPos landingSpot, int minHeight) {
        BlockPos.Mutable mut = new BlockPos.Mutable(landingSpot.getX(), landingSpot.getY(), landingSpot.getZ());
        final int maxY = mut.getY() + minHeight;
        for (int y = mut.getY() + 1; y <= maxY; y++) {
            mut.set(mut.getX(), y, mut.getZ());
            if (!MinecraftContext.world().getBlockState(mut).isAir()) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasAirBubble(BlockPos pos) {
        final int radius = 4; // Half of the full width, rounded down, as we're counting blocks in each direction from the center
        BlockPos.Mutable mut = new BlockPos.Mutable();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    mut.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    if (!MinecraftContext.world().getBlockState(mut).isAir()) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private static BetterBlockPos checkLandingSpot(BlockPos pos, LongOpenHashSet checkedSpots) {
        BlockPos.Mutable mut = new BlockPos.Mutable(pos.getX(), pos.getY(), pos.getZ());
        while (mut.getY() >= 0) {
            if (checkedSpots.contains(mut.asLong())) {
                return null;
            }
            checkedSpots.add(mut.asLong());
            Block block = MinecraftContext.world().getBlockState(mut).getBlock();

            if (isSafeBlock(block)) {
                if (!isAtEdge(mut)) {
                    return new BetterBlockPos(mut);
                }
                return null;
            } else if (block != Blocks.AIR) {
                return null;
            }
            mut.set(mut.getX(), mut.getY() - 1, mut.getZ());
        }
        return null; // void
    }

    public static void markBadLandingPos(BetterBlockPos pos) {
        badLandingSpots.add(pos);
    }

    public static BetterBlockPos findSafeLandingSpot(BetterBlockPos start) {
        Queue<BetterBlockPos> queue = new PriorityQueue<>(Comparator.<BetterBlockPos>comparingInt(pos ->
                (pos.x - start.x) * (pos.x - start.x) + (pos.z - start.z) * (pos.z - start.z)).thenComparingInt(pos -> -pos.y));
        Set<BetterBlockPos> visited = new HashSet<>();
        LongOpenHashSet checkedPositions = new LongOpenHashSet();

        List<BetterBlockPos> monsterBad = StreamSupport.stream(MinecraftContext.world().getEntities().spliterator(), false)
                .filter(entity -> entity instanceof Monster)
                .filter(monster -> !(monster instanceof ZombifiedPiglinEntity))
                .map(monster -> new BetterBlockPos(monster.getBlockPos()))
                .toList();

        queue.add(start);

        OUT:
        while (!queue.isEmpty()) {
            BetterBlockPos pos = queue.poll();
            if (MinecraftContext.world().isPosLoaded(pos) && isInBounds(pos) &&
                MinecraftContext.world().getBlockState(pos).getBlock() == Blocks.AIR) {
                BetterBlockPos actualLandingSpot = checkLandingSpot(pos, checkedPositions);
                if (actualLandingSpot != null &&
                    isColumnAir(actualLandingSpot, LANDING_COLUMN_HEIGHT) &&
                    hasAirBubble(actualLandingSpot.up(LANDING_COLUMN_HEIGHT)) &&
                    !badLandingSpots.contains(actualLandingSpot.up(LANDING_COLUMN_HEIGHT))) {
                    for (BetterBlockPos monsterPos : monsterBad) {
                        if (actualLandingSpot.distanceSq(monsterPos) < 576)
                            continue OUT;
                    }
                    return actualLandingSpot.up(LANDING_COLUMN_HEIGHT);
                }
                if (visited.add(pos.north()))
                    queue.add(pos.north());
                if (visited.add(pos.east()))
                    queue.add(pos.east());
                if (visited.add(pos.south()))
                    queue.add(pos.south());
                if (visited.add(pos.west()))
                    queue.add(pos.west());
                if (visited.add(pos.up()))
                    queue.add(pos.up());
                if (visited.add(pos.down()))
                    queue.add(pos.down());
            }
        }
        return null;
    }
}
