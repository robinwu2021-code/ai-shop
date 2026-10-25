package ai.neargo.shop.pay.channel.followup;

import ai.neargo.shop.pay.channel.api.AccessMode;
import java.time.Instant;

/**
 * 一条待补查（{@code pay_channel_followup} 一行 + 发起补查要的那几个调用坐标）。
 *
 * @param version 领取时比对的乐观锁 —— 多实例同时扫到同一条，只有一个领得到
 */
public record FollowUpTask(Long id, long version, String invocationNo, String traceId, String operation,
                           String payChannel, String market, AccessMode accessMode, String outNo, String currency,
                           int attempts, Instant nextAt) {

    /** 领取成功后的样子：领取那一笔已经把版本加了一 */
    public FollowUpTask claimed() {
        return new FollowUpTask(id, version + 1, invocationNo, traceId, operation, payChannel, market, accessMode, outNo,
                currency, attempts, nextAt);
    }
}
