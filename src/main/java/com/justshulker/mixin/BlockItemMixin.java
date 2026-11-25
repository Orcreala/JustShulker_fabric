package com.justshulker.mixin;

import com.justshulker.ShulkerBoxOpener;                          // 我们自己的工具类，负责打开潜影盒 GUI
import net.minecraft.network.chat.Component;                      // GUI 标题文字
import net.minecraft.server.level.ServerPlayer;                   // 服务端玩家（只有服务端能打开菜单）
import net.minecraft.world.InteractionResult;                    // 交互结果（SUCCESS/FAIL/PASS 等）
import net.minecraft.world.SimpleMenuProvider;                     // 简单 MenuProvider 实现（原版 EnderChestBlock 用的）
import net.minecraft.world.item.BlockItem;                        // 方块物品基类（潜影盒、末影箱物品都是它）
import net.minecraft.world.item.context.UseOnContext;              // 右键方块时传入的上下文
import net.minecraft.world.inventory.ChestMenu;                    // 末影箱用 ChestMenu（3行27格）
import net.minecraft.world.level.block.EnderChestBlock;             // 末影箱方块类（判断是不是末影箱）
import net.minecraft.world.level.block.ShulkerBoxBlock;            // 潜影盒方块类（判断是不是潜影盒）
import org.spongepowered.asm.mixin.Mixin;                         // Mixin 注解
import org.spongepowered.asm.mixin.injection.At;                  // 注入点位置
import org.spongepowered.asm.mixin.injection.Inject;              // 注入类型
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable; // 带返回值方法的回调

/**
 * Mixin 目标：BlockItem（方块物品基类）
 * 注入点：useOn(UseOnContext) 方法
 * 
 * 拦截目标：
 *   - 潜影盒物品（ShulkerBoxBlock）→ 打开物品 GUI
 *   - 末影箱物品（EnderChestBlock）→ 打开末影箱 GUI（内容存玩家身上）
 * 
 * 为什么要 Mixin BlockItem？
 *   原版 BlockItem.useOn() 负责"右键方块→放置方块"，
 *   我们在它执行放置逻辑之前拦截，改成打开 GUI。
 * 
 * 调用链：
 *   玩家右键 → 有瞄准方块？ → 是 → BlockItem.useOn() ← 我们在这里拦截
 *                            → 否 → Item.use()（在 ItemMixin 里拦截）
 */
@Mixin(BlockItem.class)
public class BlockItemMixin {

    /**
     * 在 BlockItem.useOn() 的方法入口注入
     * 
     * 逻辑：
     *   1. 判断是不是潜影盒或末影箱
     *   2. 判断是不是非潜行（潜行让原版放置）
     *   3. 末影箱 → 直接调 player.openMenu(player.getEnderChestInventory())
     *      潜影盒 → 调 ShulkerBoxOpener.open()（需要写回 ItemStack）
     */
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void onUseOnContainerItem(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        
        BlockItem item = (BlockItem) (Object) this;

        // 第一步：快速排除——既不是潜影盒也不是末影箱就不管
        boolean isShulker = item.getBlock() instanceof ShulkerBoxBlock;
        boolean isEnderChest = item.getBlock() instanceof EnderChestBlock;
        if (!isShulker && !isEnderChest) return;

        // 第二步：潜行右键 → 不拦截，原版正常放置
        // （SyncingContainer.setChanged() 已经实时把内容写回 ItemStack 了，
        //   原版 place() 拿到的就是最新物品 → 地上的潜影盒也会带内容 ✅）
        if (context.getPlayer() == null || context.getPlayer().isShiftKeyDown()) return;

        // 第三步：服务端才真正打开菜单
        if (!(context.getPlayer() instanceof ServerPlayer serverPlayer)) {
            // 客户端返回 PASS，让游戏把交互发到服务端处理
            cir.setReturnValue(InteractionResult.PASS);
            return;
        }

        // 第四步：根据类型走不同的打开逻辑
        if (isEnderChest) {
            // 末影箱：内容存在玩家身上（enderChestInventory），物品本身不带数据
            // 原版 EnderChestBlock.use() 里也是这么打开的：
            //   player.openMenu(new SimpleMenuProvider(
            //       (i, inv, p) -> ChestMenu.threeRows(i, inv, enderChestContainer),
            //       Component.translatable("container.enderchest")
            //   ));
            // 我们直接复用同样的写法，菜单会自动读/写玩家的末影箱存储
            serverPlayer.openMenu(new SimpleMenuProvider(
                (containerId, playerInventory, player) -> ChestMenu.threeRows(
                    containerId, playerInventory, serverPlayer.getEnderChestInventory()
                ),
                Component.translatable("container.enderchest")
            ));
        } else {
            // 潜影盒：内容存在 ItemStack 的 DataComponents.CONTAINER 里
            // SyncingContainer.setChanged() 会实时写回，不需要手动 close 时再写
            ShulkerBoxOpener.open(serverPlayer, context.getHand());
        }

        // 告诉游戏"这个交互已经被处理了"，原版放置逻辑不会执行
        cir.setReturnValue(InteractionResult.SUCCESS);
    }
}
