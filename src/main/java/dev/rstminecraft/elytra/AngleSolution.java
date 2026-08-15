package dev.rstminecraft.elytra;

import dev.rstminecraft.utils.Rotation;
import net.minecraft.util.math.Vec3d;

public record AngleSolution(Rotation rotation, Vec3d goingTo, boolean solvedPitch, boolean forceUseFirework) {
}
