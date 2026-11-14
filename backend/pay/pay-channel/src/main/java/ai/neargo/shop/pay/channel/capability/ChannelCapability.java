package ai.neargo.shop.pay.channel.capability;

import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.PayErrorCode;

/**
 * 一家渠道 × 一种接入模式的能力实现（复核 §3.2 W2；TDD-支付服务-W2渠道层补完 S1）。
 *
 * <p>本接口只说「我是谁」；能做什么由实现了下面哪几个子接口决定：
 * {@link OnlinePayCapability} · {@link OfflinePayCapability} · {@link CardPayCapability} · {@link TransactionCapability} ·
 * {@link OnboardingCapability} · {@link StatementCapability} · {@link PayoutCapability} · {@link SplitCapability}。
 * {@link CapabilityHandler} 从这些接口<b>派生</b>处理器的键 —— 不另写一份清单，清单与实现就不会走岔。
 *
 * <p>与 {@code ChannelHandler} 同样的约束：<b>不重试、不写库、不打业务日志</b>，无请求级可变状态（同一实例并发服务所有商户，
 * {@code GatewayStatelessTest} 守）。凭据按调用上下文取，不放在字段里随请求改。
 */
public interface ChannelCapability {

    /** 渠道码，与 {@code sys_pay_channel.pay_channel} 同值 */
    String payChannel();

    AccessMode accessMode();

    /** 只在某个地区生效时覆写；null = 任意地区 */
    default String market() {
        return null;
    }

    /** 实现了某个能力接口、但这一项操作渠道没有时的统一答复：明确失败、不可重试、没发出去 */
    static ChannelResult unsupported(ChannelOperation operation) {
        return new ChannelResult.Failed(PayErrorCode.CHANNEL_UNSUPPORTED, null, "渠道不支持 " + operation, false);
    }
}
