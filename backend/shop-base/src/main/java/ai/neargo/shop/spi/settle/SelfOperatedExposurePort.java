package ai.neargo.shop.spi.settle;

import java.util.Collection;
import java.util.Map;

/**
 * 自营结算敞口：某些主体在<b>自营</b>模式下已经产生了多少结算单与金额。
 *
 * <p><b>为什么单开一个口，而不是让商家域自己查结算表</b>：结算是另一个模块，
 * 而这个数的口径（哪些单算数、金额取哪一列）只有结算域说了算。
 *
 * <p><b>它服务的问题是税务敞口。</b>自营意味着平台是销售主体，
 * 要取得进项发票才能列支成本 —— 而没有营业执照的主体开不出票。
 * 「无照主体 × 自营 × 已结算金额」这个数，就是<b>不可税前扣除的成本规模</b>。
 * 运营在处置之前必须先看得见它有多大。
 *
 * <p>口径刻意用 {@code stl_bill.business_mode}（下单时的<b>快照</b>）
 * 而不是门店当前的模式：门店改了模式，历史单的税务性质<b>不会跟着改</b>。
 */
public interface SelfOperatedExposurePort {

    /**
     * 批量取指定主体的自营结算敞口。
     *
     * @param entityNos 主体编号；空集合返回空 Map
     * @return 只包含<b>确实有自营结算单</b>的主体。
     *         没有单的主体<b>不出现在结果里</b>，而不是给一个 0 值条目 ——
     *         调用方据此区分「查过了，没有」与「有，但是 0」，
     *         这两件事在风险清单上要显示成不同的东西
     */
    Map<String, Exposure> selfOperatedExposure(Collection<String> entityNos);

    /**
     * @param billCount   自营结算单数
     * @param amountMinor 累计金额（分）。用结算金额而不是订单金额 ——
     *                    前者才是实际要付给供应商、需要进项票的那笔钱
     */
    record Exposure(long billCount, long amountMinor) {
    }
}
