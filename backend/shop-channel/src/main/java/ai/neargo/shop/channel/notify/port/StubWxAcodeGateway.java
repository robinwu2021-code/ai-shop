package ai.neargo.shop.channel.notify.port;

import ai.neargo.shop.spi.user.WxAcodePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 小程序码桩：<b>不生成图，返回 null</b>。默认启用（{@code shop.wx.acode.stub} 跟随
 * {@code shop.wx.stub}）。
 *
 * <p><b>为什么返回 null 而不是一张占位图</b>：占位图会被商家当成真码印到包装袋上。
 * 这正是这次要修的那个错的形状 —— 假物料比没有物料更贵，
 * 因为它要等到印完几百张贴纸、老客扫不出来才暴露。
 *
 * <p>{@link #enabled()} 返回 false，端上据此显示「稍后再来」而不是一张坏图。
 */
@Component("wxAcodeGateway")
@ConditionalOnProperty(name = "shop.wx.acode.stub", havingValue = "true", matchIfMissing = true)
public class StubWxAcodeGateway implements WxAcodePort {

    @Override
    public byte[] unlimited(String scene, String page) {
        return null;
    }

    @Override
    public boolean enabled() {
        return false;
    }
}
