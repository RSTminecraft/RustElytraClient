package dev.rstminecraft.utils;


import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;

import static dev.rstminecraft.RustElytraClient.MODLOGGER;

public class RSTMsgSender {
    interface logLevel{
        void log(String s);
    }
    private static final logLevel[] logger = {MODLOGGER::debug,MODLOGGER::debug,MODLOGGER::info,MODLOGGER::warn,MODLOGGER::error,MODLOGGER::error};
    private static final String[] LevelStr = {"§7[Rust Elytra]", "§7[Rust Elytra]", "§8[Rust Elytra]", "§6[Rust Elytra Warn]", "§4[Rust Elytra Error]", "§4§l[Rust Elytra Fatal]"};
    final MsgLevel DisplayLevel;

    /**
     *
     * @param DL 最低打印到聊天区的消息级别
     */
    public RSTMsgSender(MsgLevel DL) {
        DisplayLevel = DL;
    }

    /**
     * 发送消息
     *
     * @param player 非null玩家对象
     * @param msg 消息内容
     * @param level 消息级别
     */
    public void SendMsg(@NotNull PlayerEntity player, String msg, @NotNull MsgLevel level) {
        if (level.ordinal() < DisplayLevel.ordinal()) {
            if (level == MsgLevel.tip)
                player.sendMessage(Text.literal(LevelStr[level.ordinal()] + msg + "§r"), true);
            logger[level.ordinal()].log("[Rust Elytra Log]" + msg);
            return;
        }
        player.sendMessage(Text.literal(LevelStr[level.ordinal()] + msg + "§r"), false);
        logger[level.ordinal()].log("[Rust Elytra Log]" + msg);
    }
}
