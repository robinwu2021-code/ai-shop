package ai.neargo.shop.settle;

import ai.neargo.shop.settle.dto.RateCardVO;
import ai.neargo.shop.settle.dto.SettleBillVO;

import java.util.List;

/** 结算与分账（[API 清单 §3.10 / §4.12]）。 */
public interface SettleService {

    /** 支付成功后按子单生成结算单。**幂等**：事件重投不会重复生成。 */
    int generateForOrder(String orderNo);

    /** 执行分账。**幂等**：重复执行不会重复打款。 */
    void executeSplit(String settleNo);

    /**
     * 分账回退（售后退款前必须先做）。
     *
     * @return false 表示回退失败、已转人工。**调用方必须据此停止退款** ——
     *         钱已经分给商家了还退给用户，平台就要垫付这笔差额
     */
    boolean reverseSplit(String subOrderNo);

    /** 原路退款。返回退款单号 */
    String refund(String subOrderNo, long amountMinor, String reason);

    /**
     * @param storeNos 门店作用域。<b>空集合不等于不过滤</b> —— 与订单侧同一个越权陷阱；
     *                 单店主体一律不收窄（存量流水没有 store_no，一筛就全没了）
     */
    List<SettleBillVO> merchantBills(String merchantNo, java.util.Collection<String> storeNos);

    SettleBillVO merchantBill(String merchantNo, String settleNo);

    RateCardVO rateCard();

    /** 分账日志条数（按动作）。测试与运营排查用。 */
    long splitLogCount(String settleNo, String action);
    // ---------------------------------------------------------------- 自营应付账款（P0-7）

    /**
     * 平台侧应付账款列表。**跨供应商**——运营要看到所有人的账。
     *
     * @param status   为空给全部；{@code PENDING_RECON} 就是待对账队列
     * @param entityNo 为空给全部供应商
     */
    List<SettleBillVO> opsPayables(String status, String entityNo);

    /**
     * 对账确认：{@code PENDING_RECON → CONFIRMED}。
     *
     * <p>确认的含义是「双方认这个数」，之后金额不该再变。
     * 与「付款」分开是必要的——对账在前、收票在中、付款在后，
     * 合成一步的话，票还没到就把钱付了。
     */
    SettleBillVO confirmRecon(String settleNo, String operatorNo);

    /**
     * 登记已付：{@code CONFIRMED → PAID}。
     *
     * <p><b>系统只登记不划转</b>——自营的打款是财务在网银执行的，
     * 让业务系统去动公司对公账户是财务内控问题，不是技术能力问题。
     *
     * <p><b>票到付款</b>：进项票未核验通过的单**不允许登记付款**。
     * 先款后票的代价很具体：钱付完了供应商开票的动力就没了，
     * 而平台没有发票就无法列支，等于付了钱还多缴税。
     *
     * @param paymentRef 网银流水号，**必填**——没有凭证号的「已付」等于没记
     */
    SettleBillVO markPaid(String settleNo, String paymentRef, String operatorNo);

    /**
     * 标记为无票供应商：{@code invoice_status → NO_INVOICE}，之后可直接付款。
     *
     * <p><b>为什么是「标出」而不是「禁止付款」</b>：现实中总会有例外
     * （历史遗留、特殊供应商），禁止会逼人绕过系统；标出则让每一笔例外都是
     * <b>被看见的</b>——财务在付款前就知道「这笔付出去是不能税前列支的」，
     * 而不是月末报税时才发现。
     *
     * @param reason 必填，写进审计。无票是要付出税务代价的，得说得出为什么
     */
    SettleBillVO markNoInvoice(String settleNo, String reason, String operatorNo);

}
