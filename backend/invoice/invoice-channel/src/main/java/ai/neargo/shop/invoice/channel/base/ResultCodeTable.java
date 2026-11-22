package ai.neargo.shop.invoice.channel.base;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayReceipt.Outcome;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 供应商结果码表：数据，不是代码分支。每家一张，照厂商文档逐行转写，评审时可以逐行对照、单测逐码覆盖。
 *
 * <p>先按报文种类查（同一个码在不同报文里含义不同，鲸跃 C0 / C4），再查通用码。
 * <b>未登记的码一律 {@link Outcome#FINAL}</b>：宁可停下等人看，也不对一个不认识的拒绝无限重发。码比较不分大小写。
 */
public final class ResultCodeTable {

    public static final String ANY_TYPE = "*";

    private final Map<String, Map<String, Outcome>> byType;
    private final Map<String, Outcome> common;

    private ResultCodeTable(Map<String, Map<String, Outcome>> byType, Map<String, Outcome> common) {
        this.byType = byType;
        this.common = common;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Outcome classify(String messageType, String resultCode) {
        String code = normalize(resultCode);
        Outcome typed = byType.getOrDefault(messageType, Map.of()).get(code);
        if (typed != null) return typed;
        return common.getOrDefault(code, Outcome.FINAL);
    }

    static String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    public static final class Builder {
        private final Map<String, Map<String, Outcome>> byType = new HashMap<>();
        private final Map<String, Outcome> common = new HashMap<>();

        /** 该报文的成功码；重发时供应商回的「已处理过」也登记成功 —— 同一份报文，送达就是送达。 */
        public Builder acceptedFor(String messageType, String... codes) {
            return put(messageType, Outcome.ACCEPTED, codes);
        }

        /** 与报文种类无关的成功码（多数 HTTP 接口：RtnCode=1）。 */
        public Builder accepted(String... codes) {
            return put(ANY_TYPE, Outcome.ACCEPTED, codes);
        }

        public Builder retryable(String... codes) {
            return put(ANY_TYPE, Outcome.RETRYABLE, codes);
        }

        public Builder accountProblem(String... codes) {
            return put(ANY_TYPE, Outcome.ACCOUNT_PROBLEM, codes);
        }

        private Builder put(String messageType, Outcome outcome, String... codes) {
            var target = ANY_TYPE.equals(messageType) ? common : byType.computeIfAbsent(messageType, k -> new HashMap<>());
            for (String c : codes)
                if (target.putIfAbsent(normalize(c), outcome) != null)
                    throw new IllegalStateException("duplicate result code " + messageType + "/" + c);
            return this;
        }

        public ResultCodeTable build() {
            var typed = new HashMap<String, Map<String, Outcome>>();
            byType.forEach((k, v) -> typed.put(k, Map.copyOf(v)));
            return new ResultCodeTable(Map.copyOf(typed), Map.copyOf(common));
        }
    }
}
