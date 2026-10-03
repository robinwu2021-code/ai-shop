package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.dto.PointsVOs.MerchantPointAccountVO;

/**
 * B 端积分的 app service。
 *
 * <h2>它存在的理由只有一个方法</h2>
 * {@link #toggleMerchant} 要做两件事：<b>改商家的积分开关</b>（商家域）
 * 与<b>读积分账户</b>（支付域）。此前这个编排在 {@code PointsServiceImpl} 里，
 * 靠 {@code MerchantAdminPort} 反向调商家域 ——
 * 而「改商家的一个开关」显然不是支付域该做的事。
 *
 * <p>按「除回调外不做反向依赖，pay 只解决 pay 的核心问题」，
 * 跨两个域的编排属于它们之上的这一层。
 */
public interface BizPointsAppService {

    /**
     * 开/关这个商家的积分。
     *
     * <p><b>关闭只影响将来</b>：不动已发出的分，也不退已扣的服务费 ——
     * 否则关一次开关就是一次资金事故。（这句话从原实现搬过来，它仍然成立。）
     */
    MerchantPointAccountVO toggleMerchant(String merchantNo, boolean enabled);
}
