package ai.neargo.shop.invoice.channel.cetustek;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayReceipt;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayReceipt.Outcome;
import java.util.Locale;
import java.util.Map;

/**
 * 鲸跃上传结果码 → 回执种类。码表是数据，照 UME {@code StatusCode} 与鲸跃回码说明逐行转写，评审时可以逐行对照。
 *
 * <p><b>未登记的码一律按「需人工处理」</b>（{@link Outcome#FINAL}）：宁可停下来等人看，也不对一个不认识的拒绝无限重发。
 * {@code S1 / C1 / A1}（「上传失败」，语义待鲸跃确认）同样按此处理，确认可重试后再改表。
 */
final class CetustekResultCodes {

    /** 每种报文的成功码；第二个是「已处理过」，重发时会收到，同样算送达。 */
    private static final Map<String, Map<String, Outcome>> BY_TYPE = Map.of(
            "ISSUE", Map.of("S0", Outcome.ACCEPTED, "S2", Outcome.ACCEPTED),
            "VOID", Map.of("C0", Outcome.ACCEPTED, "C5", Outcome.ACCEPTED),
            "ALLOWANCE", Map.of("A0", Outcome.ACCEPTED, "A6", Outcome.ACCEPTED),
            // 折让作废：C4 =「该作废折让单已经上传」；C3 =「没有该折让单号码可作废」按需人工
            "ALLOWANCE_VOID", Map.of("C0", Outcome.ACCEPTED, "C4", Outcome.ACCEPTED));

    /** 与报文种类无关的码。 */
    private static final Map<String, Outcome> COMMON = Map.of(
            "INVALID", Outcome.ACCOUNT_PROBLEM,   // 认证错误：officeid / posno / posid 不对
            "ERROR", Outcome.RETRYABLE);          // 鲸跃系统错误

    private CetustekResultCodes() {
    }

    static Outcome classify(String messageType, String resultCode) {
        String code = resultCode == null ? "" : resultCode.trim().toUpperCase(Locale.ROOT);
        Outcome byType = BY_TYPE.getOrDefault(messageType, Map.of()).get(code);
        if (byType != null) return byType;
        return COMMON.getOrDefault(code, Outcome.FINAL);
    }

    static GatewayReceipt receipt(String messageType, String resultCode, String refNo) {
        return switch (classify(messageType, resultCode)) {
            case ACCEPTED -> GatewayReceipt.accepted(CetustekInvoiceGateway.CODE + ":"
                    + resultCode.trim().toUpperCase(Locale.ROOT) + ":" + refNo);
            case RETRYABLE -> GatewayReceipt.retryable("cetustek " + resultCode);
            case ACCOUNT_PROBLEM -> GatewayReceipt.accountProblem("cetustek " + resultCode);
            case FINAL -> GatewayReceipt.rejected("cetustek " + resultCode);
        };
    }
}
