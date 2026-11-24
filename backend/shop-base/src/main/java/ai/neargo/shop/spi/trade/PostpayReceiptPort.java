package ai.neargo.shop.spi.trade;

import ai.neargo.shop.event.DomainEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 行业后付单的人工实收确认（TDD-餐饮柜台后付结账 · ADR-059）。
 *
 * <p>柜台收银员用现金或外部刷卡机收齐一张结算单后，餐饮调这里：核心自己核验外部记账（门店、结算单号、未作废、
 * 币种、未被重复使用、合计不少于应收、多收只能是现金找零），然后在<b>一个事务</b>里把结算单覆盖的所有后付核心单
 * 推到已付，来源标 {@code POSTPAY_RECEIPT}。按 {@code (storeNo, bizNo)} 幂等：同一请求重放原样返回。
 *
 * <p>钱没有经过平台，所以<b>不发通用的「订单已支付」事件</b>，只发 {@link Recorded}。
 */
public interface PostpayReceiptPort {

    Receipt confirm(Confirm command);

    /** 一张后付核心单的「版本」：变更指纹；没有变更过时用来源版本。冻结与确认两侧都用它比对。 */
    /**
     * 跨服务调用的请求摘要：服务端把业务错误码与它一起回在响应头里，调用方核对摘要一致才采信错误码
     * （{@code InternalServiceResponses.invokeBound}）。
     */
    static String digest(Confirm c) {
        var text = new StringBuilder("POSTPAY_RECEIPT_CONFIRM_V1");
        for (Object part : List.of(String.valueOf(c.tenantNo()), String.valueOf(c.entityNo()),
                String.valueOf(c.storeNo()), String.valueOf(c.bizNo()), String.valueOf(c.currency()),
                String.valueOf(c.actorNo()), String.valueOf(c.requestKey()))) {
            text.append('|').append(part);
        }
        if (c.roots() != null) {
            for (Root r : c.roots()) {
                text.append("|R:").append(r.orderNo()).append(':').append(r.dueMinor()).append(':')
                        .append(r.coreVersion());
            }
        }
        if (c.offlineRecordNos() != null) {
            for (String no : c.offlineRecordNos()) {
                text.append("|O:").append(no);
            }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String coreVersion(IndustryOrderPort.Result result) {
        return result.amendmentSourceHash() != null ? result.amendmentSourceHash() : "source:" + result.sourceVersion();
    }

    /** 一张后付核心单当前应收：有变更后的有效应收用它，否则用下单金额。 */
    static long dueMinor(IndustryOrderPort.Result result) {
        Long effective = result.effectiveReceivableMinor();
        return effective != null ? effective : result.payAmountMinor() == null ? 0 : result.payAmountMinor();
    }

    /**
     * @param bizNo            结算单号，与外部记账的 {@code bizNo} 一致
     * @param roots            结算单覆盖的每张后付核心单，金额与版本是餐饮冻结时读到的
     * @param offlineRecordNos 外部记账号（{@code POST /biz/payment/offline-records} 的返回）
     * @param requestKey       调用方的幂等键；同一结算单号的重放必须带同一个
     */
    record Confirm(String tenantNo, String entityNo, String storeNo, String bizNo, String currency,
            List<Root> roots, List<String> offlineRecordNos, String actorNo, String requestKey) {
        @Override
        public String toString() {
            return "PostpayReceiptConfirm[" + storeNo + "/" + bizNo + "]";
        }
    }

    /**
     * @param dueMinor    冻结时读到的这张单的当前有效应收
     * @param coreVersion 冻结时读到的变更指纹（{@code IndustryOrderPort.Result#amendmentSourceHash}）；
     *                    之后有退菜等改动就对不上，确认被拒
     */
    record Root(String orderNo, long dueMinor, String coreVersion) {
    }

    /**
     * @param receivedMinor 外部记账合计
     * @param changeMinor   找零（多收的现金）
     * @param replayed      true 表示这是此前已确认的那一次，原样返回
     */
    record Receipt(String receiptNo, String bizNo, String currency, long dueMinor, long receivedMinor,
            long changeMinor, List<String> paidOrderNos, boolean replayed) {
    }

    /** 实收已记录。只带编号，不带金额与付款人；只归档，不进平台结算。 */
    record Recorded(String receiptNo, String entityNo, String storeNo, String bizNo) implements DomainEvent {
        @Override
        public String aggregateType() {
            return "POSTPAY_RECEIPT";
        }

        @Override
        public String aggregateId() {
            return receiptNo;
        }

        @Override
        public String eventType() {
            return "POSTPAY_RECEIPT_RECORDED";
        }
    }
}
