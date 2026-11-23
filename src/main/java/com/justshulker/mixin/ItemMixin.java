package com.justshulker.mixin;

import com.justshulker.ShulkerBoxOpener;                          // 我们自己的工具类，负责打开 GUI
import net.minecraft.server.level.ServerPlayer;                   // 服务端玩家（只有服务端能打开菜单）
import net.minecraft.world.InteractionHand;                       // 主手还是副手
import net.minecraft.world.InteractionResult;                    // 交互结果（SUCCESS/FAIL/PASS 等）
import net.minecraft.world.item.BlockItem;                        // 方块物品类（潜影盒物品就是它，继承自 Item）
import net.minecraft.world.item.Item;                             // 物品基类（我们 Mixin 的目标）
import net.minecraft.world.level.block.ShulkerBoxBlock;            // 潜影盒方块类（用来判断是不是潜影盒）
import org.spongepowered.asm.mixin.Mixin;                         // Mixin 注解：告诉框架要修改哪个类
import org.spongepowered.asm.mixin.injection.At;                  // 注入点位置
import org.spongepowered.asm.mixin.injection.Inject;              // 注入类型：在目标方法里插入代码
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable; // 带返回值方法的回调

/**
 * Mixin 目标：Item（所有物品的基类）
 * 注入点：use(Level, Player, InteractionHand) 方法
 * 
 * 为什么需要这个 Mixin？—— 覆盖"右键空气"的情况
 * 
 * 原版玩家右键的调用链：
 *   Player 按右键
 *     ↓
 *   有瞄准方块吗？
 *     ├─ YES → BlockItem.useOn(UseOnContext)  ← BlockItemMixin 在这里拦截 ✅
 *     └─ NO  → Item.use(Level, Player, Hand)  ← 这里！我们需要在这里也拦截
 * 
 * 为什么 BlockItem 不重写 use()？
 *   BlockItem 只关心"放方块"这个行为，只有右键方块才有意义。
 *   右键空气对方块物品来说"啥也做不了"，所以 Item 基类的 use() 默认返回 PASS。
 *   但我们的需求是"右键空气也能开 GUI"，所以必须拦截 Item 基类的 use()。
 * 
 * 为什么直接 Mixin Item 而不是 Mixin BlockItem？
 *   因为 BlockItem 根本没有重写 use()！
 *   Mixin BlockItem 时找不到 use 方法就会报 InvalidInjectionException 崩溃。
 *   所以只能 Mixin Item 基类，在里面判断"是不是 BlockItem + 是不是潜影盒"。
 * 
 * 原版 Item.use() 做了什么？
 *   // 默认实现就是空的，很多子类（药水、食物、弓）才重写它
 *   public InteractionResult use(Level level, Player player, InteractionHand hand) {
 *       return InteractionResult.PASS;  // 什么也不做
 *   }
 */
@Mixin(Item.class)
public class ItemMixin {

    /**
     * 在 Item.use() 的方法入口（HEAD）注入我们的逻辑
     * 
     * 参数说明：
     *   level  —— 当前所在的世界（服务端 world 或客户端 world）
     *   player —— 触发交互的玩家
     *   hand   —— 用的是主手还是副手（InteractionHand.MAIN_HAND / OFF_HAND）
     *   cir    —— 回调对象，调用 cir.setReturnValue(...) 可以提前返回
     */
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void onUseShulker(net.minecraft.world.level.Level level,
                              net.minecraft.world.entity.player.Player player,
                              InteractionHand hand,
                              CallbackInfoReturnable<InteractionResult> cir) {

        // 第一步：判断 this 是不是 BlockItem
        // 
        // 因为我们 Mixin 的是 Item 基类，所有物品（钻石、弓、药水...）的 use()
        // 都会经过这里。我们只关心 BlockItem（方块物品），因为潜影盒就是它。
        // 
        // 写法说明：
        //   Mixin 里 (Object) this 是代理对象，实际运行时就是 Item 的子类实例
        //   Pattern Matching for instanceof（Java 16+）：
        //     同时完成类型检查 + 变量绑定
        //     if (obj instanceof Type var) → obj 是 Type 时自动赋给 var
        if (!((Object) this instanceof BlockItem blockItem)) return;

        // 第二步：判断这个 BlockItem 对应的方块是不是潜影盒
        // 
        // BlockItem.getBlock() 返回它对应的 Block 实例
        // 潜影盒物品对应的是 ShulkerBoxBlock
        // 如果不是潜影盒 → 直接 return，原版逻辑正常跑（大部分物品原版 use() 也是 PASS）
        if (!(blockItem.getBlock() instanceof ShulkerBoxBlock)) return;

        // 第三步：判断玩家状态 —— 不拦截潜行
        // 
        // 潜行 + 右键空气本来也就没啥事（原版潜行右键放方块，但没瞄准方块就放不了）
        // 为了和 useOn 的行为保持一致（潜行 → 让原版处理），这里也跳过潜行
        if (player.isShiftKeyDown()) return;

        // 第四步：区分客户端/服务端（和 BlockItemMixin 里的逻辑一样）
        // 
        // 服务端才能真正打开菜单（菜单打开会发网络包同步给客户端）
        // 客户端返回 PASS 让游戏自己把交互发到服务端处理
        if (player instanceof ServerPlayer serverPlayer) {
            // player.getItemInHand(hand) —— 从玩家指定的手里拿到物品（主手或副手）
            ShulkerBoxOpener.open(serverPlayer, player.getItemInHand(hand));
            cir.setReturnValue(InteractionResult.SUCCESS);
        } else {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }
}
