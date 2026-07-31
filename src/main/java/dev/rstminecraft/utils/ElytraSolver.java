package dev.rstminecraft.utils;

import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.Pair;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatIterator;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public final class ElytraSolver {

    private ElytraSolver() {}

    public interface WorldAccess {
        boolean passable(int x, int y, int z, boolean ignoreLava);
        boolean raytrace(double x1, double y1, double z1, double x2, double y2, double z2);
    }

    public record SolverConfig(double minAvoidance, float pitchRange, int simulationTicks, boolean landingMode, Rotation playerRotations) {
    }

    public record SolverContext(List<BetterBlockPos> path, int playerNear, Vec3d start, Vec3d motion, Box boundingBox, boolean ignoreLava, boolean boosted,
                                int guaranteedBoostTicks, int maximumBoostTicks) {
    }

    public static Boolean solveAngles(SolverContext context, WorldAccess world, SolverConfig config) {
        final List<BetterBlockPos> path = context.path;
        final int playerNear = config.landingMode ? path.size() - 1 : context.playerNear;
        final Vec3d start = context.start;
        Boolean solution = null;

        for (int relaxation = 0; relaxation < 3; relaxation++) {
            int[] heights = context.boosted ? new int[]{20, 10, 5, 0} : new int[]{0};
            int lookahead = relaxation == 0 ? 2 : 3;

            for (int i = Math.min(playerNear + 20, path.size() - 1); i >= playerNear; i--) {
                if (Thread.interrupted()) return null;

                final List<Pair<Vec3d, Integer>> candidates = new ArrayList<>();
                for (int dy : heights) {
                    if (relaxation == 0 || i == playerNear) {
                        candidates.add(new Pair<>(getVec(path, i), dy));
                    } else if (relaxation == 1) {
                        double[] interps = {1.0, 0.75, 0.5, 0.25};
                        for (double interp : interps) {
                            Vec3d dest = interp == 1.0
                                    ? getVec(path, i)
                                    : getVec(path, i).multiply(interp).add(getVec(path, i - 1).multiply(1.0 - interp));
                            candidates.add(new Pair<>(dest, dy));
                        }
                    } else {
                        Vec3d delta = getVec(path, i).subtract(getVec(path, i - 1));
                        int steps = fastFloor(delta.length());
                        Vec3d stepVec = delta.normalize();
                        Vec3d stepped = getVec(path, i);
                        for (int interp = 0; interp < steps; interp++) {
                            candidates.add(new Pair<>(stepped, dy));
                            stepped = stepped.subtract(stepVec);
                        }
                    }
                }

                for (Pair<Vec3d, Integer> candidate : candidates) {
                    Integer augment = candidate.second();
                    Vec3d dest = candidate.first().add(0, augment, 0);
                    if (config.landingMode) {
                        dest = dest.add(0.5, 0.5, 0.5);
                    }

                    if (augment != 0) {
                        if (i + lookahead >= path.size()) continue;
                        if (start.distanceTo(dest) < 40) {
                            if (!clearView(dest, getVec(path, i + lookahead).add(0, augment, 0), false, world)
                                || !clearView(dest, getVec(path, i + lookahead), false, world)) {
                                continue;
                            }
                        } else {
                            if (!clearView(dest, getVec(path, i), false, world)) {
                                continue;
                            }
                        }
                    }

                    Double growth = relaxation == 2 ? null
                            : relaxation == 0 ? 2 * config.minAvoidance : config.minAvoidance;

                    if (isHitboxClear(context, world, dest, growth)) {
                        Pair<Float, Boolean> pitch = solvePitch(context, world, config, dest, relaxation);
                        if (pitch == null) {
                            solution = false;
                            continue;
                        }
                        return true;
                    }
                }
            }
        }
        return solution;
    }

    private static boolean isHitboxClear(SolverContext context, WorldAccess world, Vec3d dest, Double growAmount) {
        final Vec3d start = context.start;
        final boolean ignoreLava = context.ignoreLava;

        if (!clearView(start, dest, ignoreLava, world)) {
            return false;
        }
        if (growAmount == null) {
            return true;
        }

        final Box bb = context.boundingBox.expand(growAmount);
        final double ox = dest.x - start.x;
        final double oy = dest.y - start.y;
        final double oz = dest.z - start.z;

        final double[] src = new double[]{
                bb.minX, bb.minY, bb.minZ, bb.minX, bb.minY, bb.maxZ,
                bb.minX, bb.maxY, bb.minZ, bb.minX, bb.maxY, bb.maxZ,
                bb.maxX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.maxZ,
                bb.maxX, bb.maxY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ,
        };
        final double[] dst = new double[]{
                bb.minX + ox, bb.minY + oy, bb.minZ + oz, bb.minX + ox, bb.minY + oy, bb.maxZ + oz,
                bb.minX + ox, bb.maxY + oy, bb.minZ + oz, bb.minX + ox, bb.maxY + oy, bb.maxZ + oz,
                bb.maxX + ox, bb.minY + oy, bb.minZ + oz, bb.maxX + ox, bb.minY + oy, bb.maxZ + oz,
                bb.maxX + ox, bb.maxY + oy, bb.minZ + oz, bb.maxX + ox, bb.maxY + oy, bb.maxZ + oz,
        };

        for (int i = 0; i < 8; i++) {
            if (!clearView(
                    new Vec3d(src[i * 3], src[i * 3 + 1], src[i * 3 + 2]),
                    new Vec3d(dst[i * 3], dst[i * 3 + 1], dst[i * 3 + 2]),
                    false, world)) {
                return false;
            }
        }
        return true;
    }

    private static Pair<Float, Boolean> solvePitch(SolverContext context, WorldAccess world, SolverConfig config,
                                                   Vec3d goal, int relaxation) {
        final boolean desperate = relaxation == 2;
        final float goodPitch = RotationUtils.calcRotationFromVec3d(context.start, goal, config.playerRotations).getPitch();
        final FloatArrayList pitches = pitchesToSolveFor(goodPitch, config.pitchRange, desperate);

        final List<IntTriple> tests = new ArrayList<>();

        if (context.boosted) {
            int guaranteed = context.guaranteedBoostTicks;
            if (guaranteed == 0) {
                int lookahead = Math.max(4, 10 - context.maximumBoostTicks);
                tests.add(new IntTriple(lookahead, 1, 0));
            } else if (guaranteed <= 5) {
                tests.add(new IntTriple(guaranteed + 5, guaranteed, 0));
            } else {
                tests.add(new IntTriple(guaranteed + 1, guaranteed, 0));
            }
        }

        int ticks = desperate ? 3 : context.boosted ? Math.max(5, context.guaranteedBoostTicks) : config.simulationTicks;
        tests.add(new IntTriple(ticks, context.boosted ? ticks : 0, 0));

        for (IntTriple test : tests) {
            PitchResult result = solvePitchInner(context, world, config, goal, relaxation, pitches.iterator(),
                                                 test.first, test.second, test.third);
            if (result != null) {
                return new Pair<>(result.pitch, false);
            }
        }

        if (desperate) {
            for (int delay = 3; delay >= 1; delay--) {
                PitchResult result = solvePitchInner(context, world, config, goal, relaxation, pitches.iterator(),
                                                     ticks, 10, delay);
                if (result != null) {
                    return new Pair<>(result.pitch, true);
                }
            }
        }

        return null;
    }

    private static PitchResult solvePitchInner(SolverContext context, WorldAccess world, SolverConfig config,
                                               Vec3d goal, int relaxation, FloatIterator pitches,
                                               int ticks, int ticksBoosted, int ticksBoostDelay) {
        final Vec3d goalDelta = goal.subtract(context.start);
        final Vec3d goalDirection = goalDelta.normalize();
        final Deque<PitchResult> bestResults = new ArrayDeque<>();

        while (pitches.hasNext()) {
            final float pitch = pitches.nextFloat();
            final List<Vec3d> displacement = simulate(context, world, config, goalDelta, pitch, ticks, ticksBoosted, ticksBoostDelay);
            if (displacement == null) continue;

            final Vec3d last = displacement.getLast();
            double goodness = goalDirection.dotProduct(last.normalize());
            if (config.landingMode) {
                goodness = -goalDelta.subtract(last).length();
            }

            final PitchResult bestSoFar = bestResults.peek();
            if (bestSoFar == null || goodness > bestSoFar.dot) {
                bestResults.push(new PitchResult(pitch, goodness, displacement));
            }
        }

        outer:
        for (PitchResult result : bestResults) {
            if (relaxation < 2) {
                for (int i = result.steps.size() - 1; i >= 1; i--) {
                    if (!clearView(context.start.add(result.steps.get(i)), goal, context.ignoreLava, world)) {
                        continue outer;
                    }
                }
            } else {
                if (!clearView(context.start.add(result.steps.getLast()), goal, context.ignoreLava, world)) {
                    continue;
                }
            }
            return result;
        }
        return null;
    }

    private static List<Vec3d> simulate(SolverContext context, WorldAccess world, SolverConfig config,
                                       Vec3d goalDelta, float pitch, int ticks, int ticksBoosted, int ticksBoostDelay) {
        Vec3d delta = goalDelta;
        Vec3d motion = context.motion;
        Box hitbox = context.boundingBox;
        List<Vec3d> displacement = new ArrayList<>(ticks + 1);
        displacement.add(Vec3d.ZERO);
        int remainingTicksBoosted = ticksBoosted;

        for (int i = 0; i < ticks; i++) {
            if (delta.lengthSquared() < 1) break;

            final Rotation desired = RotationUtils.calcRotationFromVec3d(Vec3d.ZERO, delta, config.playerRotations).withPitch(pitch);
            final Vec3d lookDirection = RotationUtils.calcLookDirectionFromRotation(desired);

            motion = step(motion, lookDirection, desired.getPitch());
            delta = delta.subtract(motion);

            final Box inMotion = hitbox.stretch(motion.x, motion.y, motion.z).expand(0.01);

            int xmin = fastFloor(inMotion.minX);
            int xmax = fastCeil(inMotion.maxX);
            int ymin = fastFloor(inMotion.minY);
            int ymax = fastCeil(inMotion.maxY);
            int zmin = fastFloor(inMotion.minZ);
            int zmax = fastCeil(inMotion.maxZ);
            for (int x = xmin; x < xmax; x++) {
                for (int y = ymin; y < ymax; y++) {
                    for (int z = zmin; z < zmax; z++) {
                        if (!world.passable(x, y, z, context.ignoreLava)) {
                            return null;
                        }
                    }
                }
            }

            hitbox = hitbox.offset(motion);
            displacement.add(displacement.getLast().add(motion));

            if (i >= ticksBoostDelay && remainingTicksBoosted-- > 0) {
                motion = motion.add(
                        lookDirection.x * 0.1 + (lookDirection.x * 1.5 - motion.x) * 0.5,
                        lookDirection.y * 0.1 + (lookDirection.y * 1.5 - motion.y) * 0.5,
                        lookDirection.z * 0.1 + (lookDirection.z * 1.5 - motion.z) * 0.5
                );
            }
        }

        return displacement;
    }

    private static Vec3d step(Vec3d motion, Vec3d lookDirection, float pitch) {
        double motionX = motion.x;
        double motionY = motion.y;
        double motionZ = motion.z;

        float pitchRadians = pitch * RotationUtils.DEG_TO_RAD_F;
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

    private static boolean clearView(Vec3d start, Vec3d dest, boolean ignoreLava, WorldAccess world) {
        if (start.equals(dest)) return true;
        return world.raytrace(start.x, start.y, start.z, dest.x, dest.y, dest.z);
    }

    private static FloatArrayList pitchesToSolveFor(float goodPitch, float pitchRange, boolean desperate) {
        float minPitch = desperate ? -90 : Math.max(goodPitch - pitchRange, -89);
        float maxPitch = desperate ? 90 : Math.min(goodPitch + pitchRange, 89);

        FloatArrayList pitchValues = new FloatArrayList(fastCeil(maxPitch - minPitch) + 1);
        for (float pitch = goodPitch; pitch <= maxPitch; pitch++) {
            pitchValues.add(pitch);
        }
        for (float pitch = goodPitch - 1; pitch >= minPitch; pitch--) {
            pitchValues.add(pitch);
        }
        return pitchValues;
    }

    private static Vec3d getVec(List<BetterBlockPos> path, int index) {
        BetterBlockPos pos = path.get(index);
        return new Vec3d(pos.x, pos.y, pos.z);
    }

    private static final double FLOOR_DOUBLE_D = 1_073_741_824.0;
    private static final int FLOOR_DOUBLE_I = 1_073_741_824;

    private static int fastFloor(double v) {
        return (int) (v + FLOOR_DOUBLE_D) - FLOOR_DOUBLE_I;
    }

    private static int fastCeil(double v) {
        return FLOOR_DOUBLE_I - (int) (FLOOR_DOUBLE_D - v);
    }

    private record PitchResult(float pitch, double dot, List<Vec3d> steps) {
    }

    private record IntTriple(int first, int second, int third) {
    }
}
