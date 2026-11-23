package com.justshulker.mixin;

import com.justshulker.ShulkerBoxOpener;                          // 我们自己的工具类，负责把 GUI 改动写回物品
import net.minecraft.server.level.ServerPlayer;                   // 服务端玩家
import net.minecraft.world.entity.player.Player;                  // 玩家基类（removed 的参数类型）
import net.minecraft.world.inventory.ShulkerBoxMenu;            // 原版潜影盒菜单（我们打开 GUI 时用的就是这个类）
import org.spongepowered.asm.mixin.Mixin;                         // Mixin 注解
import org.spongepowered.asm.mixin.injection.At;                  // 注入点位置
import org.spongepowered.asm.mixin.injection.Inject;              // 注入类型
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo; // 无返回值方法的回调（不能 setReturnValue，只能等方法跑完）

/**
 * Mixin 目标：ShulkerBoxMenu（原版潜影盒菜单类）
 * 注入点：removed(Player) 方法
 * 
 * 什么时候 removed() 被调用？
 *   玩家关闭菜单时（按 ESC、点右上角 X、打开新菜单替换当前菜单...）
 *   原版在菜单关闭前会调 removed()，让菜单做清理工作：
 *     // ChestMenu.removed(Player player) 原版实现
 *     public void removed(Player player) {
 *         super.removed(player);
 *         this.container.stopOpen(player);  // 告诉方块"玩家离开了"（用于方块实体的状态更新）
 *     }
 * 
 * 我们的任务：
 *   玩家关掉潜影盒物品的 GUI 时，把 SimpleContainer 里的改动写回 ItemStack。
 *   具体怎么做？—— 调 ShulkerBoxOpener.onMenuClosed(serverPlayer)
 *   它会从 OPEN_SESSIONS Map 里取出会话，把容器内容写回物品，然后移除会话。
 * 
 * 为什么不用 Fabric ScreenHandlerEvents？
 *   Fabric API v1 里没有现成的"菜单关闭"事件（v2+ 有，但我们的 fabric-api 版本较老）。
 *   所以最直接的方式就是 Mixin ShulkerBoxMenu.removed()。
 *   好处是简单直接，而且 removed() 一定是在服务端先调用（客户端只是收到同步包）。
 * 
 * 为什么 @At("HEAD") 而不是 @At("RETURN")？
 *   HEAD = 在原版 removed() 开头就调我们的逻辑。
 *   RETURN = 原版逻辑跑完之后再调。
 *   对我们来说，HEAD 和 RETURN 都能工作（我们只是读容器内容写回物品），
 *   但 HEAD 更安全——如果原版逻辑里有修改容器的操作，我们提前写回也能保证数据一致。
 */
@Mixin(ShulkerBoxMenu.class)
public class ShulkerBoxMenuMixin {

    /**
     * 在 ShulkerBoxMenu.removed() 的方法入口注入
     * 
     * @Inject 参数说明：
     *   method = "removed"    —— 要注入的方法名
     *   at = @At("HEAD")      —— 在方法的第一行之前插入
     *   cancellable = false   —— 默认值，removed 是 void 方法，没返回值可取消
     * 
     * 注意 CallbackInfo 和 CallbackInfoReturnable 的区别：
     *   - CallbackInfo           → 用于 void 方法（没有返回值）
     *   - CallbackInfoReturnable → 用于有返回值的方法（可以 setReturnValue 提前返回）
     * 
     * removed(Player player) 是 void 方法，所以用 CallbackInfo
     */
    @Inject(method = "removed", at = @At("HEAD"))
    private void onRemoved(Player player, CallbackInfo ci) {
        
        // 只处理服务端玩家（服务端才真正持有 ItemStack 数据，客户端只是显示）
        // instanceof 还是用了 Pattern Matching，省一步强转
        if (player instanceof ServerPlayer serverPlayer) {
            // 调 ShulkerBoxOpener 写回数据
            // 它内部会：
            //   1. 从 OPEN_SESSIONS 里取出会话
            //   2. 把 SimpleContainer 的内容拷成 List<ItemStack>
            //   3. 构建 ItemContainerContents 写回 ItemStack 的 CONTAINER 组件
            //   4. 从 Map 里移除会话
            ShulkerBoxOpener.onMenuClosed(serverPlayer);
        }
        // 客户端玩家 → 什么也不做，关闭菜单的逻辑本来就在客户端了
        // 我们的 open() 只在服务端调，所以 OPEN_SESSIONS 里只会有服务端玩家的会话
    }
}
