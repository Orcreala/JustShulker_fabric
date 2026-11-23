package com.justshulker;

import net.fabricmc.api.ModInitializer;   // Fabric 模组主入口接口，所有模组必须实现它
import net.minecraft.resources.Identifier;  // Minecraft 的资源定位符（"命名空间:路径"格式），用于注册物品、方块等
import org.slf4j.Logger;                     // SLF4J 日志接口，模组里统一用它打日志
import org.slf4j.LoggerFactory;             // Logger 的工厂类

/**
 * 模组主类 —— Fabric 模组的入口点
 * 
 * 加载顺序：Minecraft 启动 → Fabric Loader 读取 fabric.mod.json
 * → 找到 entrypoints.main 里写的这个类 → 实例化并调用 onInitialize()
 * 
 * 这里只做最简单的事：打印一条日志表示模组加载成功
 * 其他功能（Mixin、注册表等）通过 fabric.mod.json 和 mixins.json 自动加载
 */
public class Justshulker implements ModInitializer {

    // 模组 ID，全局唯一。注册物品/方块/Mixin 时都用这个作为命名空间
    public static final String MOD_ID = "justshulker";

    // 日志器，名字用 MOD_ID 方便在控制台里区分日志来源
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * 模组加载完成时调用（服务端和客户端都会执行一次）
     * 
     * 注意：不要在这里做客户端专属的事情（比如注册 Screen 渲染器），
     * 那些要放到 ClientModInitializer 的 onInitializeClient() 里
     */
    @Override
    public void onInitialize() {
        LOGGER.info("JustShulker initialized!");
    }

    /**
     * 快捷工具方法：构造一个 Identifier
     * 
     * 例子：id("diamond_shulker_box") → Identifier("justshulker", "diamond_shulker_box")
     * 
     * @param path 资源路径（不含命名空间）
     * @return 完整的 Identifier
     */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
