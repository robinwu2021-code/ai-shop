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
