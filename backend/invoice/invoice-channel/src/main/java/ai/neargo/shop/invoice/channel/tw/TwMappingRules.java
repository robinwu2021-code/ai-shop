package ai.neargo.shop.invoice.channel.tw;

import tools.jackson.databind.JsonNode;

/**
 * 台湾加值中心共用的 MIG 读取规则（TDD-发票-加值中心模板-鲸跃与汎宇 §1）。各家从同一份 MIG 报文（{@code TwEguiRegime#message}）
 * 映射，相同的判断只写一次；纯函数，供厂商类组合使用 —— 不进继承树，因为厂商的父类留给了对接形态（SOAP / JSON …）。
 */
public final class TwMappingRules {

    /** 一般消费者的买方识别码：10 个 0。 */
    public static final String CONSUMER_ID = "0000000000";

    private TwMappingRules() {
    }

    /** 买方是营业人（带统编）。 */
    public static boolean b2b(JsonNode buyer) {
        return !CONSUMER_ID.equals(buyer.path("Identifier").asText());
    }

    /** 开立报文含零税率（主档课税别 2、零税率销售额非 0，或任一明细课税别 2）。 */
    public static boolean zeroRated(JsonNode mig) {
        JsonNode amount = mig.path("Amount");
        if ("2".equals(amount.path("TaxType").asText()) || amount.path("ZeroTaxSalesAmount").asLong() != 0) return true;
        for (JsonNode item : mig.path("Details").path("ProductItem"))
            if ("2".equals(item.path("TaxType").asText())) return true;
        return false;
    }

    /** 发票去向：捐赠 / 存载具 / 纸本。 */
    public enum IssueMode { DONATE, CARRIER, PRINT }

    public static IssueMode issueMode(JsonNode main) {
        if (main.hasNonNull("NPOBAN")) return IssueMode.DONATE;
        if (main.hasNonNull("CarrierType")) return IssueMode.CARRIER;
        return IssueMode.PRINT;
    }

    /** MIG 日期 {@code 20261121} → 加值中心要的格式，如 {@code 2026/11/21}（鲸跃）或 {@code 2026-11-21}（汎宇）。 */
    public static String date(String yyyymmdd, char separator) {
        if (yyyymmdd == null || yyyymmdd.length() != 8) return yyyymmdd;
        return yyyymmdd.substring(0, 4) + separator + yyyymmdd.substring(4, 6) + separator + yyyymmdd.substring(6);
    }

    /** 可选字段：没有或为 null 时给空串（加值中心多要求键存在、值留空）。 */
    public static String optional(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }
}
