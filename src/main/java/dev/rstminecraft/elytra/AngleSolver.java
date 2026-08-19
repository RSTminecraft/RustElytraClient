package dev.rstminecraft.elytra;

import dev.rstminecraft.RustClientCore.MinecraftContext;
import dev.rstminecraft.RustClientCore.utils.Pair;
import dev.rstminecraft.elytra.path.NetherPath;
import dev.rstminecraft.elytra.path.NetherPathfinderContext;
import dev.rstminecraft.elytra.path.PathManager;
import dev.rstminecraft.utils.Rotation;
import dev.rstminecraft.utils.SilentRotation;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatIterator;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static dev.rstminecraft.RustClientCore.utils.FastMath.fastCeil;
import static dev.rstminecraft.RustClientCore.utils.FastMath.fastFloor;

public class AngleSolver {
    private final static double[] interps = new double[]{1.0, 0.75, 0.5, 0.25};
    private final NetherPathfinderContext npf;
    private final PathManager pathManager;
    private final BlockStateUtils bsu;

    public AngleSolver(@NotNull NetherPathfinderContext npf, PathManager pathManager, BlockStateUtils bsu) {
        this.npf = npf;
        this.pathManager = pathManager;
        this.bsu = bsu;
    }

    /**
     * 鞘翅每刻模拟
     *
     * @param motion        玩家速度
     * @param lookDirection 玩家视线
     * @param pitch         玩家朝向
     * @return 玩家下一刻速度
     */
    public static Vec3d step(final Vec3d motion, final Vec3d lookDirection, final float pitch) {
        double motionX = motion.x;
        double motionY = motion.y;
        double motionZ = motion.z;

        float pitchRadians = pitch * 0.017453292F;
        double pitchBase2 = Math.sqrt(lookDirection.x * lookDirection.x + lookDirection.z * lookDirection.z);
        double flatMotion = Math.sqrt(motionX * motionX + motionZ * motionZ);
        double thisIsAlwaysOne = lookDirection.length();
        float pitchBase3 = MathHelper.cos(pitchRadians);
        pitchBase3 = (float) ((double) pitchBase3 * (double) pitchBase3 * Math.min(1, thisIsAlwaysOne / 0.4));
        motionY += -0.08 + (double) pitchBase3 * 0.06;
        if (motionY < 0 && pitchBase2 > 0) {
            double speedModifier = motionY * -0.1 * (double) pitchBase3;
            motionY += speedModifier;
            motionX += lookDirection.x * speedModifier / pitchBase2;
            motionZ += lookDirection.z * speedModifier / pitchBase2;
        }
        if (pitchRadians < 0) {
            double anotherSpeedModifier = flatMotion * (double) -MathHelper.sin(pitchRadians) * 0.04;
            motionY += anotherSpeedModifier * 3.2;
            motionX -= lookDirection.x * anotherSpeedModifier / pitchBase2;
            motionZ -= lookDirection.z * anotherSpeedModifier / pitchBase2;
        }
        if (pitchBase2 > 0) {
            motionX += (lookDirection.x / pitchBase2 * flatMotion - motionX) * 0.1;
            motionZ += (lookDirection.z / pitchBase2 * flatMotion - motionZ) * 0.1;
        }
        motionX *= 0.99f;
        motionY *= 0.98f;
        motionZ *= 0.99f;

        return new Vec3d(motionX, motionY, motionZ);
    }

    private static FloatArrayList pitchesToSolveFor(final float goodPitch, final boolean desperate) {
        final float minPitch = desperate ? -90 : Math.max(goodPitch - 25, -89);
        final float maxPitch = desperate ? 90 : Math.min(goodPitch + 25, 89);

        final FloatArrayList pitchValues = new FloatArrayList(fastCeil(maxPitch - minPitch) + 1);
        for (float pitch = goodPitch; pitch <= maxPitch; pitch++) {
            pitchValues.add(pitch);
        }
        for (float pitch = goodPitch - 1; pitch >= minPitch; pitch--) {
            pitchValues.add(pitch);
        }

        return pitchValues;
    }


    public List<Pair<Vec3d, Integer>> defaultCandidatePoints(FireworkBoost firework, int relaxation, int targetPathPos) {

        final int[] heights = firework.isBoosted() ? new int[]{20, 10, 5, 0} : new int[]{0};

        NetherPath path = pathManager.path;
        int playerNear = pathManager.playerNear;

        final List<Pair<Vec3d, Integer>> candidates = new ArrayList<>();
        for (int dy : heights) {
            if (relaxation == 0 || targetPathPos == playerNear) {
                candidates.add(new Pair<>(path.getVec(targetPathPos), dy));
            } else if (relaxation == 1) {
                for (double interp : interps) {
                    final Vec3d dest =
                            interp == 1.0 ? path.getVec(targetPathPos) : path.getVec(targetPathPos).multiply(interp)
                                    .add(path.getVec(targetPathPos - 1).multiply(1.0 - interp));
                    candidates.add(new Pair<>(dest, dy));
                }
            } else {
                // Create a point along the segment every block
                final Vec3d delta = path.getVec(targetPathPos).subtract(path.getVec(targetPathPos - 1));
                final int steps = fastFloor(delta.length());
                final Vec3d step = delta.normalize();
                Vec3d stepped = path.getVec(targetPathPos);
                for (int interp = 0; interp < steps; interp++) {
                    candidates.add(new Pair<>(stepped, dy));
                    candidates.add(new Pair<>(stepped.add(3, 0, 0), dy));
                    candidates.add(new Pair<>(stepped.add(-3, 0, 0), dy));
                    candidates.add(new Pair<>(stepped.add(0, 3, 0), dy));
                    candidates.add(new Pair<>(stepped.add(0, -3, 0), dy));
                    candidates.add(new Pair<>(stepped.add(0, 0, 3), dy));
                    candidates.add(new Pair<>(stepped.add(0, 0, -3), dy));
                    stepped = stepped.subtract(step);
                }
            }
        }

        return candidates;
    }

    public AngleSolution findSolutionInCandidate(Vec3d start, Vec3d motion, boolean ignoreLava, FireworkBoost firework, int relaxation,
            List<Pair<Vec3d, Integer>> candidates, int currentPathPoint, float defaultPitch, boolean canUseFirework) {
        NetherPath path = pathManager.path;

        AngleSolution solution = null;

        for (final Pair<Vec3d, Integer> candidate : candidates) {
            final Integer augment = candidate.second();
            Vec3d dest = candidate.first().add(0, augment, 0);

            if (augment != 0) {
                if (currentPathPoint + 2 >= path.size()) {
                    continue;
                }
                if (start.distanceTo(dest) < 40) {
                    if (!npf.raytrace(dest, path.getVec(currentPathPoint + 2).add(0, augment, 0)) ||
                        !npf.raytrace(dest, path.getVec(currentPathPoint + 2))) {
                        // aka: don't go upwards if doing so would prevent us from being able to see the next position **OR** the modified next position
                        continue;
                    }
                } else {
                    // but if it's far away, allow gaining altitude if we could lose it again by the time we get there
                    if (!npf.raytrace(dest, path.getVec(currentPathPoint))) {
                        continue;
                    }
                }
            }

            final Double growth = relaxation == 2 ? null : relaxation == 0 ? 0.4 : 0.2;

            if (isHitBoxClear(start, dest, growth, ignoreLava)) {
                // Yaw is trivial, just calculate the rotation required to face the destination
                final float yaw = Rotation.calcRotationFromVec3d(start, dest).getYaw();

                final Pair<Float, Boolean> pitch = solvePitch(start, dest, motion, relaxation, firework, ignoreLava, canUseFirework);
                if (pitch == null) {
                    solution = new AngleSolution(new Rotation(yaw, defaultPitch), null, false, false);
                    continue;
                }

                // A solution was found with yaw AND pitch, so just immediately return it.
                return new AngleSolution(new Rotation(yaw, pitch.first()), dest, true, pitch.second());
            }
        }

        return solution;
    }

    public AngleSolution solveAngle(Vec3d start, Vec3d motion, boolean ignoreLava, FireworkBoost firework) {


        AngleSolution solution = null;


        NetherPath path = pathManager.path;
        int playerNear = pathManager.playerNear;


        Rotation rotate;
        if (SilentRotation.isActive()) {
            rotate = new Rotation(SilentRotation.getTargetYaw(), SilentRotation.getTargetPitch());
        } else {
            rotate = new Rotation(MinecraftContext.player().getYaw(), MinecraftContext.player().getPitch());
        }


        for (int relaxation = 0; relaxation < 3; relaxation++) {

            for (int i = Math.min(playerNear + (relaxation == 2 ? 5 : 20), path.size() - 1); i >= playerNear; i--) {

                final List<Pair<Vec3d, Integer>> candidates = defaultCandidatePoints(firework, relaxation, i);
                solution = findSolutionInCandidate(start, motion, ignoreLava, firework, relaxation, candidates, i, rotate.getPitch(),
                        relaxation == 2);
                if (solution != null && solution.solvedPitch())
                    return solution;
            }
        }

        return solution;

    }

    private Pair<Float, Boolean> solvePitch(Vec3d start, Vec3d goal, Vec3d motion, int relaxation, FireworkBoost firework, boolean ignoreLava,
            boolean canUseFirework) {
        final boolean desperate = relaxation == 2;
        final float goodPitch = Rotation.calcRotationFromVec3d(start, goal).getPitch();
        final FloatArrayList pitches = pitchesToSolveFor(goodPitch, desperate);

        final IntTriFunction<PitchResult> solve = (ticks, ticksBoosted, ticksBoostDelay) -> solvePitch(start, goal, motion, relaxation,
                pitches.iterator(), ticks, ticksBoosted,
                ticksBoostDelay, ignoreLava);

        final List<IntTriple> tests = new ArrayList<>();

        if (firework.isBoosted()) {
            final int guaranteed = firework.getGuaranteedBoostTicks();
            if (guaranteed == 0) {
                // uncertain when boost will run out
                final int lookahead = Math.max(4, 10 - firework.getMaximumBoostTicks());
                tests.add(new IntTriple(lookahead, 1, 0));
            } else if (guaranteed <= 5) {
                // boost will run out within 5 ticks
                tests.add(new IntTriple(guaranteed + 5, guaranteed, 0));
            } else {
                // there's plenty of guaranteed boost
                tests.add(new IntTriple(guaranteed + 1, guaranteed, 0));
            }
        }

        // Standard test, assume (not) boosted for entire duration
        final int ticks = desperate ? 3 : firework.isBoosted() ? Math.max(5, firework.getGuaranteedBoostTicks()) : 20;
        tests.add(new IntTriple(ticks, firework.isBoosted() ? ticks : 0, 0));

        final Optional<PitchResult> result = tests.stream().map(i -> solve.apply(i.first, i.second, i.third)).filter(Objects::nonNull).findFirst();
        if (result.isPresent()) {
            return new Pair<>(result.get().pitch, false);
        }

        // If we used a firework would we be able to get out of the current situation??? perhaps
        if (canUseFirework) {
            PitchResult resultBoost = solve.apply(ticks, 10, 1);
            if (resultBoost != null) {
                return new Pair<>(resultBoost.pitch, true);
            }
        }

        return null;
    }

    private PitchResult solvePitch(Vec3d start, Vec3d goal, Vec3d motion, final int relaxation, final FloatIterator pitches, final int ticks,
            final int ticksBoosted, final int ticksBoostDelay, boolean ignoreLava) {
        // we are at a certain velocity, but we have a target velocity
        // what pitch would get us closest to our target velocity?
        // yaw is easy so we only care about pitch

        final Vec3d goalDelta = goal.subtract(start);
        final Vec3d goalDirection = goalDelta.normalize();

        final Deque<PitchResult> bestResults = new ArrayDeque<>();

        Box hitbox = new Box(start.x - 0.3, start.y, start.z - 0.3, start.x + 0.3, start.y + 1.8, start.z + 0.3);

        while (pitches.hasNext()) {
            final float pitch = pitches.nextFloat();
            final List<Vec3d> displacement = simulate(hitbox, motion, goalDelta, pitch, ticks, ticksBoosted, ticksBoostDelay, ignoreLava);
            if (displacement == null) {
                continue;
            }
            final Vec3d last = displacement.getLast();
            double goodness = goalDirection.dotProduct(last.normalize());
            final PitchResult bestSoFar = bestResults.peek();

            if (bestSoFar == null || goodness > bestSoFar.dot) {
                bestResults.push(new PitchResult(pitch, goodness, displacement));
            }
        }

        outer:
        for (final PitchResult result : bestResults) {

            if (relaxation < 2) {
                // Ensure that the goal is visible along the entire simulated path
                // Reverse order iteration since the last position is most likely to fail
                for (int i = result.steps.size() - 1; i >= 1; i--) {
                    if (hasObstacle(start.add(result.steps.get(i)), goal, ignoreLava)) {
                        continue outer;
                    }
                }
            } else {
                // Ensure that the goal is visible from the final position
                if (hasObstacle(start.add(result.steps.getLast()), goal, ignoreLava)) {
                    continue;
                }
            }
            return result;
        }
        return null;
    }

    private List<Vec3d> simulate(Box hitbox, Vec3d motion, Vec3d goalDelta, final float pitch, final int ticks, final int ticksBoosted,
            final int ticksBoostDelay,
            boolean ignoreLava) {
        Vec3d delta = goalDelta;
        List<Vec3d> displacement = new ArrayList<>(ticks + 1);
        displacement.add(Vec3d.ZERO);
        int remainingTicksBoosted = ticksBoosted;

        for (int i = 0; i < ticks; i++) {
            if (delta.lengthSquared() < 1) {
                break;
            }
            final float yawToGoal = Rotation.calcRotationFromVec3d(Vec3d.ZERO, delta).getYaw();
            final Vec3d lookDirection = Rotation.calcLookDirectionFromRotation(new Rotation(yawToGoal, pitch));

            motion = step(motion, lookDirection, pitch);
            delta = delta.subtract(motion);


            // Collision box while the player is in motion, with additional padding for safety
            final Box inMotion = hitbox.stretch(motion.x, motion.y, motion.z).expand(0.01);

            int x_min = fastFloor(inMotion.minX);
            int x_max = fastCeil(inMotion.maxX);
            int y_min = fastFloor(inMotion.minY);
            int y_max = fastCeil(inMotion.maxY);
            int z_min = fastFloor(inMotion.minZ);
            int z_max = fastCeil(inMotion.maxZ);
            for (int x = x_min; x < x_max; x++) {
                for (int y = y_min; y < y_max; y++) {
                    for (int z = z_min; z < z_max; z++) {
                        if (!bsu.passable(x, y, z, ignoreLava)) {
                            return null;
                        }
                    }
                }
            }

            hitbox = hitbox.offset(motion);
            displacement.add(displacement.getLast().add(motion));

            if (i >= ticksBoostDelay && remainingTicksBoosted-- > 0) {
                // See EntityFireworkRocket
                motion = motion.add(lookDirection.x * 0.1 + (lookDirection.x * 1.5 - motion.x) * 0.5,
                        lookDirection.y * 0.1 + (lookDirection.y * 1.5 - motion.y) * 0.5,
                        lookDirection.z * 0.1 + (lookDirection.z * 1.5 - motion.z) * 0.5);
            }
        }

        return displacement;
    }

    private boolean isHitBoxClear(Vec3d start, Vec3d dest, Double growAmount, boolean ignoreLava) {
        if (hasObstacle(start, dest, ignoreLava) || hasObstacle(start.add(0, 1.8, 0), dest.add(0, 1.8, 0), ignoreLava))
            return false;
        if (growAmount == null) {
            return true;
        }

        final Box bb = new Box(start.x - 0.3, start.y, start.z - 0.3, start.x + 0.3, start.y + 1.8, start.z + 0.3);

        final double ox = dest.x - start.x;
        final double oy = dest.y - start.y;
        final double oz = dest.z - start.z;

        // @formatter:off
        final double[] src = new double[]{
                bb.minX, bb.minY, bb.minZ,
                bb.minX, bb.minY, bb.maxZ,
                bb.minX, bb.maxY, bb.minZ,
                bb.minX, bb.maxY, bb.maxZ,
                bb.maxX, bb.minY, bb.minZ,
                bb.maxX, bb.minY, bb.maxZ,
                bb.maxX, bb.maxY, bb.minZ,
                bb.maxX, bb.maxY, bb.maxZ,
        };
        final double[] dst = new double[]{
                bb.minX + ox, bb.minY + oy, bb.minZ + oz,
                bb.minX + ox, bb.minY + oy, bb.maxZ + oz,
                bb.minX + ox, bb.maxY + oy, bb.minZ + oz,
                bb.minX + ox, bb.maxY + oy, bb.maxZ + oz,
                bb.maxX + ox, bb.minY + oy, bb.minZ + oz,
                bb.maxX + ox, bb.minY + oy, bb.maxZ + oz,
                bb.maxX + ox, bb.maxY + oy, bb.minZ + oz,
                bb.maxX + ox, bb.maxY + oy, bb.maxZ + oz,
        };
        // @formatter:on

        return npf.raytrace(8, src, dst, NetherPathfinderContext.Visibility.ALL);
    }

    public boolean hasObstacle(Vec3d start, Vec3d dest, boolean ignoreLava) {
        if (!ignoreLava) {
            // if start == dest then the cpp raytracer dies
            return !start.equals(dest) && !npf.raytrace(start, dest);
        } else {
            return MinecraftContext.world().raycast(
                            new RaycastContext(start, dest, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, MinecraftContext.player()))
                           .getType() != HitResult.Type.MISS;
        }
    }

    @FunctionalInterface
    private interface IntTriFunction<T> {
        T apply(int first, int second, int third);
    }

    private record PitchResult(float pitch, double dot, List<Vec3d> steps) {
    }

    private record IntTriple(int first, int second, int third) {
    }

    public static final class FireworkBoost {

        private final Integer fireworkTicksExisted;
        private final int minimumBoostTicks;
        private final int maximumBoostTicks;

        /**
         * @param fireworkTicksExisted The ticksExisted of the attached firework entity, or {@code null} if no entity.
         * @param minimumBoostTicks    The minimum number of boost ticks that the attached firework entity, if any, will
         *                             provide.
         */
        public FireworkBoost(final Integer fireworkTicksExisted, final int minimumBoostTicks) {
            this.fireworkTicksExisted = fireworkTicksExisted;

            // this.lifetime = 10 * i + this.rand.nextInt(6) + this.rand.nextInt(7);
            this.minimumBoostTicks = minimumBoostTicks;
            maximumBoostTicks = minimumBoostTicks + 11;
        }

        public boolean isBoosted() {
            return fireworkTicksExisted != null;
        }

        /**
         * @return The guaranteed number of remaining ticks with boost
         */
        public int getGuaranteedBoostTicks() {
            return isBoosted() ? Math.max(0, minimumBoostTicks - fireworkTicksExisted) : 0;
        }

        /**
         * @return The maximum number of remaining ticks with boost
         */
        public int getMaximumBoostTicks() {
            return isBoosted() ? Math.max(0, maximumBoostTicks - fireworkTicksExisted) : 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || o.getClass() != FireworkBoost.class) {
                return false;
            }

            FireworkBoost other = (FireworkBoost) o;
            if (!isBoosted() && !other.isBoosted()) {
                return true;
            }

            return Objects.equals(fireworkTicksExisted, other.fireworkTicksExisted) && minimumBoostTicks == other.minimumBoostTicks &&
                   maximumBoostTicks == other.maximumBoostTicks;
        }
    }
}
