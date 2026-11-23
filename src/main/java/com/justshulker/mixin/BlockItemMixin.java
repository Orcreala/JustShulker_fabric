package com.justshulker.mixin;

import com.justshulker.ShulkerBoxOpener;                          // 我们自己的工具类，负责打开 GUI
import net.minecraft.server.level.ServerPlayer;                   // 服务端玩家（只有服务端能打开菜单）
import net.minecraft.world.InteractionHand;                       // 主手/副手（这个文件里没用到，保留给可能的扩展）
import net.minecraft.world.InteractionResult;                    // 交互结果（SUCCESS/FAIL/PASS 等）
import net.minecraft.world.item.BlockItem;                        // 方块物品基类（潜影盒物品就是它）
import net.minecraft.world.item.Item;                             // 物品基类（BlockItem 的父类）
import net.minecraft.world.item.context.UseOnContext;              // 右键方块时传入的上下文（包含点击位置、方块、玩家等）
import net.minecraft.world.level.block.ShulkerBoxBlock;            // 潜影盒方块类（用来判断是不是潜影盒）
import org.spongepowered.asm.mixin.Mixin;                         // Mixin 注解：告诉框架要修改哪个类
import org.spongepowered.asm.mixin.injection.At;                  // 注入点位置（HEAD/RETURN/INVOKE 等）
import org.spongepowered.asm.mixin.injection.Inject;              // 注入类型：在目标方法里插入代码
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable; // 带返回值方法的回调（可以 setReturnValue 提前返回）

/**
 * Mixin 目标：BlockItem（方块物品基类）
 * 注入点：useOn(UseOnContext) 方法
 * 
 * BlockItem.useOn() 什么时候被调用？
 *   玩家右键时，游戏先检查"有没有瞄准方块"
 *   有 → 调 item.useOn(UseOnContext)
 *   没有 → 调 item.use(Level, Player, Hand)（在 ItemMixin 里拦截）
 * 
 * 原版 BlockItem.useOn() 做了什么？
 *   public InteractionResult useOn(UseOnContext ctx) {
 *       InteractionResult result = this.place(new BlockPlaceContext(ctx));
 *       // place() 尝试放置方块，成功返回 SUCCESS，失败返回 FAIL
 *       return !result.consumesAction() && hasConsumable
 *           ? super.use(...)  // 有 CONSUMABLE 组件才 fallback 到 use()
 *           : result;
 *   }
 * 
 * 我们的拦截策略：
 *   - 只拦截"潜影盒物品"（通过 BlockItem.getBlock() instanceof ShulkerBoxBlock 判断）
 *   - 只拦截"非潜行右键"（isShiftKeyDown() == false）
 *   - 拦截后：打开 GUI + 返回 SUCCESS（阻止原版放置逻辑）
 *   - 不拦截时：原版逻辑正常执行（潜行右键 → 放置）
 * 
 * 注意：为什么 BlockItem 没重写 use() 却重写了 useOn()？
 *   因为"右键方块"的处理是方块物品特有的，
 *   而"右键空气"（喝药水、吃食物等）在 Item 基类里就有了。
 */
@Mixin(BlockItem.class)
public class BlockItemMixin {

    /**
     * 在 BlockItem.useOn() 的方法入口（HEAD）注入我们的逻辑
     * 
     * @Inject 参数说明：
     *   method = "useOn"       —— 要注入的目标方法名
     *   at = @At("HEAD")       —— 在目标方法的第一行之前插入（HEAD = 方法入口）
     *   cancellable = true     —— 允许我们通过 cir.setReturnValue() 提前返回，
     *                              原版 useOn() 的后续代码就不会执行了
     * 
     * 方法签名必须和原版 useOn 一致（参数类型、顺序、返回值），
     * 只是多加了 CallbackInfoReturnable<InteractionResult> 这个回调参数
     */
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void onUseOnShulker(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        
        // (Object) this 是一个 Mixin 里的惯用写法：
        //   - 在 Mixin 类里，this 是代理对象，类型是 @Mixin 的目标类（BlockItem）
        //   - 但编译器不知道，所以先强转 Object 再强转 BlockItem 就不会报错
        BlockItem item = (BlockItem) (Object) this;

        // 第一步：快速排除非潜影盒物品
        // 
        // BlockItem 有个 getBlock() 方法返回它对应的方块
        // 潜影盒物品对应的就是 ShulkerBoxBlock 的实例
        // 
        // 如果不是潜影盒 → 直接 return，让原版逻辑继续执行（放方块）
        if (!(item.getBlock() instanceof ShulkerBoxBlock)) return;

        // 第二步：判断玩家状态
        // 
        // context.getPlayer() 可能为 null（比如某些自动交互场景），
        // 先做 null 检查再判断是不是按着 Shift（潜行键）
        // 
        // 我们只拦截"非潜行"的情况，
        // 潜行右键仍然让原版放置（符合用户需求：蹲下右键放地上）
        if (context.getPlayer() != null && !context.getPlayer().isShiftKeyDown()) {
            
            // 第三步：区分客户端/服务端
            // 
            // 游戏有客户端和服务端两个逻辑：
            //   客户端 —— 处理玩家输入、渲染画面，但不能真的打开菜单
            //   服务端 —— 真正执行业务逻辑，包括打开菜单（会同步给客户端）
            // 
            // 所以：服务端才调 open()，客户端返回 PASS 让游戏自己去服务端处理
            if (context.getPlayer() instanceof ServerPlayer serverPlayer) {
                // 服务端：打开 GUI
                ShulkerBoxOpener.open(serverPlayer, context.getItemInHand());
                // SUCCESS = 告诉游戏"这个交互已经被处理了，不用再往后走了"
                cir.setReturnValue(InteractionResult.SUCCESS);
            } else {
                // 客户端：PASS = 继续让游戏走正常流程，
                // 客户端拦截后游戏会发一个网络包告诉服务端"玩家右键了这个物品"，
                // 服务端收到后会调 useOn()/use()，那时我们的拦截也会生效
                cir.setReturnValue(InteractionResult.PASS);
            }
        }
        // 第四步（隐式）：如果是潜行右键 → 不进 if 分支 → 不调 setReturnValue → 
        // 原版 useOn() 继续执行 → 正常放置潜影盒 ✅
    }
}
