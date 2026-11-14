package ai.neargo.shop.pay.channel.http;

import ai.neargo.shop.pay.channel.entity.PayRawPayload;

/** {@code pay_raw_payload} 的写口。生产是 MyBatis + 独立事务（同调用记录），单测是内存实现 */
public interface RawPayloadStore {

    Long insert(PayRawPayload row);
}
