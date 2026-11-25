package com.justshulker;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 潜影盒物品 GUI 管理器
 * 
 * 【核心设计：UUID 精确定位 + 实时同步】
 *   每个潜影盒被分配唯一 UUID（存 DataComponents.CUSTOM_DATA 里），
 *   无论它在主手、副手、光标、36 个背包槽中的哪个位置，
 *   都能通过 findShulker(player, boxId) 精确找到它。
 * 
 * 【极简架构】
 *   1. SyncingContainer.setChanged() —— 物品变动时立即写回 ItemStack
 *   2. ServerTickEvents tick 检查 —— 潜影盒丢失时自动关闭 GUI
 *   就这两件事。没有关闭时的额外写回逻辑。
 * 
 *   不需要 ShulkerBoxMenu.removed() 里清理（tick 会兜底），
 *   不需要打开新潜影盒前手动清旧会话（HashMap.put 会直接覆盖）。
 */
public class ShulkerBoxOpener {

    /** 我们存唯一 ID 的 NBT key */
    public static final String BOX_ID_KEY = "justshulker:box_id";

    /** 正在打开 GUI 的玩家 → 会话（只用 player UUID 做 key，一个玩家同一时刻只能开一个 GUI） */
    public static final Map<UUID, ShulkerBoxSession> OPEN_SESSIONS = new HashMap<>();

    /**
     * 打开潜影盒 GUI（必须在服务端调用）
     */
    public static void open(ServerPlayer player, InteractionHand hand) {
        ItemStack shulkerStack = player.getItemInHand(hand);

        // 给这个潜影盒打一个唯一 ID（如果还没有的话）
        UUID boxId = getOrCreateBoxId(shulkerStack);

        // 初始化容器：创建 SyncingContainer（物品变动时自动写回）+ 从 ItemStack 加载现有内容
        final int size = ShulkerBoxBlockEntity.CONTAINER_SIZE;
        SyncingContainer container = new SyncingContainer(player, boxId, size);
        ItemContainerContents contents = shulkerStack.get(DataComponents.CONTAINER);
        if (contents != null) {
            contents.copyInto(container.items);
        }

        // 保存会话（HashMap.put 自动覆盖，不需要手动 remove 旧的）
        OPEN_SESSIONS.put(player.getUUID(), new ShulkerBoxSession(boxId, container));

        // 打开 GUI
        player.openMenu(new net.minecraft.world.MenuProvider() {
            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player p) {
                return new ShulkerBoxMenu(containerId, playerInventory, container);
            }
            @Override
            public Component getDisplayName() {
                Component customName = shulkerStack.getHoverName();
                return customName != null ? customName : Component.translatable("container.shulkerBox");
            }
        });
    }

    /**
     * 给潜影盒获取或创建唯一 ID
     * UUID 存 DataComponents.CUSTOM_DATA 里，第一次打开时创建，以后复用
     * 
     * 【1.21 变化】
     *   - CompoundTag.hasUUID/getUUID/putUUID 被移除了，改用 String 存 UUID
     *   - CUSTOM_DATA 值类型是 CustomData（不是 CompoundTag）
     *   - CustomData 构造器 private，必须用 CustomData.of(CompoundTag)
     *   - CompoundTag.getString() 返回 Optional<String>
     */
    public static UUID getOrCreateBoxId(ItemStack stack) {
        var customData = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = customData != null ? customData.copyTag() : new CompoundTag();

        Optional<String> existing = tag.getString(BOX_ID_KEY);
        if (existing.isPresent()) {
            return UUID.fromString(existing.get());
        }

        UUID newId = UUID.randomUUID();
        tag.putString(BOX_ID_KEY, newId.toString());
        stack.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
        return newId;
    }

    /** 检查 ItemStack 的 box_id 是否匹配 */
    public static boolean matchesBoxId(ItemStack stack, UUID boxId) {
        if (!(stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock)) return false;
        var customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return false;
        Optional<String> val = customData.copyTag().getString(BOX_ID_KEY);
        return val.isPresent() && UUID.fromString(val.get()).equals(boxId);
    }

    /**
     * 在玩家身上查找带指定 boxId 的潜影盒
     * 遍历：主手、副手、光标、36 个背包槽位
     * 
     * 被两个地方调用：
     *   - SyncingContainer.syncToItemStack() —— 物品变动时找位置写回
     *   - ServerTickEvents —— tick 检查物品是否还在
     * 
     * @return 找到的 ItemStack，或 null（真的丢了）
     */
    public static ItemStack findShulker(ServerPlayer player, UUID boxId) {
        // 主手
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (matchesBoxId(stack, boxId)) return stack;

        // 副手
        stack = player.getItemInHand(InteractionHand.OFF_HAND);
        if (matchesBoxId(stack, boxId)) return stack;

        // 光标（如果玩家正在操作任何 GUI）
        stack = player.containerMenu.getCarried();
        if (matchesBoxId(stack, boxId)) return stack;

        // 背包 36 槽
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            stack = player.getInventory().getItem(i);
            if (matchesBoxId(stack, boxId)) return stack;
        }

        return null;
    }

    /**
     * 清理玩家的会话（ServerTickEvents 在物品丢失时调）
     */
    public static void closeAll(ServerPlayer player) {
        OPEN_SESSIONS.remove(player.getUUID());
    }

    // ===================== 内部类 =====================

    /**
     * 实时同步容器 —— 用 UUID 精确定位物品位置
     * 
     * 每次玩家在 GUI 里放/拿物品 → Slot.setItem() → SimpleContainer.setItem() → setChanged()
     * → 我们重写的 setChanged() → syncToItemStack()
     * → findShulker(player, boxId) 精确定位当前物品位置
     * → 把容器内容写回 DataComponents.CONTAINER
     * 
     * 全程自动，不需要等 GUI 关闭。
     */
    public static class SyncingContainer extends SimpleContainer {
        private final ServerPlayer player;
        private final UUID boxId;

        public SyncingContainer(ServerPlayer player, UUID boxId, int size) {
            super(size);
            this.player = player;
            this.boxId = boxId;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            syncToItemStack();
        }

        /**
         * 把容器内容同步回那个唯一的潜影盒物品
         * 
         * 用 findShulker() 精确定位，不管物品在主手/副手/光标/背包
         * 如果物品真丢了（返回 null），就不写回了——没地方写
         */
        private void syncToItemStack() {
            ItemStack target = findShulker(player, boxId);
            if (target == null) return; // 物品丢了，不写回

            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < getContainerSize(); i++) {
                items.add(getItem(i).copy());
            }
            target.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        }
    }

    /**
     * 会话数据 —— 只存 boxId 和 SyncingContainer
     * 
     * 不存 InteractionHand！物品可以在主手/副手/光标/背包之间任意移动，
     * 存 hand 只能跟踪"之前在哪"，存 boxId + findShulker() 才能找到"现在在哪"。
     */
    public static class ShulkerBoxSession {
        public final UUID boxId;
        public final SyncingContainer container;

        public ShulkerBoxSession(UUID boxId, SyncingContainer container) {
            this.boxId = boxId;
            this.container = container;
        }
    }
}
