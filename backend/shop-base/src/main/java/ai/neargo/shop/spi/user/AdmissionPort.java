package ai.neargo.shop.spi.user;

import java.util.function.LongSupplier;

/**
 * 弱主体准入闸门：保证金 / 限品类 / 限额。
 *
 * <p>平台无仓、不碰货，「自营」只是资质代持的外壳。准入矩阵里最弱的一档
 * （S3 = {@code legal_form=MICRO}）<b>没有「入平台仓让平台验货」这条出路</b>——
 * 那个仓不存在。平台在法律上是销售主体、承担全部产品责任，
 * 却没有任何货物控制手段；这个缺口只能用准入和钱去补。
 *
 * <p>做成 Port 而不是让商品域、交易域直接读 {@code mch_*} 表：
 * 那两个域要问的是「这家能不能卖 / 这单能不能下」，
 * 不该因此获得读写商家账户的能力。
 *
 * <p><b>两个方法都是「不通过就抛」而不是返回 boolean</b>：
 * 返回 boolean 的话，调用方漏判一次就是一次静默放行，
 * 而这里每一次静默放行都对应平台的一笔无上限敞口。
 */
public interface AdmissionPort {

    /**
     * 上架准入：保证金是否足额、该类目本档位是否禁售。
     *
     * @param categoryNeedsQualification 该类目是否挂了资质要求。
     *                                   由调用方传入而非本 Port 自查——类目树属于商品域，
     *                                   商家域跨过去读就把依赖方向倒转了。
     */
    void requireListingAllowed(String merchantNo, String categoryNo, boolean categoryNeedsQualification);

    /**
     * 下单准入：单笔限额 + 日累计限额。
     *
     * <p>日累计必须连同单笔一起判：只卡单笔的话，
     * 拆成十单就绕过去了，而平台的敞口是按天累计的。
     *
     * @param todayPaidMinor 该商户当日已成交额，<b>由交易域惰性提供</b>。
     *                       订单表属于交易域，商家域跨过去读会把依赖方向倒转；
     *                       用 {@link LongSupplier} 而非直接传值，是为了让
     *                       「本档位不限日累计」的绝大多数商户<b>根本不触发那次聚合查询</b>。
     */
    void requireOrderAllowed(String merchantNo, long amountMinor, LongSupplier todayPaidMinor);
}
