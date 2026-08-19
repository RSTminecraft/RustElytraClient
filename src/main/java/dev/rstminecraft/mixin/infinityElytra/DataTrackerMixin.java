package dev.rstminecraft.mixin.infinityElytra;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.rstminecraft.elytra.infinityElytra;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.data.DataTracked;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedDataHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(DataTracker.class)
public abstract class DataTrackerMixin {

    @Final
    @Shadow
    private DataTracked trackedEntity;

    /**
     * 在 writeUpdatedEntries 的 HEAD 注入，逐条目拦截。
     * 完全等价于原项目的 DataTrackerEvents 事件分发机制。
     * FLAGS (ID=0): transactionPending 时保持 bit7=1
     * POSE  (ID=6): transactionPending 或 poseLockTicks>0 时保持 FALL_FLYING
     */
    @Inject(method = "writeUpdatedEntries", at = @At("HEAD"))
    private void onWriteUpdatedEntries(
            List<DataTracker.SerializedEntry<?>> entries,
            CallbackInfo ci,
            @Local(argsOnly = true) LocalRef<List<DataTracker.SerializedEntry<?>>> entryRef
    ) {
        if (!(trackedEntity instanceof Entity entity)) return;
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null || entity != player) return;

        boolean interceptFlags = infinityElytra.transactionPending;
        boolean interceptPose = infinityElytra.transactionPending
                                || infinityElytra.poseLockTicks > 0;

        if (!interceptFlags && !interceptPose) return;

        final int FLAGS_ID = 0;
        final int POSE_ID = 6;
        final int GLIDING_FLAG_MASK = 1 << 7;

        boolean flagsModified = false;
        List<DataTracker.SerializedEntry<?>> newList = new ArrayList<>();

        for (DataTracker.SerializedEntry<?> entry : entries) {
            if (interceptFlags && entry.id() == FLAGS_ID) {
                byte flags = (byte) entry.value();
                if ((flags & GLIDING_FLAG_MASK) == 0) {
                    flags |= (byte) GLIDING_FLAG_MASK;
                    @SuppressWarnings("unchecked")
                    TrackedDataHandler<Byte> h = (TrackedDataHandler<Byte>) entry.handler();
                    newList.add(new DataTracker.SerializedEntry<>(0, h, flags));
                    flagsModified = true;
                    continue;
                }
            }
            if (entry.id() == POSE_ID) {
                if (entry.value() instanceof EntityPose pose && pose != EntityPose.GLIDING) {
                    @SuppressWarnings("unchecked")
                    TrackedDataHandler<EntityPose> h = (TrackedDataHandler<EntityPose>) entry.handler();
                    newList.add(new DataTracker.SerializedEntry<>(entry.id(), h, EntityPose.GLIDING));
                    continue;
                }
            }
            newList.add(entry);
        }

        if (flagsModified) {
            infinityElytra.finishTransaction(player);
        }
        if (!newList.equals(entries)) {
            entryRef.set(newList);
        }
    }
}
