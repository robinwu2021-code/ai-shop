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
    public record RfqView(String rfqNo, String status, LocalDateTime createdAt, int lineCnt,
                          String needInvoice, String dcReq, String condReq, String packingReq,
                          Integer needByDays, boolean allowAlt, String deliverCity, String company,
                          String contactName, String contactPhone, String remark,
                          LocalDateTime quotedAt, java.time.LocalDate quoteValidUntil, String quoteNote,
                          String closeReason, List<LineView> lines) {
    }

    /**
     * @param quote 平台对这一行的报价；null = 还没报，或报价时这一行没找到货
     */
    public record LineView(int lineNo, String partNo, String mpn, String mfr, long qty, Long targetE6,
                           LineQuote quote) {
    }

    /**
     * @param priceE6  含税单价，百万分之一元
     * @param dcYear   批次年份
     * @param leadDays 交期天数，0 = 现货
     */
    public record LineQuote(long priceE6, Long qty, Integer dcYear, Integer leadDays, String cond,
                           String packing, String note) {
    }

    // ── 运营端 ──────────────────────────────────────────────────────────────

    /**
     * 运营看到的询价单：<b>买家的完整联系方式、每行库里谁有货</b>都在这里 —— 运营端是内部面。
     *
     * @param buyerNotified 结果通知送达了没有（订阅消息或站内信任一送到）
     */
    public record OpsRfqView(String rfqNo, String status, LocalDateTime createdAt, int lineCnt,
                             String contactName, String contactPhone, String company, String needInvoice,
                             String dcReq, String condReq, String packingReq, Integer needByDays,
                             boolean allowAlt, String deliverCity, String remark, LocalDateTime quotedAt,
                             String quotedBy, java.time.LocalDate quoteValidUntil, String quoteNote,
                             boolean buyerNotified, String closeReason, List<OpsLineView> lines) {
    }

    /** @param sources 库里谁有货（列表页不带，详情才带） */
    public record OpsLineView(int lineNo, String partNo, String mpn, String mfr, long qty, Long targetE6,
                              LineQuote quote, List<OpsSource> sources) {
    }

    /** 运营要照着它报价，所以供应商那一行的口径要全：阶梯价的最低档、币种、含税、包装、货况、交期 */
    public record OpsSource(String supplierNo, String companyName, String contactPhone, long qty,
                            String dateCode, Long priceE6, String currency, boolean taxIncluded,
                            String packing, String cond, Integer leadDays, String region) {
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
