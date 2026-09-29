package ai.neargo.shop.elec.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 询价。第一步由平台收单、人工跟进。 */
public final class RfqDtos {

    private RfqDtos() {
    }

    /**
     * @param needInvoice NONE / VAT_NORMAL / VAT_SPECIAL
     * @param dcReq       ANY / Y1 / Y2
     */
    /**
     * @param condReq     ANY / ORIGINAL 只要原装原包 / NEW 原装即可
     * @param packingReq  ANY / REEL 必须整盘 / CUT_TAPE 可以剪带
     * @param needByDays  几天内要到货；空 = 不急。**急单与常备单的价完全不同**
     * @param allowAlt    能不能用替代/兼容型号（含国产替代）。很多单子卡在这里
     */
    public record RfqReq(List<LineReq> lines, String needInvoice, String dcReq, String condReq,
                         String packingReq, Integer needByDays, Boolean allowAlt, String deliverCity,
                         String company, String contactName, String remark) {
    }

    /**
     * @param partNo   从料号详情进来时带上；手输的料号可以没有
     * @param targetE6 目标单价，百万分之一元，可空
     */
    public record LineReq(String partNo, String mpn, String mfr, Long qty, Long targetE6) {
    }

    /**
     * 买家看到的询价单。
     *
     * @param status       SUBMITTED 待报价 / QUOTED 已报价 / EXPIRED 报价已过期 / ACCEPTED 已接受 / CLOSED 已结束
     * @param contactPhone 提交时绑定的手机号（掩码）—— 让他知道平台会打哪个号
     * @param closeReason  NO_SOURCE 暂无货源 / BUYER_CANCELLED / DONE；没结束为 null
     */
    /**
     * @param dispatchCnt 派给了几家供应商
     * @param quoteCnt    有几家报了价
     */
    public record RfqView(String rfqNo, String status, LocalDateTime createdAt, int lineCnt,
                          int dispatchCnt, int quoteCnt,
                          String needInvoice, String dcReq, String condReq, String packingReq,
                          Integer needByDays, boolean allowAlt, String deliverCity, String company,
                          String contactName, String contactPhone, String remark,
                          LocalDateTime quotedAt, java.time.LocalDate quoteValidUntil, String quoteNote,
                          String closeReason, List<LineView> lines) {
    }

    /**
     * @param quote 平台对这一行的报价；null = 还没报，或报价时这一行没找到货
     */
    /**
     * @param quote  平台自己报的那条（运营在后台填的）；null = 平台没报
     * @param offers 买家能选的全部报价：平台那条 + 供应商报的（已加价、已匿名），按价升序
     */
    public record LineView(int lineNo, String partNo, String mpn, String mfr, long qty, Long targetE6,
                           LineQuote quote, List<Offer> offers) {
    }

    /**
     * @param priceE6  含税单价，百万分之一元
     * @param dcYear   批次年份
     * @param leadDays 交期天数，0 = 现货
     */
    public record LineQuote(long priceE6, Long qty, Integer dcYear, Integer leadDays, String cond,
                           String packing, String note) {
    }

    /**
     * 买家看到的一条报价。**没有、也不许加任何供应商字段**。
     *
     * @param label    这一行内的代号（报价 A / B / C）。<b>只在这一行内有意义</b> ——
     *                 跨行、跨单的 A 不是同一家，否则一对比就能把某一家聚出来
     * @param priceE6  含税单价，已按平台规则加价、换算成人民币
     * @param qty      他能供多少。少于要的数量时端上要标出来
     * @param from     PLATFORM 平台报的 / SUPPLIER 供应商报的。端上不显示这个词，
     *                 只用它决定「接受」之后走哪条跟进流程
     */
    public record Offer(String offerNo, String label, long priceE6, Long qty, Integer dcYear,
                        Integer leadDays, String cond, String packing, java.time.LocalDate validUntil,
                        String note, String from) {
    }

    // ── 供应商侧（看得到求购，看不到买家）────────────────────────────────

    /**
     * 派给这家供应商的一条求购。
     *
     * @param dispatchNo 供应商侧的单号。**不是 rfq_no** —— 两边拿不到同一个号
     * @param status     SENT 待报价 / VIEWED 看过 / QUOTED 已报价 / DECLINED 已拒绝
     * @param inStock    他自己库里这个料号还有多少（帮他一眼判断能不能接）；没有为 null
     */
    public record DispatchView(String dispatchNo, String status, LocalDateTime createdAt, String mpn,
                               String mfr, long qty, Long targetE6, String dcReq, String condReq,
                               String packingReq, Integer needByDays, boolean allowAlt, String needInvoice,
                               String deliverProvince, Long inStock, SupplierQuote myQuote) {
    }

    /** 供应商自己填的那条报价（他看得到原样，买家看到的是加价并匿名之后的） */
    public record SupplierQuote(String quoteNo, long priceE6, String currency, boolean taxIncluded,
                                long qtyAvailable, String dateCode, Integer leadDays, String cond,
                                String packing, Integer moq, java.time.LocalDate validUntil, String remark,
                                String status) {
    }

    /**
     * 供应商提交报价。
     *
     * @param validDays 报价有效几天（默认 3）
     * @param remark    只给平台看
     */
    public record SupplierQuoteReq(Long priceE6, String currency, Boolean taxIncluded, Long qtyAvailable,
                                   String dateCode, Integer leadDays, String cond, String packing,
                                   Integer moq, Integer validDays, String remark) {
    }

    /** @param reason NO_STOCK 没货 / PRICE 价格做不了 / OTHER */
    public record DeclineReq(String reason) {
    }

    // ── 运营端 ──────────────────────────────────────────────────────────────

    /**
     * 运营看到的询价单：<b>买家的完整联系方式、每行库里谁有货、每家供应商报了什么</b>都在这里 —— 运营端是内部面。
     *
     * @param buyerNotified 结果通知送达了没有（订阅消息或站内信任一送到）
     * @param dispatchCnt   派给了几家供应商（去重）
     * @param respondedCnt  其中几家已经回了话（报价或拒绝都算）。与 dispatchCnt 差得多 = 该催了
     * @param offerCnt      几家报了还有效的价
     */
    public record OpsRfqView(String rfqNo, String status, LocalDateTime createdAt, int lineCnt,
                             String contactName, String contactPhone, String company, String needInvoice,
                             String dcReq, String condReq, String packingReq, Integer needByDays,
                             boolean allowAlt, String deliverCity, String remark, LocalDateTime quotedAt,
                             String quotedBy, java.time.LocalDate quoteValidUntil, String quoteNote,
                             boolean buyerNotified, String closeReason, int dispatchCnt, int respondedCnt,
                             int offerCnt, List<OpsLineView> lines) {
    }

    /**
     * @param sources 库里谁有货（列表页不带，详情才带）
     * @param offers  这一行派给了谁、各自回了什么（列表页不带，详情才带）。<b>真名、原价</b>
     */
    public record OpsLineView(int lineNo, String partNo, String mpn, String mfr, long qty, Long targetE6,
                              LineQuote quote, List<OpsSource> sources, List<OpsOffer> offers) {
    }

    /**
     * 运营要照着它报价，所以供应商那一行的口径要全。
     *
     * @param tiers      阶梯价（供应商原样：原币种、原含税口径），按数量升序
     * @param priceE6    最低数量档的单价（= tiers 第一条）
     * @param validUntil 这行库存有效到哪天。快到期的价要打折扣看
     */
    public record OpsSource(String supplierNo, String companyName, String contactPhone, String stockNo, long qty,
                            String dateCode, Integer moq, Integer spq, List<SupplierDtos.PriceTier> tiers,
                            Long priceE6, String currency, boolean taxIncluded, String packing, String cond,
                            Integer leadDays, String region, java.time.LocalDate validUntil) {
    }

    /**
     * 运营看到的一条派单及其结果。与买家看到的 {@link Offer} 是同一件事的两个面：
     * 这里是<b>真名、供应商原价、备注</b>，那边是代号与加价后的价。
     *
     * @param via           AUTO_MATCH 库里有货自动派 / OPS 运营手工指派
     * @param dispatchStatus SENT 未看 / VIEWED 看了没回 / QUOTED 报了价 / DECLINED 拒了
     * @param declineReason NO_STOCK / PRICE / OTHER；没拒为空
     * @param quoteNo       报了价才有；下面的报价字段同理
     * @param priceE6       供应商填的单价（他的币种与含税口径，没加价）
     * @param buyerPriceE6  买家看到的价（换成人民币含税、按平台规则加价之后）
     * @param remark        供应商写给平台的备注 —— <b>买家永远看不到</b>
     * @param quoteStatus   ACTIVE / WITHDRAWN / ACCEPTED / EXPIRED
     */
    public record OpsOffer(String dispatchNo, String supplierNo, String companyName, String contactPhone,
                           String via, String dispatchStatus, String declineReason, LocalDateTime notifiedAt,
                           LocalDateTime respondedAt, String quoteNo, Long priceE6, String currency,
                           Boolean taxIncluded, Long buyerPriceE6, Long qtyAvailable, String dateCode,
                           Integer leadDays, String cond, String packing, Integer moq,
                           java.time.LocalDate validUntil, String remark, String quoteStatus) {
    }

    /** 手工指派：给这一行再派几家（已经派过的自动跳过）。一次最多 20 家 */
    public record DispatchReq(List<String> supplierNos) {
    }

    /**
     * 报价记录的一行：全部供应商报价，按时间倒序。
     *
     * @param status ACTIVE / WITHDRAWN / ACCEPTED / EXPIRED（过了有效期的 ACTIVE 显示成 EXPIRED）
     */
    public record OpsQuoteRow(String quoteNo, String rfqNo, int lineNo, String mpn, long qtyWanted,
                              String supplierNo, String companyName, Long priceE6, String currency,
                              boolean taxIncluded, Long buyerPriceE6, long qtyAvailable, Integer leadDays,
                              java.time.LocalDate validUntil, String status, LocalDateTime createdAt) {
    }

    /**
     * 录入报价。没列在 lines 里的行 = 没找到货。
     *
     * @param validDays 报价有效几天（默认 3 天）
     * @param note      给买家的说明（≤255 字）
     */
    public record QuoteReq(Integer validDays, String note, List<QuoteLineReq> lines) {
    }

    /** @param priceE6 含税单价，百万分之一元，必填 */
    public record QuoteLineReq(Integer lineNo, Long priceE6, Long qty, Integer dcYear, Integer leadDays,
                               String cond, String packing, String note) {
    }

    /** @param reason NO_SOURCE 暂无货源（会通知买家）/ BUYER_CANCELLED / DONE */
    public record CloseReq(String reason, String note) {
    }
}
