package ai.neargo.shop.pay.acquiring.service;

import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.channel.api.ChannelResult;

/** 按原交易身份关闭渠道单，不能用本地 CLOSED 代替渠道关闭证明（完整方案 AC16）。 */
public interface PaymentAttemptCloseService {
    /** 查原尝试的冻结商户；不能按当前门店绑定或默认商户查询。 */
    ChannelResult query(PayCaller caller, PayOrder order, StlPayment attempt);

    /** 只有渠道明确关成或已关才返回 true；查无此单不能排除尚未发出的下单请求。 */
    boolean close(PayCaller caller, PayOrder order, StlPayment attempt);
}
