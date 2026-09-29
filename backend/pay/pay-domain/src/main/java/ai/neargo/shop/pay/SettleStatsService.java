package ai.neargo.shop.pay;

import java.util.List;

/**
 * 结算口径的多维统计（TDD-供应商结算与双轨资金 §2.1 / §3.2）。
 *
 * <p><b>与 {@code DashboardService.storeRanking} 不是一回事，所以不复用它</b>：
 * 那个读订单（GMV、单数、退款率），是最近 N 天的 Top N 排行；
 * 这里读结算单（成交额、佣金、服务费、渠道费、净额），是按日期区间的全量，
 * 且三个维度可切换。两个问题不同 ——「哪家店卖得好」看那个，
 * 「这段时间各家该结多少」看这个，而两者的金额口径也不同
 * （GMV 没扣佣金与手续费）。
 *
 * <h2>三条不可违反的口径规则</h2>
 *
 * <ol>
 *   <li><b>一律按快照列聚合，绝不 join 回主数据。</b>门店可以换挂主体、
 *       商家可以改收款号 —— 实时 join 会让历史流水跟着改口径：
 *       上个月这家店属于 A 主体、这个月改挂 B，join 出来的「A 主体上月营收」
 *       会凭空消失。这与 {@code businessMode} / {@code payMerchantNo} 用快照
 *       是同一条理由。<b>展示名可以查</b>，那是给人看的一层，不参与聚合。</li>
 *   <li><b>{@code store_no} 可能为空</b>（存量的主体级流水）。按门店聚合时
 *       空值<b>单独成一组</b>，不能悄悄丢掉 —— 丢掉的后果是
 *       门店合计 ≠ 主体合计，而看数的人找不出差在哪。</li>
 *   <li><b>{@link Dim#PAY_MERCHANT} 是资金维度，不是经营维度。</b>
 *       按它聚合回答的是「这个账户该收多少钱」，<b>不能</b>用来回答
 *       「这家店挣了多少」—— {@code StlBill.storeNo} 的注释写的就是这条界线。</li>
 * </ol>
 *
 * <h2>覆盖范围（页面上必须写明）</h2>
 *
 * <p><b>不含退款。</b>{@code stl_bill} 上没有退款列 —— 退款走售后与分账回退，
 * 是另一条链路。所以这里的「成交额」是结算单口径的计提额，
 * 不写明的话，「这个数比订单后台小」会被当成缺陷来报。
 */
public interface SettleStatsService {

    /** 统计维度。**做成枚举不是字符串** —— 字符串维度会让「传错维度名」变成运行时空结果 */
    enum Dim {
        /** 门店。纯经营维度 */
        STORE,
        /** 主体（一张营业执照） */
        ENTITY,
        /** 收款商户号。**资金维度**，见类注释规则 3 */
        PAY_MERCHANT
    }

    /** 空门店的占位键。见类注释规则 2 —— 它要占一行，不能被丢掉 */
    String UNASSIGNED = "__UNASSIGNED__";

    /**
     * @param dim           聚合维度
     * @param fromMillis    起（含）。按 {@code accrued_at} 计提时间，也就是<b>成交日</b> ——
     *                      商家心里的「今天赚了多少」是这个，不是可结算日或应结日
     * @param toMillis      止（含）
     * @param businessMode  经营模式过滤，空则不限（{@code SELF_OPERATED} / {@code THIRD_PARTY}）
     */
    List<StatRow> stats(Dim dim, long fromMillis, long toMillis, String businessMode);

    /**
     * 一行 = 一个维度值。
     *
     * @param dimKey    维度值（门店号 / 主体号 / 收款商户号）。空门店为 {@link #UNASSIGNED}
     * @param billCount 结算单数。**只给金额的话，看不出「一笔大的还是很多笔」**
     */
    record StatRow(String dimKey, long grossMinor, long commissionMinor,
                   long serviceFeeMinor, long channelFeeMinor, long netMinor,
                   int billCount) {
    }
}
