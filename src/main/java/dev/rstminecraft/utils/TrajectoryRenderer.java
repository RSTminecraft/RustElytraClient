package dev.rstminecraft.utils;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TrajectoryRenderer {
    private static final List<BlockPos> MARKED_POSITIONS = new ArrayList<>();
    public static List<Vec3d> path = new ArrayList<>();

    public static void init() {
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(TrajectoryRenderer::renderTrajectory);
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(TrajectoryRenderer::renderPos);
    }

    public static void markPos(BlockPos pos) {
        if (!MARKED_POSITIONS.contains(pos)) {
            MARKED_POSITIONS.add(pos);
        }
    }

    public static void drawTrajectory(List<Vec3d> path2) {
        path = path2;
    }

    public static void clear() {
        MARKED_POSITIONS.clear();
        path.clear();
    }

    private static void renderPos(@NotNull WorldRenderContext context) {
        if (MARKED_POSITIONS.isEmpty()) return;

        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null) return;

        // 获取相机的偏移量，将坐标系对齐到世界坐标
        double camX = context.camera().getCameraPos().x;
        double camY = context.camera().getCameraPos().y;
        double camZ = context.camera().getCameraPos().z;


        for (BlockPos pos : MARKED_POSITIONS) {
            Objects.requireNonNull(matrices).push();
            matrices.translate(pos.getX() - camX, pos.getY() - camY, pos.getZ() - camZ);

            VertexConsumer vertexConsumer = Objects.requireNonNull(context.consumers()).getBuffer(RenderLayers.LINES);

            VertexRendering.drawOutline(Objects.requireNonNull(context.matrixStack()), vertexConsumer,
                    VoxelShapes.fullCube(), 0.0, 0.0, 0.0, 0xFFFF0000, 1f);
            matrices.pop();
        }
    }

    private static void renderTrajectory(@NotNull WorldRenderContext context) {
        MatrixStack matrices = context.matrixStack();
        Vec3d cameraPos = context.camera().getCameraPos();
        VertexConsumerProvider consumers = context.consumers();

        VertexConsumer lineBuffer = Objects.requireNonNull(consumers).getBuffer(RenderLayers.LINES);

        Matrix4f matrix = Objects.requireNonNull(matrices).peek().getPositionMatrix();

        for (int i = 0; i < path.size() - 1; i++) {
            Vec3d start = path.get(i);
            Vec3d end = path.get(i + 1);

            // 起点
            lineBuffer.vertex(matrix, (float) (start.x - cameraPos.x), (float) (start.y - cameraPos.y),
                    (float) (start.z - cameraPos.z)).color(0f, 1f, 1f, 1f).normal(1f, 1f, 1f);

            // 终点
            lineBuffer.vertex(matrix, (float) (end.x - cameraPos.x), (float) (end.y - cameraPos.y),
                    (float) (end.z - cameraPos.z)).color(0f, 1f, 1f, 1f).normal(1f, 1f, 1f);
        }

    }
}
