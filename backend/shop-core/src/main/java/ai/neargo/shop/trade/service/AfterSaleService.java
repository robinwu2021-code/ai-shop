package ai.neargo.shop.trade.service;

import ai.neargo.shop.trade.dto.AfterSaleVO;
import ai.neargo.shop.trade.dto.OpsAfterSaleVO;

import java.util.List;

/** 售后（[API 清单 §2.6 / §3.7]）。**子单粒度**：一次售后只针对一个商家。 */
public interface AfterSaleService {

    List<String> reasons();

    AfterSaleVO apply(String subOrderNo, ApplyCommand cmd);

    List<AfterSaleVO> myList();

    AfterSaleVO detail(String afterSaleNo);

    AfterSaleVO cancel(String afterSaleNo);

    AfterSaleVO shipBack(String afterSaleNo, String company, String expressNo);

    AfterSaleVO escalate(String afterSaleNo, String appeal);

    List<AfterSaleVO> merchantList(String merchantNo, String status);

    /** 同意。**退货退款的「同意」不等于退钱** —— 要等收到货（见实现）。 */
    AfterSaleVO approve(String merchantNo, String afterSaleNo, String remark);

    /** 驳回。{@code remark} 必填 —— 用户要据此决定是否申诉。 */
    AfterSaleVO reject(String merchantNo, String afterSaleNo, String remark);

    /** 确认收到退货 → 触发退款。 */
    AfterSaleVO confirmReturn(String merchantNo, String afterSaleNo);

    record ApplyCommand(String type, String reason, List<String> images, Long refundMinor) {
    }

    /**
     * 待商家处理的售后单数（工作台待办）。
     *
     * <p>只数 {@code APPLIED} —— 那是**球在商家手里**的唯一状态。
     * 把 REFUNDING（等买家寄回）也算进去的话，待办数会一直挂着一个商家做不了什么的数字，
     * 而挂久了整块待办就没人看了。
     */
    int merchantPendingCount(String merchantNo);

    // ---------------------------------------------------------------- 平台仲裁（P-6.1）

    /** 平台侧售后列表。{@code status} 为空给全部。 */
    List<OpsAfterSaleVO> opsList(String status, String merchantNo);

    /**
     * 平台裁决（{@code ARBITRATING} 的出口）。
     *
     * <p>此前只有入口没有出口：用户能把争议上升到平台（{@code escalate}），
     * 而平台没有任何接口能裁 —— 单子停在 ARBITRATING，用户和商家都在等一个不会来的结果。
     *
     * @param refund    true = 支持用户（推进到退款），false = 维持商家决定（关闭）
     * @param liability 责任方 MERCHANT / USER / PLATFORM。**裁决必须落责任**，
     *                  否则赔付出资比例无从算起（M4 口径未定，但责任本身要记）
     * @param verdict   裁决说明，<b>必填</b> —— 用户与商家都会看到
     */
    OpsAfterSaleVO arbitrate(String afterSaleNo, boolean refund, String liability, String verdict,
                             String operatorNo);

    /**
     * 把一笔停在 {@code REFUNDING} 的售后单走完退款（矩阵 P-12.1.5，E4）。
     *
     * <p><b>为什么需要它</b>：{@link #arbitrate}{@code (refund=true)} 只把状态推到
     * {@code REFUNDING}，钱一分没退 —— 单子停在那里等一个不会来的动作。
     * 而它对应的子单可能已经分过账，所以收尾必须走「先回退分账再退款」那条唯一路径。
     *
     * <p>本方法<b>只是 {@code doRefund} 的入口</b>，不重写收尾逻辑：
     * 子单转态、{@code AfterSaleRefunded} 事件、下游回补库存与评分都挂在那里。
     * 财务侧（{@code /ops/refund-split-backs/{no}/execute}）通过
     * {@code RefundSplitBackPort} 调它。
     *
     * <p><b>幂等</b>：已 {@code REFUNDED} 直接返回 —— 运营看到列表没刷新会再点一次。
     *
     * @throws ai.neargo.shop.common.BizException 状态不允许，或分账回退失败
     *         （后者是 {@code SPLIT_EXPIRED}，此时<b>退款不会发生</b>）
     */
    void resumeRefund(String afterSaleNo, String operatorNo);
}
