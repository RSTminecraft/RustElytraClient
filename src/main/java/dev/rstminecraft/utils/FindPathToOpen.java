//package dev.rstminecraft.utils;
//
//import net.minecraft.client.MinecraftClient;
//import net.minecraft.client.network.ClientPlayerEntity;
//import net.minecraft.entity.player.PlayerEntity;
//import net.minecraft.util.hit.BlockHitResult;
//import net.minecraft.util.hit.HitResult;
//import net.minecraft.util.math.BlockPos;
//import net.minecraft.util.math.Box;
//import net.minecraft.util.math.Vec3d;
//import net.minecraft.util.shape.VoxelShape;
//import net.minecraft.world.RaycastContext;
//import net.minecraft.world.World;
//import org.jetbrains.annotations.NotNull;
//import org.jetbrains.annotations.Nullable;
//
//import java.util.ArrayList;
//import java.util.List;
//
//
//public class FindPathToOpen {
//    public static class TakeoffStruct{
//        public TakeoffStruct(float pitch,float yaw,BlockPos end){
//            this.pitch = pitch;
//            this.yaw = yaw;
//            this.end = end;
//        }
//        public float pitch;
//        public float yaw;
//        public BlockPos end;
//    }
//
//
//
//    /**
//     * 返回一个适合在下界起飞鞘翅的方向（偏航角、俯仰角）。
//     * 要求：沿该方向直线飞行至少50米无障碍，且沿途空间开阔（便于后续转弯）。
//     *
//     * @param client Minecraft客户端实例
//     * @return float[2] 数组，索引0为偏航角(yaw)，索引1为俯仰角(pitch)
//     */
//    public static @Nullable TakeoffStruct getTakeoffDirection(@NotNull MinecraftClient client, double MAX_DIST, double SURROUND_DIST, double yr, double yh) {
//        World world = client.world;
//        PlayerEntity player = client.player;
//        if (world == null || player == null) {
//            return null;
//        }
//
//        // 从玩家眼睛位置出发
//        Vec3d start = player.getEntityPos().add(0,yh,0);
//        Vec3d startv = player.getVelocity();
//
//        // 生成26个基本方向（归一化向量）
//        List<Vec3d> baseDirs = generate26Directions(yr);
//
//        // 存储可行方向及其开阔度分数
//        List<DirectionScore> candidates = new ArrayList<>();
//
//        for (Vec3d dir : baseDirs) {
//            // 1. 检查直线50米是否有方块阻挡
//            BlockPos end = hasNoObstacleInLine(world, start,startv, dir, MAX_DIST) ;
//            if (end != null) {
//                // 2. 计算开阔度分数（越大越开阔）
//                double score = computeOpennessScore(world, start, dir, MAX_DIST, SURROUND_DIST, client.player);
//                if (score > 0) {
//                    candidates.add(new DirectionScore(dir, dir.y < 0 ?score * 0.5:score,end));
//                }
//            }
//        }
//
//        // 如果没有可行方向，则返回玩家当前视角（或任何默认方向）
//        if (candidates.isEmpty()) {
//            return null;
//        }
//
//        // 按分数降序排序，取前3个进行细化
//        candidates.sort((a, b) -> Double.compare(b.score, a.score));
//        int topCount = Math.min(3, candidates.size());
//        List<DirectionScore> topCandidates = candidates.subList(0, topCount);
//
//        // 细化搜索：在最佳方向附近采样更多方向
//        DirectionScore best = refineDirections(world, start, startv,topCandidates, MAX_DIST, SURROUND_DIST, client);
//
//        // 将最佳方向向量转换为角度
//        float yaw = getYawFromDirection(best.dir);
//        float pitch = getPitchFromDirection(best.dir);
//        return new TakeoffStruct(pitch,yaw,best.end);
//    }
//
//    /**
//     * 生成26个均匀分布的方向（立方体的26个邻格方向，归一化）
//     */
//    private static @NotNull List<Vec3d> generate26Directions(double yr) {
//        List<Vec3d> list = new ArrayList<>(512);
//        double phi = Math.PI * (3 - Math.sqrt(5));  // 黄金角
//        for (int i = 0; i < 512; i++) {
//            double y = 1 - i / (double)(512 - 1) * yr;  // y 从 1 到 -1
//            double radius = Math.sqrt(1 - y * y);
//            double theta = phi * i;
//
//            double x = Math.cos(theta) * radius;
//            double z = Math.sin(theta) * radius;
//            list.add(new Vec3d(x, y, z));
//        }
//        return list;
//    }
//
//    /**
//     * 检查从起点沿方向直线移动指定距离内是否有方块阻挡
//     */
//    public static @Nullable BlockPos hasNoObstacleInLine(@NotNull World world, Vec3d start, Vec3d startv, @NotNull Vec3d dir, double maxDist) {
//        Vec3d lookVec = dir.normalize();
//
//        // 1. 定义鞘翅飞行时的碰撞箱 (通常高度会缩减为 0.6)
//        double playerWidth = 0.6;
//        double playerHeight = 0.6;
//
//        double distanceTravelled = 0;
//        Vec3d nextPos = null;
//        List<Vec3d> ts = FlightPredictor.predictPath(40,start,startv,lookVec);
//
//        for (int t = 1; t < 40; t++) {
//
//            nextPos = ts.get(t);
//
//            if(!world.isChunkLoaded(BlockPos.ofFloored(nextPos))) return null;
//            // 3. 构建当前位置的玩家碰撞箱 (BoundingBox)
//            // 居中于当前坐标
//            Box playerBox = new Box(
//                    nextPos.x - playerWidth / 2, nextPos.y, nextPos.z - playerWidth / 2,
//                    nextPos.x + playerWidth / 2, nextPos.y + playerHeight, nextPos.z + playerWidth / 2
//            );
//
//            // 4. 高级碰撞检测：检测该 Box 是否与世界中的方块重叠
//            // getBlockCollisions 会返回所有与该 Box 相交的方块形状
//            Iterable<VoxelShape> collisions = world.getBlockCollisions(null, playerBox);
//
//            if (collisions.iterator().hasNext()) {
//                // 如果迭代器不为空，说明发生了碰撞
//                return null;
//            }
//
//            // 5. 更新状态
//            distanceTravelled += ts.get(t-1).distanceTo(nextPos);
//
//            if (distanceTravelled >= maxDist) break;
//        }
//
//        return BlockPos.ofFloored(nextPos);
//    }
//
//    /**
//     * 计算沿某个方向飞行的开阔度分数（分数越高越开阔）
//     */
//    private static double computeOpennessScore(@NotNull World world, @NotNull Vec3d start, @NotNull Vec3d dir,
//                                               double maxDist, double surroundDist, @NotNull ClientPlayerEntity player) {
//        // 在路径上取4个点（10,20,30,40米处）
//        double[][] sampleDistances = {{10,0.15}, {20,0.35}, {30,0.35}, {40,0.15}};
//        double score = 0;
//
//        for (double[] distance : sampleDistances) {
//            double d = distance[0];
//            double weight = distance[1];
//            if (d > maxDist) continue;
//            Vec3d point = start.add(dir.multiply(d));
//
//            // 确保该点所在的方块是空气（理论上应该成立，但防御性检查）
//            BlockPos pos = BlockPos.ofFloored(point);
//            if (!world.getBlockState(pos).isAir()) {
//                return 0; // 路径上有非空气？直接判为不可用
//            }
//
//            // 六个轴向：东、西、上、下、南、北
//            Vec3d[] axes = {
//                    new Vec3d(1, 0, 0), new Vec3d(-1, 0, 0),
//                    new Vec3d(0, 1, 0), new Vec3d(0, -1, 0),
//                    new Vec3d(0, 0, 1), new Vec3d(0, 0, -1)
//            };
//
//            double pointMin = Double.MAX_VALUE;
//            for (Vec3d axis : axes) {
//                Vec3d end = point.add(axis.multiply(surroundDist));
//                RaycastContext ctx = new RaycastContext(
//                        point, end,
//                        RaycastContext.ShapeType.COLLIDER,
//                        RaycastContext.FluidHandling.NONE,
//                        player
//                );
//                BlockHitResult hit = world.raycast(ctx);
//                double dist;
//                if (hit == null || hit.getType() == HitResult.Type.MISS) {
//                    dist = surroundDist; // 全程无阻挡
//                } else {
//                    dist = hit.getPos().distanceTo(point);
//                }
//                pointMin = Math.min(pointMin, dist);
//            }
//
//            if (pointMin <= 1.5) break; // 已经非常狭窄，提前结束
//            score += pointMin * weight;
//        }
//
//        return score; // 返回分数
//    }
//
//    /**
//     * 在最佳方向的邻近区域进行细化搜索，返回最佳方向
//     */
//    private static DirectionScore refineDirections(@NotNull World world, @NotNull Vec3d start,
//                                                   Vec3d startv, @NotNull List<DirectionScore> topCandidates,
//                                                   double maxDist, double surroundDist, @NotNull MinecraftClient client) {
//        final double ANGLE_RANGE = 15.0; // 搜索范围 ±15度
//        final int STEPS = 5;             // 每个维度采样5个点，共25个
//
//        DirectionScore best = null;
//        double bestScore = -1;
//
//        for (DirectionScore cand : topCandidates) {
//            float baseYaw = getYawFromDirection(cand.dir);
//            float basePitch = getPitchFromDirection(cand.dir);
//
//            for (int i = 0; i < STEPS; i++) {
//                for (int j = 0; j < STEPS; j++) {
//                    float yawOffset = (float) (-ANGLE_RANGE + 2 * ANGLE_RANGE * i / (STEPS - 1));
//                    float pitchOffset = (float) (-ANGLE_RANGE + 2 * ANGLE_RANGE * j / (STEPS - 1));
//                    float testYaw = baseYaw + yawOffset;
//                    float testPitch = basePitch + pitchOffset;
//
//                    // 限制俯仰角范围
//                    testPitch = Math.max(-90, Math.min(90, testPitch));
//
//                    Vec3d testDir = getDirectionFromYawPitch(testYaw, testPitch);
//                    BlockPos end = hasNoObstacleInLine(world, start, startv,testDir, maxDist);
//                    if (end != null) {
//                        double score = computeOpennessScore(world, start, testDir, maxDist, surroundDist, client.player);
//                        if (score > bestScore) {
//                            bestScore = score;
//                            best = new DirectionScore(testDir, score,end);
//                        }
//                    }
//                }
//            }
//        }
//
//        // 如果细化未找到更好的，返回原始最佳候选
//        return best != null ? best : topCandidates.getFirst();
//    }
//
//    /**
//     * 从方向向量计算偏航角（度）
//     */
//    private static float getYawFromDirection(@NotNull Vec3d dir) {
//        double yaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
//        return (float) ((yaw + 360) % 360);
//    }
//
//    /**
//     * 从方向向量计算俯仰角（度）
//     */
//    private static float getPitchFromDirection(@NotNull Vec3d dir) {
//        double pitch = Math.toDegrees(-Math.asin(dir.y));
//        return (float) pitch;
//    }
//
//    /**
//     * 从偏航角和俯仰角计算单位方向向量
//     */
//    private static @NotNull Vec3d getDirectionFromYawPitch(float yaw, float pitch) {
//        double yawRad = Math.toRadians(yaw);
//        double pitchRad = Math.toRadians(pitch);
//        double x = -Math.sin(yawRad) * Math.cos(pitchRad);
//        double y = -Math.sin(pitchRad);
//        double z = Math.cos(yawRad) * Math.cos(pitchRad);
//        return new Vec3d(x, y, z);
//    }
//
//    /**
//     * 辅助类：方向向量 + 开阔度分数
//     */
//    private static class DirectionScore {
//        Vec3d dir;
//        double score;
//        BlockPos end;
//
//
//        DirectionScore(Vec3d dir, double score,BlockPos end) {
//            this.dir = dir;
//            this.score = score;
//            this.end = end;
//        }
//    }
//}