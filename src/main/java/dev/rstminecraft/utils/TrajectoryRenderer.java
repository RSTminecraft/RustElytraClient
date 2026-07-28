package dev.rstminecraft.utils;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

import net.minecraft.client.MinecraftClient;
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
        WorldRenderEvents.BEFORE_ENTITIES.register(TrajectoryRenderer::renderTrajectory);
        WorldRenderEvents.BEFORE_ENTITIES.register(TrajectoryRenderer::renderPos);
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

        MatrixStack matrices = context.matrices();

        // 获取相机的偏移量，将坐标系对齐到世界坐标
        double camX = MinecraftClient.getInstance().gameRenderer.getCamera().getCameraPos().x;
        double camY = MinecraftClient.getInstance().gameRenderer.getCamera().getCameraPos().y;
        double camZ = MinecraftClient.getInstance().gameRenderer.getCamera().getCameraPos().z;


        for (BlockPos pos : MARKED_POSITIONS) {
            Objects.requireNonNull(matrices).push();
            matrices.translate(pos.getX() - camX, pos.getY() - camY, pos.getZ() - camZ);

            VertexConsumer vertexConsumer = Objects.requireNonNull(context.consumers()).getBuffer(RenderLayers.LINES);

            VertexRendering.drawOutline(Objects.requireNonNull(context.matrices()), vertexConsumer,
                    VoxelShapes.fullCube(), 0.0, 0.0, 0.0, 0xFFFF0000, 1f);
            matrices.pop();
        }
    }

    private static void renderTrajectory(@NotNull WorldRenderContext context) {
        MatrixStack matrices = context.matrices();
        Vec3d cameraPos = MinecraftClient.getInstance().gameRenderer.getCamera().getCameraPos();
        VertexConsumerProvider consumers = context.consumers();

        VertexConsumer lineBuffer = consumers.getBuffer(RenderLayers.LINES);

        Matrix4f matrix = Objects.requireNonNull(matrices).peek().getPositionMatrix();

        for (int i = 0; i < path.size() - 1; i++) {
            Vec3d start = path.get(i);
            Vec3d end = path.get(i + 1);

            // 起点
            lineBuffer.vertex(matrix, (float) (start.x - cameraPos.x), (float) (start.y - cameraPos.y),
                    (float) (start.z - cameraPos.z)).color(0f, 1f, 1f, 1f).normal(1f, 1f, 1f).lineWidth(1);

            // 终点
            lineBuffer.vertex(matrix, (float) (end.x - cameraPos.x), (float) (end.y - cameraPos.y),
                    (float) (end.z - cameraPos.z)).color(0f, 1f, 1f, 1f).normal(1f, 1f, 1f).lineWidth(1);
        }

    }
}
