package com.justshulker;

import net.minecraft.network.chat.Component;                                    // GUI 上显示的文字（可翻译组件）
import net.minecraft.server.level.ServerPlayer;                                 // 服务端玩家（只有服务端玩家能打开菜单）
import net.minecraft.world.Container;                                           // 容器接口（定义 getItem/setItem 等方法）
import net.minecraft.world.SimpleContainer;                                     // 简单容器实现（27格 = 潜影盒的容量）
import net.minecraft.world.entity.player.Inventory;                              // 玩家背包（打开 GUI 时要显示）
import net.minecraft.world.entity.player.Player;                                 // 玩家基类
import net.minecraft.world.inventory.AbstractContainerMenu;                      // 所有菜单的基类
import net.minecraft.world.inventory.ShulkerBoxMenu;                            // 原版潜影盒菜单（27格，用 ShulkerBoxSlot 禁止嵌套）
import net.minecraft.world.item.ItemStack;                                       // 物品实例（带 NBT/组件/数量）
import net.minecraft.world.item.component.ItemContainerContents;                 // 1.21 新增的容器内容数据组件（存储容器物品列表）

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 潜影盒物品 GUI 管理器 —— 负责"打开"和"关闭写回"
 * 
 * 为什么需要这个类？
 *   原版潜影盒 GUI 是绑定在 ShulkerBoxBlockEntity（方块实体）上的，
 *   但我们要让"拿在手里的潜影盒物品"也能打开 GUI，它不是方块实体。
 *   所以我们自己创建一个 SimpleContainer（临时内存容器），
 *   用原版 ShulkerBoxMenu 把它包起来，打开给玩家看。
 * 
 * 数据读写流程：
 *   打开：ItemStack ← ItemContainerContents ← SimpleContainer ← ShulkerBoxMenu ← 玩家
 *   关闭：玩家 → ShulkerBoxMenu → SimpleContainer → ItemContainerContents → ItemStack
 * 
 * 关键概念：
 *   - ItemContainerContents：1.21 新数据组件，替代旧 NBT 的 "Items" 列表。
 *     通过 stack.get(DataComponents.CONTAINER) 读取
 *     通过 stack.set(DataComponents.CONTAINER, ...) 写入
 *   - SimpleContainer：原版提供的轻量容器，内部是 NonNullList<ItemStack>
 *   - MenuProvider：player.openMenu() 需要的接口，让游戏知道要打开什么菜单
 */
public class ShulkerBoxOpener {

    /**
     * 当前"正在打开潜影盒 GUI"的玩家 → 会话数据 的映射
     * 
     * 为什么需要这个 Map？
     *   当玩家关闭 GUI 时，游戏只给我们传 Menu 对象，
     *   但我们需要知道"这个 Menu 对应的是哪个 ItemStack"才能写回。
     *   所以打开时存一份，关闭时取出来用。
     * 
     * key = 玩家 UUID（每个玩家唯一，即使重名也不会混淆）
     * value = ShulkerBoxSession（保存 ItemStack + SimpleContainer）
     */
    public static final Map<java.util.UUID, ShulkerBoxSession> OPEN_SESSIONS = new HashMap<>();

    /**
     * 打开潜影盒 GUI（必须在服务端调用！）
     * 
     * @param player       服务端玩家（只有 ServerPlayer 有 openMenu 方法）
     * @param shulkerStack 手里的潜影盒物品（要读取它的内容并最终写回）
     */
    public static void open(ServerPlayer player, ItemStack shulkerStack) {
        
        // 第一步：创建临时内存容器
        // 玩家在 GUI 里做的所有操作（放/拿/挪）都先发生在这个临时容器里
        // 
        // 为什么用 ShulkerBoxBlockEntity.CONTAINER_SIZE 而不是写死 27？
        //   原版所有地方（BlockEntity、Menu）都用这个常量，跟原版保持一致。
        //   原版没有 Item.getContainerSize() 方法，那个是 BlockEntity 才有的实例方法。
        //   如果以后要做不同容量的自定义潜影盒，就不能用原版 ShulkerBoxMenu 了，
        //   得自己写一个不硬校验容量的自定义 Menu。
        final int containerSize = net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity.CONTAINER_SIZE;
        SimpleContainer container = new SimpleContainer(containerSize);

        
        // 第二步：把物品里已有的内容（如果有的话）加载到临时容器
        // 
        // 1.21 以前是读 NBT tag：item.getOrCreateTag().getList("Items", 10)
        // 1.21 以后用 Data Components：item.get(DataComponents.CONTAINER)
        // 
        // copyInto(NonNullList<ItemStack>) 会把容器内容逐格拷进去，
        // 空物品（EMPTY）的格子也会被填充，保证大小是 27
        ItemContainerContents contents = shulkerStack.get(net.minecraft.core.component.DataComponents.CONTAINER);
        if (contents != null) {
            contents.copyInto(container.items);
        }
        // 如果 contents 是 null，说明这潜影盒从来没装过东西（是空的），
        // 那 SimpleContainer(27) 已经自带了 27 个 EMPTY，不需要额外处理

        // 第三步：保存会话到 Map
        // 关闭菜单时 ShulkerBoxMenuMixin 会调用 onMenuClosed()，
        // 从这个 Map 里取出会话，把 SimpleContainer 的内容写回 ItemStack
        OPEN_SESSIONS.put(player.getUUID(), new ShulkerBoxSession(shulkerStack, container));

        // 第四步：打开 GUI
        // MenuProvider 是一个接口，我们用匿名类实现它的两个方法：
        //   createMenu() — 创建实际的菜单对象，游戏会自动处理网络同步
        //   getDisplayName() — GUI 标题栏显示的名字
        player.openMenu(new net.minecraft.world.MenuProvider() {
            
            /**
             * 创建菜单（游戏内部调用，网络包到达客户端时客户端也会再调一次）
             * 
             * ShulkerBoxMenu 构造函数：
             *   containerId   — 游戏分配的菜单 ID（网络同步用的）
             *   playerInventory — 玩家背包（用来在 GUI 下半部分显示）
             *   container      — 我们的临时容器（GUI 上半部分显示的潜影盒内容）
             */
            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player p) {
                // 为什么用原版 ShulkerBoxMenu 而不是 ChestMenu？
                //   ShulkerBoxMenu 内部用 ShulkerBoxSlot，它会禁止把潜影盒放进潜影盒
                //   ChestMenu 用普通 Slot，就能嵌套了（游戏规则不允许）
                return new ShulkerBoxMenu(containerId, playerInventory, container);
            }

            /**
             * GUI 标题文字（玩家看到的）
             * 
             * 如果潜影盒有自定义名字（铁砧改的）就显示自定义名，
             * 否则显示原版翻译键 "container.shulkerBox"（默认就是"潜影盒"）
             */
            @Override
            public Component getDisplayName() {
                Component customName = shulkerStack.getHoverName();
                return customName != null ? customName : Component.translatable("container.shulkerBox");
            }
        });
    }

    /**
     * 菜单关闭时把 GUI 里的改动写回 ItemStack
     * 
     * 谁调用它？—— ShulkerBoxMenuMixin 拦截 ShulkerBoxMenu.removed() 后调
     * 什么时候调用？—— 玩家按 ESC 关 GUI、点右上角 X、或切换到别的菜单时
     * 
     * @param player 关闭菜单的玩家（用于从 OPEN_SESSIONS 里找到对应的会话）
     */
    public static void onMenuClosed(ServerPlayer player) {
        // 从 Map 里取出会话并移除（remove = 取出后删除，防止内存泄漏）
        ShulkerBoxSession session = OPEN_SESSIONS.remove(player.getUUID());
        
        // 如果是 null，说明关闭的菜单不是我们打开的（比如玩家打开了箱子 GUI）
        // 我们只关心自己打开的潜影盒，所以直接 return
        if (session == null) return;

        // 把 SimpleContainer 里的物品列表构建出来
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < session.container.getContainerSize(); i++) {
            // copy() 很重要！否则容器里的 ItemStack 和写进 NBT 的是同一个引用，
            // 可能被后续操作意外修改
            items.add(session.container.getItem(i).copy());
        }

        // 关键：把物品列表重新打包成 ItemContainerContents 写回 ItemStack
        // 
        // fromItems(List<ItemStack>) 会：
        //   1. 找到最后一个非空格的索引 i
        //   2. 创建一个大小为 i+1 的 ItemContainerContents
        //   3. 前面的空格不存，节省 NBT 体积
        // 
        // set(DataComponents.CONTAINER, ...) 会：
        //   如果写 EMPTY，等价于移除这个组件（空潜影盒就不带容器数据）
        session.shulkerStack.set(net.minecraft.core.component.DataComponents.CONTAINER,
                ItemContainerContents.fromItems(items));
    }

    /**
     * 会话数据 —— 只是个简单的 POJO（普通数据类）
     * 
     * 为什么需要它？
     *   - 我们打开 Menu 时创建了 SimpleContainer（临时容器）
     *   - 关闭 Menu 时要把 SimpleContainer 的内容写回 ItemStack
     *   - 两者都要保存，所以用这个小类把它们打在一起
     * 
     * 字段说明：
     *   shulkerStack — 玩家手里那个潜影盒物品的引用（最终要写回它）
     *   container    — 这次 GUI 对应的临时容器（玩家所有操作都发生在这上面）
     */
    public static class ShulkerBoxSession {
        public final ItemStack shulkerStack;
        public final SimpleContainer container;

        public ShulkerBoxSession(ItemStack shulkerStack, SimpleContainer container) {
            this.shulkerStack = shulkerStack;
            this.container = container;
        }
    }
}
