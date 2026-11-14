package ai.neargo.shop.pay.channel.invoke.record;

import ai.neargo.shop.pay.channel.entity.PayChannelCallLog;
import java.util.Optional;

/**
 * {@code pay_channel_call_log} 的读写口。记录器只依赖它：生产是 MyBatis + 独立事务（业务回滚不带走调用记录），
 * 单测是内存实现。
 */
public interface CallLogStore {

    /** 插入一行，返回 id */
    Long insert(PayChannelCallLog row);

    void update(PayChannelCallLog row);

    /** 同一渠道、同一操作、同一我方单号最近一次<b>明确成功</b>的调用 —— 幂等拦截器用 */
    Optional<PayChannelCallLog> lastSucceeded(String payChannel, String operation, String outNo);
}
