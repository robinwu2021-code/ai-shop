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

    /**
     * 挂在这张**子单**上的售后单，取最新的一张；没有则为空。
     *
     * <p>订单详情要用它：C 端「售后进行中」那张卡（连同「填写退货单号」与
     * 「提出申诉」两个动作）判的是**售后单存不存在**，而不是订单状态 ——
     * 订单在售后期间保持原状态，已完成的单照样能申请售后。
     *
     * <p>此前订单详情根本拿不到它（契约声明了 {@code afterSale}、后端不发），
     * 于是整张卡永远不显示。见 TDD-交互清单缺口修复 G15。
     */
    java.util.Optional<AfterSaleVO> ofSubOrder(String subOrderNo);

    AfterSaleVO cancel(String afterSaleNo);

    /**
     * <b>系统发起的全额退款</b>：集单取消、集单截单前买家撤单（TDD-营销-活动统一模型与集单 §1.3 / R3）。
     *
     * <p>为什么不能走 {@link #apply}：它要求买家本人的会话，自营单还会先进平台仲裁 ——
     * 而这里「退不退」已经由商家或规则定了，没有人需要再审一次。
     * 为什么不能只改状态：{@code abortGroup} 就是那么做的，结果是钱一分没退。
     *
     * <p>所以这里只做一件事：替买家建一张「仅退款、全额」的售后单，<b>直接走
     * {@code doRefund}</b> —— 与商家同意、平台裁决、极速退同一条收尾路径
     * （先回退分账再退款、子单转 REFUNDED、发事件）。
     *
     * <p><b>幂等</b>：子单已 REFUNDED 或已有退款中 / 已退款的售后单，直接返回那一张；
     * 买家自己的售后正在处理（APPLIED / ARBITRATING 等）时不另建第二张，也返回它，
     * 由那一张走完。未支付或已取消的子单没有钱可退，返回空。
     *
     * <p><b>循环调用放在调用方的代理上</b>：本类自调用不走事务代理（见 {@link #stuckRefundNos}）。
     */
    java.util.Optional<AfterSaleVO> systemRefund(String subOrderNo, String reason, String label);

    AfterSaleVO shipBack(String afterSaleNo, String company, String expressNo);

    AfterSaleVO escalate(String afterSaleNo, String appeal);

    List<AfterSaleVO> merchantList(String merchantNo, String status);

    /**
     * 同意。**退货退款的「同意」不等于退钱** —— 要等收到货（见实现）。
     *
     * @param refundMinor 商家核定的退款额(分)，可空。**仅已签收可传**（部分退款，AC9）；
     *                    未签收传了按 {@code AFTERSALE_PARTIAL_NEED_RECEIPT} 拒。>实付按 {@code AFTERSALE_AMOUNT_INVALID} 拒。
     * @param refundMode  退款形式，可空，当前仅接受 {@code CASH}；积分返还由独立准备/确认处理。
     */
    AfterSaleVO approve(String merchantNo, String afterSaleNo, String remark,
                        Long refundMinor, String refundMode);

    /** 驳回。{@code remark} 必填 —— 用户要据此决定是否申诉。 */
    AfterSaleVO reject(String merchantNo, String afterSaleNo, String remark);

    /** 确认收到退货（只认 {@code RETURNING}：买家已寄回）→ 触发退款。 */
    AfterSaleVO confirmReturn(String merchantNo, String afterSaleNo);

    /**
     * 在途仅退款（{@code WAIT_RECEIPT}）由商家确认退款（TDD-订单状态机 AC8）。
     * 签收后系统只提醒，<b>不自动退</b>；商家也可以不等签收直接确认。
     */
    AfterSaleVO confirmRefund(String merchantNo, String afterSaleNo);

    /**
     * 子单签收 / 完成时调用：该子单上有 {@code WAIT_RECEIPT} 的仅退款单时，<b>提醒商家确认退款</b>（不退钱）。
     * 幂等；没有这类单时为空操作。系统身份调用。
     */
    void settleOnReceipt(String subOrderNo);

    /**
     * @param reason     买家原话（存库、给商家/运营看）
     * @param reasonCode 原因码（{@code OrdAfterSale.REASONS} 之一），判生鲜等规则用；
     *                   为空时回落把 {@code reason} 当码 —— 老客户端只发 reason
     */
    record ApplyCommand(String type, String reason, List<String> images, Long refundMinor, String reasonCode,
                        List<ItemQty> items) {
        public ApplyCommand(String type, String reason, List<String> images, Long refundMinor) {
            this(type, reason, images, refundMinor, null, null);
        }

        public ApplyCommand(String type, String reason, List<String> images, Long refundMinor, String reasonCode) {
            this(type, reason, images, refundMinor, reasonCode, null);
        }

        /** 买家选的行；null / 空 = 整单剩余（老客户端） */
        public record ItemQty(long itemId, int qty) {
        }

        /** 判规则用的码：有 reasonCode 用它，否则沿用 reason */
        public String ruleReason() {
            return reasonCode != null && !reasonCode.isBlank() ? reasonCode : reason;
        }
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
     * Resume a decided refund outside any transaction: prepare, recover the fixed cash intent, then verify completion.
     * Pending/unknown cash retains REFUNDING; stock, coupons, counters and terminal events wait for trusted proof.
     */
    void resumeRefund(String afterSaleNo, String operatorNo);

    /**
     * 卡在 {@code REFUNDING} 的售后单号（不变式 I5）。
     *
     * <p><b>为什么需要它</b>：{@code doRefund} 的顺序注释写着「① 回退分账，
     * 失败就停在 REFUNDING 等重试」—— 而在此之前<b>没有任何东西在重试</b>：
     * <pre>{@code
     * grep -rln "REFUNDING" shop-*(SLASH)src/main/java | grep -i "job|retry"   # 空
     * }</pre>
     * 于是「等重试」实际是「等到有人发现」，而发现它的通常是用户 ——
     * 他只知道钱没回来。这条与支付域拆分无关，是<b>今天就欠着的账</b>。
     *
     * <p>持久准备公平轮转选择；逐条续跑由 {@link #resumeRefund} 的分阶段事务处理，
     * 而<b>自调用不走代理</b> —— 在本类里循环调它，那个 {@code @Transactional}
     * 一条都不生效，表现是「退款做了一半」而没有任何报错。
     * 所以循环放在 {@code RefundRetryJob} 里，它拿到的是代理。
     *
     * @param stuckBefore 只取这个时刻之前更新过的。留出的这段时间是给正常链路的：
     *                    刚进 REFUNDING 的单可能正被同步流程处理，插进去会撞在同一笔上
     * @param limit       单轮上限。**超出的留待下一轮** —— 积压很多时一次全跑会把退款通道打满
     */
    java.util.List<String> stuckRefundNos(long stuckBefore, int limit);

    /**
     * 已经退款、而<b>分账没有回退</b>的售后单（不变式 I4）。
     *
     * <h2>它是这批不变式里唯一修不了的一条</h2>
     * 别的几条都能自动补：缺结算单就补生成、标记与流水不符就清标记、
     * 预占没了主就退回去。这一条<b>不能</b> —— 钱已经退给买家了，
     * 而商家那一侧的分账没收回来，差额是实打实的损失。
     *
     * <p>能做的只有两件：<b>告警</b>，以及让它出现在人的待办里。
     * 自动「补一次 reverseSplit」听起来对，其实危险：
     * 走到这一步说明第一次回退失败过，而失败的原因通常是
     * <b>分账已过期</b>（通道那边不接受了）—— 那时再调一次只会再失败一次，
     * 而日志里多出一行「已重试」会让人以为有人在处理。
     *
     * <p>{@code doRefund} 的顺序（① 回退分账失败就停，绝不往下走）保证这一类很罕见。
     * <b>所以它一旦不为零就该有人看</b>，而不是等它攒够一批。
     *
     * @param since 只看这个时刻之后退款的
     * @param limit 单轮上限
     */
    java.util.List<String> refundedWithoutSplitReversal(long since, int limit);

    // ------------------------------------------------------------------ 时效（§3）

    /**
     * 商家迟迟不处理的售后单（{@code APPLIED} 且最后动过的时间早于 {@code idleBefore}）。
     *
     * <p><b>只取非自营</b>：归集路径下平台就是卖家，那些单一申请就直接进
     * {@code ARBITRATING}，根本没有「等商家」这一步 —— 把它们算成商家超时，
     * 等于让平台自动同意自己，而平台仲裁是要看材料的。
     *
     * <p>与 {@link #stuckRefundNos} 同一套路：只查不做，逐条处置放在 job 里
     * （自调用不走代理，在本类里循环调带事务的方法，那个事务一条都不生效）。
     */
    java.util.List<String> idlePendingNos(long idleBefore, int limit);

    /**
     * 商家超时未处理（时效 T1）→ <b>提醒商家</b>（到点一次、再过 24h 一次），买家此后可申请平台介入。
     *
     * <p>2026-10-14 起<b>不再替商家同意</b>：退款永远不自动（《退货退款逻辑》v2）。
     */
    void remindOverdue(String afterSaleNo);

    /** 同意退货后买家一直没寄回的单（时效 T3a）：{@code WAIT_RETURN}。 */
    java.util.List<String> unshippedReturnNos(long idleBefore, int limit);

    /** 买家逾期没寄回 → 关闭本次申请。他可以重新申请，但不能让单子永远悬着 */
    void autoCloseUnshipped(String afterSaleNo);

    /** 买家已寄回、而商家迟迟不确认收货的单（时效 T3b）：{@code RETURNING}。 */
    java.util.List<String> unconfirmedReturnNos(long idleBefore, int limit);

    /** 商家逾期不确认收货 → <b>提醒商家</b>，买家可申请平台介入。不再自动退款 */
    void remindUnconfirmedReturn(String afterSaleNo);
}
