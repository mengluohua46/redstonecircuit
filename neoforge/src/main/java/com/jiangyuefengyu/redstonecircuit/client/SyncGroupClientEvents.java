package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * 断线时把同步火把的组忘掉, 客户端专用.
 *
 * <p>一个类而不是在 {@link SyncGroupView} 上加一个订阅: 那个类会被服务器上的包处理路径提到名, 而本类引用了
 * {@code ClientPlayerNetworkEvent} - 那是只有客户端才有的类型。分开之后, 专用服务器永远不必加载本类 (NeoForge 只在
 * {@code Dist.CLIENT} 上注册它), 与 {@code StructureScreens} 分开的理由是同一个。
 *
 * <p>不清的后果是具体的: 组名单里留着上一个世界的位置, 换一个世界进同一个维度键时, 那些格子会被画成某个频道的颜色。
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = RedstoneCircuit.MODID, value = Dist.CLIENT)
public final class SyncGroupClientEvents {

    private SyncGroupClientEvents() {
    }

    /** 世界没加载, 里面的一组火把也就没有意义。 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SyncGroupView.clear();
    }
}
