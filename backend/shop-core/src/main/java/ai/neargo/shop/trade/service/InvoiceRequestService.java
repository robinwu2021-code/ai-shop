package ai.neargo.shop.trade.service;

import java.util.List;

/**
 * 开票申请：**平台开给消费者**（ADR-017 §3.4 条件 2）。
 *
 * <p><b>为什么这一版是手工开票。</b>
 * 归集路径要成立，四个必要条件缺一不可，第二条就是「平台开票给消费者」。
 * 而 C 端此前<b>零入口</b> —— 只有下单前一句「本商家无法开具发票」，
 * 连申请的地方都没有。<b>没有入口 = 没有履行途径</b>，那是实质性缺失。
 *
 * <p>接票据系统（数电票/税控）是独立项目，等它就是无限期挂着。
 * 但条件 2 要的是「平台承担开票义务并<b>实际履行</b>」，不要求自动化：
 * 手工开票 + 可追溯的申请记录，法律关系上成立。
 * 单量小的时候完全扛得住 —— <b>扛不住那天，正是该接系统的信号</b>。
 */
public interface InvoiceRequestService {

    /**
     * 消费者申请开票。
     *
     * <p>三条边界：
     * <ul>
     *   <li><b>只有已支付的订单能申请</b>：未付款的单没有交易，开票无从谈起</li>
     *   <li><b>一张订单只能申请一次</b>：重复申请 = 重复开票 = 一笔交易两张票，
     *       那是税务问题不是体验问题。改抬头走「驳回后重申请」</li>
     *   <li>金额<b>落快照</b>：后续退款会改订单金额，而已开的票不会跟着变</li>
     * </ul>
     */
    InvoiceRequestVO apply(ApplyCommand cmd);

    /** 我的开票申请。C 端订单详情据此显示「已申请 / 已开具 / 被驳回」。 */
    List<InvoiceRequestVO> mine();

    /** 某单的申请，没有则空。C 端据此决定显示「申请发票」还是状态。 */
    InvoiceRequestVO ofOrder(String orderNo);

    /** 运营：待处理与历史。 */
    List<InvoiceRequestVO> list(String status, int page, int size);

    /**
     * 运营开完票回填。
     *
     * @param invoiceNo 票号。**没有票号的「已开具」等于没开** —— 消费者拿不到凭证，
     *                  事后也查不到到底开没开
     */
    InvoiceRequestVO markIssued(String requestNo, String invoiceNo, String operatorNo);

    /**
     * 驳回。
     *
     * @param reason 必填。不写原因的驳回等于让消费者再猜一遍抬头哪里错了
     */
    InvoiceRequestVO reject(String requestNo, String reason, String operatorNo);

    /**
     * @param titleType PERSONAL 个人 / COMPANY 单位
     * @param taxNo     单位抬头必填
     * @param email     电子票只能发到这里，填错就是开了也收不到
     */
    record ApplyCommand(String orderNo, String titleType, String title, String taxNo, String email) {
    }

    record InvoiceRequestVO(String requestNo, String orderNo, String titleType, String title,
                            String taxNo, String email, long amountMinor, String status,
                            String invoiceNo, Long issuedAt, String rejectReason,
                            Long createdAt) {
    }
}
