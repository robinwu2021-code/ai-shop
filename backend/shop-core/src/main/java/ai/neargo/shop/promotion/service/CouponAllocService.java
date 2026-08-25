package ai.neargo.shop.promotion.service;

import ai.neargo.shop.spi.marketing.CouponPort;

import java.util.List;

/**
 * 新模型的券在下单时怎么算、怎么核销（P4）。
 *
 * <p><b>不直接实现 {@link CouponPort}</b>：老模型的实现还在，两个 Bean 同时实现同一个
 * Port 会让注入点变成「看运气」。谁来接这一单由 app 层的路由决定
 * （{@code portal.port.CouponPortRouter}）—— 它按<b>这张券在哪张表里</b>分流，
 * 而不是按一个全局开关。数据在哪就走哪条路，比开关安全：
 * 开关切错的那一刻，用户手上一整类券会同时失效。
 */
public interface CouponAllocService {

    /** 这张用户券是不是新模型的（路由靠它分流） */
    boolean owns(String userNo, String userCouponNo);

    /** 与 {@link CouponPort#allocate} 同义，只是读的是 {@code pmt_*} */
    CouponPort.Allocation allocate(String userNo, String userCouponNo,
                                   List<CouponPort.MerchantAmount> groups);

    /**
     * 下单成功后占用这张券，并记一行 {@code pmt_apply}。
     *
     * @param allocation 这一单实际减掉多少。<b>不在这里重算</b> ——
     *                   重算依赖的规则会变，而这一行记的是「当时减了多少」
     */
    void markUsed(String userNo, String userCouponNo, String orderNo,
                  ai.neargo.shop.spi.marketing.CouponPort.Allocation allocation);

    /** 订单取消/关闭时退回，并把那一行 {@code pmt_apply} 标记为已撤销 */
    void release(String orderNo);
}
