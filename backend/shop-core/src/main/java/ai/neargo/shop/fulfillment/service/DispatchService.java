package ai.neargo.shop.fulfillment.service;

import ai.neargo.shop.fulfillment.dto.ArrivalBatchVO;
import ai.neargo.shop.fulfillment.dto.OverdueRuleVO;
import ai.neargo.shop.fulfillment.dto.RedeemStatVO;
import ai.neargo.shop.fulfillment.dto.SortingRowVO;

import java.util.List;

/**
 * 平台侧履约调度（P-5.1）。**跨商家、跨自提点的汇总视角** ——
 * B 端只看自己的货，平台看一个点上所有商家的货。
 *
 * <h2>它不做什么</h2>
 *
 * <p><b>不写订单状态。</b>到货登记与核销都在 B 端履约台（{@code PickupService}），
 * 经 {@code FulfillmentQueryPort} 走 trade 的状态机。平台代签的后果是
 * 「平台说到货了、站长没见到货」，而买家已经收到「可以来取了」——
 * 签收动作必须发生在货真的在的那一端。
 *
 * <p><b>不存计数器。</b>件数、待核销数、逾期数全部现算自 {@code ord_sub_order}。
 * 另存一份的代价是「总览说 3 单、点进去只有 2 单」（B-6.0），
 * 而这种不一致既不报错也无从复现。
 */
public interface DispatchService {

    /**
     * 到货批次列表（P-5.1.1）。
     *
     * <p><b>读时补齐</b>：有未完成自提单的「自提点 × 到货日」若还没有批次行，就地建一条
     * （PLANNED / 车次「待派」）。不做定时任务的理由见 TDD §4.3 ——
     * 一个只在半夜跑的补齐任务，会让上午开城的新点当天在看板上完全不存在。
     */
    List<ArrivalBatchVO> batches(String communityNo, String pickupNo, String status, String keyword);

    /**
     * 推进批次状态。<b>只能沿 计划→发车→到货→签收 走一步</b>，跳步抛
     * {@code BATCH_TRANSITION_ILLEGAL}。
     *
     * <p>签收是这条链上唯一有对外后果的动作：这批货签收之后才进入分拣汇总视图。
     */
    ArrivalBatchVO setBatchStatus(String batchNo, String status, String operatorNo);

    /**
     * 按自提点汇总分拣（P-5.1.2）。<b>只看已签收批次覆盖到的点</b>。
     *
     * @param pickupNo 限定自提点；空 = 全部
     */
    List<SortingRowVO> sorting(String pickupNo);

    /**
     * 核销监控与逾期看板（P-5.1.3）。逾期的判据取自 {@link #overdueRule()} 的宽限小时数。
     */
    List<RedeemStatVO> redeemStats(String pickupNo);

    /** 逾期规则（P-5.1.4）。没配过时返回默认值 —— 参数少一行不该让整个页面打不开。 */
    OverdueRuleVO overdueRule();

    /**
     * 保存逾期规则。
     *
     * <p><b>宽限期 &lt; 1 小时直接拒</b>：到点即作废必产生客诉，
     * 宽限期是规则不是建议。VOID 也一样要留宽限期。
     */
    OverdueRuleVO saveOverdueRule(String action, int graceHours, int maxPostpone, String operatorNo);
}
