package ai.neargo.shop.channel.notify.port;

import ai.neargo.shop.spi.trade.WxShippingPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 发货信息录入的桩：只记日志，恒成功。
 *
 * <p><b>{@link #enabled()} 返回 false</b>，而 {@link #upload} 返回成功 ——
 * 两件事分开是有意的：上报链路要能在没接通道时整条跑通（台账、状态机、补报任务
 * 都该被测到），而运营与排查的人必须能一眼看出「这个号根本还没开通」。
 * 让 enabled 也返回 true 的话，「一直没结到钱」会被查成通道故障，
 * 而真相是从来没接过。
 */
@Component
@ConditionalOnProperty(name = "shop.wx.shipping.stub", havingValue = "true", matchIfMissing = true)
public class StubWxShippingGateway implements WxShippingPort {

    private static final Logger log = LoggerFactory.getLogger(StubWxShippingGateway.class);

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Result upload(Command cmd) {
        log.info("[wxship-stub] 发货上报（未真发）outTradeNo={} type={} desc={}",
                cmd.outTradeNo(), cmd.logisticsType(), cmd.itemDesc());
        return Result.ok();
    }
}
