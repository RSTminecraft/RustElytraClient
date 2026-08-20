package dev.rstminecraft.utils;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

public class SilentRotation {
    private static float targetYaw, targetPitch;
    private static boolean active = false;

    private static float prevYaw, prevPitch;

    public static void savePrevRotation(float yaw, float pitch) {
        prevYaw = yaw;
        prevPitch = pitch;
    }

    /**
     * 等效于 updateTarget(rotation, false) 且 elytraFreeLook=true
     */
    public static void setRotation(float yaw, float pitch) {
        targetYaw = yaw;
        targetPitch = pitch;
        active = true;
    }

    public static void disable(){
        active = false;
    }

    public static boolean isActive() {return active;}

    public static float getTargetYaw() {return targetYaw;}

    public static float getTargetPitch() {return targetPitch;}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            if (player != null && SilentRotation.isActive()) {
                player.setYaw(SilentRotation.prevYaw);
                player.setPitch(SilentRotation.prevPitch);
            }
        });
        ClientTickEvents.START_CLIENT_TICK.register(client -> active = false);
    }
}
