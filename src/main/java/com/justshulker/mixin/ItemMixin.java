package com.justshulker.mixin;

import com.justshulker.ShulkerBoxOpener;                          // 潜影盒 GUI 工具类
import net.minecraft.network.chat.Component;                      // GUI 标题文字
import net.minecraft.server.level.ServerPlayer;                   // 服务端玩家（只有服务端能打开菜单）
import net.minecraft.world.InteractionHand;                       // 主手/副手
import net.minecraft.world.InteractionResult;                    // 交互结果（SUCCESS/FAIL/PASS）
import net.minecraft.world.SimpleMenuProvider;                     // 简单 MenuProvider 实现（原版 EnderChestBlock 用的）
import net.minecraft.world.item.BlockItem;                        // 方块物品基类
import net.minecraft.world.item.Item;                             // 物品基类（我们 Mixin 的目标）
import net.minecraft.world.inventory.ChestMenu;                    // 末影箱用 ChestMenu（3行27格）
import net.minecraft.world.level.block.EnderChestBlock;             // 末影箱方块类
import net.minecraft.world.level.block.ShulkerBoxBlock;            // 潜影盒方块类
import org.spongepowered.asm.mixin.Mixin;                         // Mixin 注解
import org.spongepowered.asm.mixin.injection.At;                  // 注入点位置
import org.spongepowered.asm.mixin.injection.Inject;              // 注入类型
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable; // 回调对象

/**
 * Mixin 目标：Item（所有物品的基类）
 * 注入点：use(Level, Player, InteractionHand) 方法
 * 
 * 为什么需要？—— 覆盖"右键空气"的情况
 * 
 * 原版调用链：
 *   玩家右键 → 有瞄准方块？
 *     ├─ YES → BlockItem.useOn() ← BlockItemMixin 拦截
 *     └─ NO  → Item.use()       ← 这里拦截（右键空气、抬头右键等）
 * 
 * 为什么 Mixin Item 而不是 BlockItem？
 *   BlockItem 根本没重写 use()！只有 useOn()。
 *   所以右键空气时走的是 Item 基类的 use()，必须在那里拦截。
 */
@Mixin(Item.class)
public class ItemMixin {

    /**
     * 在 Item.use() 方法入口注入
     * 
     * 逻辑和 BlockItemMixin 完全一致：
     *   - 潜影盒 → ShulkerBoxOpener.open()
     *   - 末影箱 → player.openMenu(player.getEnderChestInventory())
     */
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void onUseContainerItem(net.minecraft.world.level.Level level,
                                    net.minecraft.world.entity.player.Player player,
                                    InteractionHand hand,
                                    CallbackInfoReturnable<InteractionResult> cir) {

        // 第一步：是不是 BlockItem（方块物品）
        if (!((Object) this instanceof BlockItem blockItem)) return;

        // 第二步：是不是潜影盒或末影箱
        boolean isShulker = blockItem.getBlock() instanceof ShulkerBoxBlock;
        boolean isEnderChest = blockItem.getBlock() instanceof EnderChestBlock;
        if (!isShulker && !isEnderChest) return;

        // 第三步：潜行让原版处理
        if (player.isShiftKeyDown()) return;

        // 第四步：服务端才真正打开菜单
        if (!(player instanceof ServerPlayer serverPlayer)) {
            cir.setReturnValue(InteractionResult.PASS);
            return;
        }

        // 第五步：末影箱和潜影箱走不同路径
        if (isEnderChest) {
            // 末影箱：复用原版 EnderChestBlock.use() 的写法
            // 注意 lambda 里的参数名不能和外层方法参数重名，所以用 p 代替 player
            serverPlayer.openMenu(new SimpleMenuProvider(
                (containerId, playerInventory, p) -> ChestMenu.threeRows(
                    containerId, playerInventory, serverPlayer.getEnderChestInventory()
                ),
                Component.translatable("container.enderchest")
            ));
        } else {
            // 潜影盒：SyncingContainer 实时写回，不需要关闭时再处理
            ShulkerBoxOpener.open(serverPlayer, hand);
        }

        cir.setReturnValue(InteractionResult.SUCCESS);
    }
}
