package ai.neargo.shop.settle;

import ai.neargo.shop.settle.dto.PurchaseInvoiceVO;
import ai.neargo.shop.settle.dto.StatementVO;
import ai.neargo.shop.settle.dto.RateCardVO;
import ai.neargo.shop.settle.dto.SettleBillVO;

import java.util.List;

/** 结算与分账（[API 清单 §3.10 / §4.12]）。 */
public interface SettleService {

    /** 支付成功后按子单生成结算单。**幂等**：事件重投不会重复生成。 */
    int generateForOrder(String orderNo);

    /**
     * <b>通道回执确认分账已到账。</b>把 {@code SPLIT} 推到终态 {@code SPLIT_CONFIRMED}。
     *
     * <p>⚠️ <b>这是进入终态的唯一入口，并且只该由回执调用。</b>
     * 不给运营端、不给人工按钮 —— 与提现单 {@code APPROVED → PAID} 同一条规矩：
     * 让人手动把单子做平，之后对账差额永远说不清是「通道慢了」还是「有人点早了」。
     *
     * <p><b>幂等</b>：已经是终态的重复调用直接返回，不重复写时间戳 ——
     * 回执会重投，而 {@code split_confirmed_at} 被改晚会让对账把一条正常单
     * 算成「发出很久才确认」。
     *
     * @param channelRef 通道那边的分账单号，用于对账时回溯
     * @return 是否真的发生了状态迁移（false = 本来就是终态，或这单压根没分过账）
     */
    boolean confirmSplit(String settleNo, String channelRef);

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
     * 平台端 · 全部结算单（P2-9）。
     *
     * <p>与 {@link #opsPayables} 的差别是<b>范围</b>：那个只看自营（第三方的钱走分账，
     * 不存在「应付账款」这件事），这个两条轨道都看 —— 运营要的是「这家店的钱到哪一步了」，
     * 而那不该因为经营模式不同就分成两个入口去查。
     */
    List<SettleBillVO> opsBills(String status, String entityNo, String businessMode);

    /**
     * 平台端 · 分账指令流水（P2-9）。
     *
     * <p>结算单说的是「该给多少」，流水说的是「发了几条指令、成没成、失败在哪」——
     * 出问题时要看的是后者。此前它只存在于库里，运营端**没有任何入口**。
     */
    List<SplitLogVO> opsSplitLogs(String settleNo, String action);

    /**
     * @param amountMinor 该指令的金额。<b>补差与分账口径不同</b>：
     *                    分账动的是平台应收，补差动的是买家用积分抵掉的那部分
     * @param result      SUCCESS / FAIL
     */
    record SplitLogVO(String settleNo, String subOrderNo, String splitAction,
                      long amountMinor, String result, String requestNo,
                      String providerNo, String message, long createdAt) {
    }

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

    // ---------------------------------------------------------------- 进项票（P0-8/10）

    /**
     * 供应商提交进项票（B 端）。一张票覆盖该周期**全部已对账待开票**的单。
     *
     * <p><b>金额必须等于这批单的应付合计</b>——这是最实际的一条防错：
     * 开票金额与应付对不上，多半是周期选错或漏了几单，
     * 而这种错到报税时才发现就晚了。
     */
    PurchaseInvoiceVO submitInvoice(String merchantNo, SubmitInvoiceCommand cmd);

    /** 供应商看自己提交过的票（B 端），含驳回原因。 */
    List<PurchaseInvoiceVO> myInvoices(String merchantNo);

    /** 平台待核验队列。{@code status} 为空给全部。 */
    List<PurchaseInvoiceVO> opsInvoices(String status);

    /**
     * 核验通过。
     *
     * <p><b>会做三流一致的机器比对</b>：开票方名称必须等于供应商主体名。
     * 不一致会被认定为虚开风险，后果远大于少抵一点税，而「个体户用法人个人名义开票」
     * 这类不一致肉眼很容易放过。
     *
     * <p>⚠️ 资金流那一环（结算账户户名）**机器比对不了**——库里只存了账户掩码，
     * 没存户名。这一项仍需人工核对，接口不假装它已经查过。
     */
    PurchaseInvoiceVO verifyInvoice(String invoiceNo, String operatorNo);

    /** 驳回。{@code reason} 必填——供应商得知道是抬头错了、金额不符还是影像看不清。 */
    PurchaseInvoiceVO rejectInvoice(String invoiceNo, String reason, String operatorNo);

    /**
     * @param period        结算周期，如 {@code 2026-08}
     * @param invoiceType   {@code GENERAL} 普票 / {@code SPECIAL} 专票
     * @param titleName     开票方名称。三流比对用
     * @param amountMinor   价税合计（分）
     */
    record SubmitInvoiceCommand(String period, String invoiceCode, String invoiceNumber,
                                String invoiceType, String titleName, String titleTaxNo,
                                long amountMinor, long taxAmountMinor, int taxRate,
                                Long invoiceDate, String imageUrl) {
    }

    // ---------------------------------------------------------------- 平台开票信息（P0-11）

    /**
     * 平台的开票抬头。供应商照着它开票。
     *
     * <p>落 {@code sys_setting} 而不是建表：它是**一组参数**不是一批记录，
     * 与费率、频控、售后极速退阈值同类。
     */
    java.util.Map<String, String> platformInvoiceTitle();

    /** 保存平台开票信息（运营端）。公司全称与税号必填——缺了这两项供应商开不出票。 */
    java.util.Map<String, String> savePlatformInvoiceTitle(java.util.Map<String, String> fields,
                                                           String operatorNo);

    // ---------------------------------------------------------------- 对账单（P0-12）

    /**
     * 商家对账单。**凭证需求不是报表需求**——必须逐行可与外部账单勾对。
     *
     * @param period 结算周期 {@code YYYY-MM}；为空给全部
     */
    StatementVO statement(String merchantNo, String period);

}
