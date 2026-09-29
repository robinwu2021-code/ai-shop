package ai.neargo.shop.elec.gateway;

import java.util.List;

/**
 * 推给平台运营的消息（企业微信群）。第一步<b>群就是运营的工作台</b>，
 * 所以消息要能直接拿来干活：买家要什么、打谁的电话、库里谁有货。
 *
 * <p><b>失败语义：不抛，返回送没送到</b>。调用方据此写 notified_at ——
 * 没送到的那几条在库里查得出来，而不是只在日志里。
 */
public interface ElecAlerts {

    boolean newSupplier(SupplierAlert s);

    boolean newRfq(RfqAlert rfq);

    /** 买家接受了平台的报价：运营要去签合同、安排发货 */
    boolean rfqAccepted(String rfqNo, String contactName, String contactPhone, String summary);

    /** 供应商报了价：哪家（真名）、什么价、什么货况，这一行目前几家报了 —— 运营据此决定要不要再催别家 */
    boolean supplierQuoted(QuoteAlert q);

    /**
     * 供应商拒了。{@link DeclineAlert#lineAllDeclined} 为 true 时这一行<b>已经没人能接了</b>，
     * 消息标题要醒目 —— 那是运营要亲自出手找货的信号，淹在普通消息里就会被错过。
     */
    boolean supplierDeclined(DeclineAlert d);

    /**
     * @param priceE6      供应商填的原价（他的币种与含税口径）
     * @param offersOnLine 这一行目前有几家有效报价（含这一家）
     * @param requote      true = 同一条派单改价（不是新的一家）
     */
    record QuoteAlert(String rfqNo, int lineNo, String mpn, long qtyWanted, String companyName,
                      String contactPhone, long priceE6, String currency, boolean taxIncluded, long qtyAvailable,
                      String dateCode, Integer leadDays, String cond, int offersOnLine, boolean requote) {
    }

    /**
     * @param reason          NO_STOCK 没货 / PRICE 价格做不了 / OTHER
     * @param lineAllDeclined 这一行派出去的都回了话、都是拒绝，平台也没报 —— 已经没人能接
     */
    record DeclineAlert(String rfqNo, int lineNo, String mpn, long qtyWanted, String companyName,
                        String contactPhone, String reason, boolean lineAllDeclined) {
    }

    /** @param companyName 可空：点一下就成为供应商，公司名之后才补 */
    record SupplierAlert(String supplierNo, String companyName, String kind, String city,
                         String contactName, String contactPhone) {
    }

    /**
     * @param needInvoice NONE / VAT_NORMAL / VAT_SPECIAL（码，格式化时翻成人话）
     * @param dcReq       ANY / Y1 / Y2
     */
    record RfqAlert(String rfqNo, String contactName, String contactPhone, String company,
                    String needInvoice, String dcReq, String deliverCity, String remark,
                    List<RfqLine> lines) {
    }

    /**
     * @param targetE6 目标单价，百万分之一元；空 = 没填
     * @param sources  平台库里谁有货（内部信息，只进运营群）；空列表 = 库里没货，要平台去找
     */
    record RfqLine(String mpn, String mfr, long qty, Long targetE6, List<Source> sources) {
    }

    /** @param priceE6 供应商填的单价；空 = 没报价 */
    record Source(String companyName, String contactPhone, long qty, String dateCode,
                  Long priceE6, boolean taxIncluded) {
    }
}
