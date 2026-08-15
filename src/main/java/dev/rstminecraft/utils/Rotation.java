package dev.rstminecraft.utils;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class Rotation {
    private final float yaw;
    private final float pitch;

    public Rotation(float var1, float var2) {
        yaw = var1;
        pitch = var2;
        if (Float.isInfinite(var1) || Float.isNaN(var1) || Float.isInfinite(var2) || Float.isNaN(var2)) {
            throw new IllegalStateException(var1 + " " + var2);
        }
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public String toString() {
        return "Yaw: " + yaw + ", Pitch: " + pitch;
    }

    public static Rotation calcRotationFromVec3d(Vec3d var0, Vec3d var1) {
        double[] var8;
        double var2 = MathHelper.atan2((var8 = new double[]{var0.x - var1.x, var0.y - var1.y, var0.z - var1.z})[0], -var8[2]);
        double var4 = Math.sqrt(var8[0] * var8[0] + var8[2] * var8[2]);
        double var6 = MathHelper.atan2(var8[1], var4);
        return new Rotation((float)(var2 * 57.29577951308232), (float)(var6 * 57.29577951308232));
    }

    public static Vec3d calcLookDirectionFromRotation(Rotation var0) {
        float var1 = MathHelper.cos(-var0.getYaw() * 0.017453292F - 3.1415927F);
        float var2 = MathHelper.sin(-var0.getYaw() * 0.017453292F - 3.1415927F);
        float var3 = -MathHelper.cos(-var0.getPitch() * 0.017453292F);
        float var4 = MathHelper.sin(-var0.getPitch() * 0.017453292F);
        return new Vec3d(var2 * var3, var4, var1 * var3);
    }
}
