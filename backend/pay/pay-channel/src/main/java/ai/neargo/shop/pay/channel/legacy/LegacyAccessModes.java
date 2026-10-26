package ai.neargo.shop.pay.channel.legacy;

import ai.neargo.shop.pay.channel.AlipayPayGateway;
import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.WechatDirectPayGateway;
import ai.neargo.shop.pay.channel.WechatPayGateway;
import ai.neargo.shop.pay.channel.api.AccessMode;

/**
 * 现有网关是哪种接入模式。此前靠 {@code shop.pay.wechat.mode} 二选一装配，模式藏在「装的是哪个类」里；
 * 新管线按（渠道 × 接入模式）路由，所以要把它说出来。只此一处，随网关拆成处理器一起消失。
 */
public final class LegacyAccessModes {

    private LegacyAccessModes() {
    }

    public static AccessMode of(PayGateway gateway) {
        if (gateway instanceof WechatDirectPayGateway) {
            return AccessMode.MERCHANT_OWN;
        }
        if (gateway instanceof WechatPayGateway || gateway instanceof AlipayPayGateway) {
            return AccessMode.SERVICE_PROVIDER;
        }
        // 测试、stub、免单：平台自己的账户，没有外部商户身份
        return AccessMode.PLATFORM_SUB;
    }
}
