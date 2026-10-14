package ai.neargo.shop.channel.notify.port;

import ai.neargo.shop.spi.notify.WxUrlLinkPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * URL Link 桩：**默认启用**（{@code shop.wx.urllink.stub} 默认 true），永远返回空。
 *
 * <p>返回空的含义不是「坏了」，是「这条增强没接」——调用方据此退回 H5 看件页，
 * 发货短信照发。本地与「小程序 urllink 还没开通」的生产都走这个桩。
 */
@Component("wxUrlLinkGateway")
@ConditionalOnProperty(name = "shop.wx.urllink.stub", havingValue = "true", matchIfMissing = true)
public class StubWxUrlLinkGateway implements WxUrlLinkPort {

    @Override
    public Optional<String> generate(String path, String query) {
        return Optional.empty();
    }
}
