package com.jiangyuefengyu.redstonecircuit.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Fabric 这一侧唯一的"配置"按钮。
 *
 * <h2>为什么是 ModMenu</h2>
 * Fabric 自己<b>没有模组列表</b>: 加载器只负责把 jar 挂上去, 界面上那一页是 ModMenu 这类模组加的。所以一个模组想
 * 要"配置"按钮, 只有一条路——实现 {@link ModMenuApi}, 再在 {@code fabric.mod.json} 里把类名写进
 * {@code "modmenu"} 那个入口点: ModMenu 按名字把入口点取出来, 取到就给它加一个按钮, 取不到就不加。这也是为什么
 * 这个类没有第二个用途, 也没有一处 {@code instanceof}: 它只回答"我的配置界面是哪一页"。
 *
 * <h2>只在编译期存在</h2>
 * {@code build.gradle} 里它是 {@code modCompileOnly}({@code libs/modmenu-11.0.5.jar}), 不打包、不声明依赖: 装了
 * ModMenu 的玩家看到按钮, 没装的人<b>根本走不到这个类</b>——{@code "modmenu"} 这个入口点只有 ModMenu 会来取, 游戏
 * 自己也从不加载它, 所以少一个模组不会多一条报错, 更不会让客户端起不来。
 */
@Environment(EnvType.CLIENT)
public final class RedstoneCircuitModMenu implements ModMenuApi {

    /**
     * 交给 ModMenu 的是<b>工厂</b>而不是一页界面: ModMenu 把打开它自己的那块界面传进来, 配置页关掉时回到那里,
     * 所以每一次点开都是新的一页(读的是文件里此刻的值), 而不是一个被反复展示的旧对象。
     */
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new ConfigScreen(parent);
    }
}
