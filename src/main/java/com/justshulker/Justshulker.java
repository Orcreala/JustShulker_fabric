package com.justshulker;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * 模组主类 —— Fabric 入口点
 */
public class Justshulker implements ModInitializer {

    public static final String MOD_ID = "justshulker";
    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("JustShulker initialized!");

        // 每服务器 tick 检查会话状态
        // 用 UUID 精确定位潜影盒：主手/副手/光标/背包 → 都能找到
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ShulkerBoxOpener.ShulkerBoxSession session = 
                        ShulkerBoxOpener.OPEN_SESSIONS.get(player.getUUID());
                if (session == null) continue;

                // 精确定位：遍历所有可能位置找那个唯一 UUID 的潜影盒
                if (ShulkerBoxOpener.findShulker(player, session.boxId) == null) {
                    // 找不到 → 真丢了，清会话 + 关 GUI
                    ShulkerBoxOpener.closeAll(player);
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerClosePacket(
                            player.containerMenu.containerId));
                }
                // 找到了 → 什么都不用做，SyncingContainer 自己会实时同步
            }
        });
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
