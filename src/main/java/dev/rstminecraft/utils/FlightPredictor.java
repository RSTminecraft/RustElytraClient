//package dev.rstminecraft.utils;
//
//
//import net.minecraft.util.math.Vec3d;
//import org.jetbrains.annotations.NotNull;
//
//import java.util.ArrayList;
//import java.util.List;
//
//public class FlightPredictor {
//    public static @NotNull List<Vec3d> predictPath(int ticks, Vec3d pos, Vec3d vel, @NotNull Vec3d look) {
//        List<Vec3d> points = new ArrayList<>();
//
//        // Simulation Loop (20 GT)
//        for (int i = 0; i < ticks; i++) {
//            points.add(pos);
//
//            // 1. Simulate Firework Impulse (Simplified but precise for the 1st second)
//            // Rockets add velocity every tick, but the initial burst is most significant
//            vel = vel.add(look.x * 0.1 + (look.x * 1.5 - vel.x) * 0.5,
//                    look.y * 0.1 + (look.y * 1.5 - vel.y) * 0.5,
//                    look.z * 0.1 + (look.z * 1.5 - vel.z) * 0.5);
//
//            // 2. Apply Elytra Physics (LivingEntity.travel logic)
//            double horizontalVel = vel.horizontalLength();
//            float f = (float) look.y;
//            float g = f <= -0.5F ? 2.0F : 1.0F; // Pitch influence
//
//            // Gravity & Lift
//            vel = vel.add(0, -0.08 + (double)f * 0.06 * (double)g, 0);
//
//            if (vel.y < 0.0 && horizontalVel > 0.0) {
//                double lift = vel.y * -0.1 * (double)g;
//                vel = vel.add(look.x * lift / horizontalVel, lift, look.z * lift / horizontalVel);
//            }
//
//            // 3. Drag (0.99 for Elytra)
//            vel = vel.multiply(0.99, 0.98, 0.99);
//
//            // 4. Update Position
//            pos = pos.add(vel);
//        }
//        return points;
//    }
//}
