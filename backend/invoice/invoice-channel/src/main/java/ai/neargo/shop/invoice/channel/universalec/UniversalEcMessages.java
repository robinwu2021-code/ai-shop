package ai.neargo.shop.invoice.channel.universalec;

import ai.neargo.shop.invoice.channel.base.AbstractInvoiceGateway.UnsupportedMessageException;
import ai.neargo.shop.invoice.channel.tw.TwMappingRules;
import ai.neargo.shop.invoice.gateway.BlankRangeCapability;
import ai.neargo.shop.invoice.gateway.TrackCapability;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * 税制的 MIG 树 → 汎宇 POS JSON 报文（《電子發票POS機傳輸規格書 JSON MIG4.1》）。纯函数，便于逐字段对照规格书。
 *
 * <p>开立用 C0401（A / B / C / D 编号字段）、作废 C0501、折让 D0401N、折让作废 D0501；汎宇侧各自转成 MIG4.1 的
 * F0401 / F0501 / G0401 / G0501。折让不用 D0401：它的「原明细排列序号」必填，而税制的折让报文没有这一项，不编造。
 *
 * <p>账号三元按 {@code SELLERID / POSID / POSSN} 写进报文体 —— 汎宇没有签名或加密，通道金钥本身就是认证。
 */
final class UniversalEcMessages {

    /** 汎宇文档要求的占位值：ACCOUNT / APPID 填 16 个 0。 */
    static final String ZEROS16 = "0000000000000000";

    private UniversalEcMessages() {
    }

    /** F0401 → C0401。零税率需要「零税率原因」与通关方式，税制报文没有，映射阶段拒绝。 */
    static Map<String, Object> issue(JsonNode m, ProviderAccount account, String sysTime) {
        JsonNode main = m.path("Main"), amount = m.path("Amount"), buyer = main.path("Buyer");
        if (TwMappingRules.zeroRated(m)) throw new UnsupportedMessageException("zero-rated invoices are not supported on UniversalEC");

        var x = new LinkedHashMap<String, Object>();
        x.put("A1", "C0401");
        x.put("A2", main.path("InvoiceNumber").asText());
        x.put("A3", day(main.path("InvoiceDate").asText()));
        x.put("A4", main.path("InvoiceTime").asText());
        x.put("A5", buyer.path("Identifier").asText());
        x.put("A6", buyer.path("Name").asText());
        for (String blank : List.of("A7", "A8", "A9", "A10", "A11", "A12", "A13", "A15", "A16", "A17")) x.put(blank, "");
        x.put("A22", main.path("InvoiceType").asText("07"));
        x.put("A23", "");
        x.put("A24", main.path("DonateMark").asText("0"));
        x.put("A25", text(main, "CarrierType"));
        x.put("A26", text(main, "CarrierId1"));
        x.put("A27", text(main, "CarrierId2"));
        x.put("A28", main.path("PrintMark").asText());
        x.put("A29", text(main, "NPOBAN"));
        x.put("A30", main.path("RandomNumber").asText());
        x.put("A31", "");

        var items = new ArrayList<Map<String, Object>>();
        for (JsonNode item : m.path("Details").path("ProductItem")) {
            var b = new LinkedHashMap<String, Object>();
            String seq = item.path("SequenceNumber").asText();
            b.put("B1", seq);
            b.put("B2", item.path("Description").asText());
            b.put("B3", item.path("Quantity").asText());
            b.put("B4", text(item, "Unit"));
            b.put("B5", item.path("UnitPrice").asText());
            b.put("B6", item.path("Amount").asText());
            b.put("B7", seq);
            b.put("B8", "");
            b.put("B9", "");
            b.put("B13", item.path("TaxType").asText());
            items.add(b);
        }
        x.put("B", items);

        x.put("C1", amount.path("SalesAmount").asText());
        x.put("C2", amount.path("FreeTaxSalesAmount").asText("0"));
        x.put("C3", amount.path("ZeroTaxSalesAmount").asText("0"));
        x.put("C4", amount.path("TaxType").asText());
        x.put("C5", amount.path("TaxRate").asText("0.05"));
        x.put("C6", amount.path("TaxAmount").asText());
        x.put("C7", amount.path("TotalAmount").asText());

        x.put("D1", account.accountCode());
        x.put("D2", account.secret());
        x.put("D3", account.keyId());
        x.put("D4", sysTime);
        x.put("ACCOUNT", ZEROS16);
        x.put("APPID", ZEROS16);
        return Map.of("Invoice", x);
    }

    /** F0501 → C0501。 */
    static Map<String, Object> voidInvoice(JsonNode m, ProviderAccount account, String sysTime) {
        return cancelInvoice("C0501", m, account, sysTime);
    }

    /** F0701 → C0701 注销（字段与作废同形）。 */
    static Map<String, Object> revoke(JsonNode m, ProviderAccount account, String sysTime) {
        return cancelInvoice("C0701", m, account, sysTime);
    }

    /** Z11 查注销流程状态。 */
    static Map<String, Object> revokeStatus(String fiscalNumber, String invoiceDate, ProviderAccount account,
            String sysTime) {
        var x = new LinkedHashMap<String, Object>();
        x.put("FUNCTIONCODE", "Z11");
        x.put("SELLERID", account.accountCode());
        x.put("POSID", account.keyId());
        x.put("POSSN", account.secret());
        x.put("INVOICE_NUMBER", fiscalNumber);
        x.put("INVOICE_DATE", invoiceDate);
        x.put("SYSTIME", sysTime);
        x.put("REMARK", "");
        return Map.of("INDEX", x);
    }

    private static Map<String, Object> cancelInvoice(String code, JsonNode m, ProviderAccount account, String sysTime) {
        var x = cancelHead(code, account);
        x.put("INVOICE_NUMBER", m.path("CancelInvoiceNumber").asText());
        x.put("INVOICE_DATE", day(m.path("InvoiceDate").asText()));
        cancelTail(x, m, account, sysTime);
        return Map.of("Invoice", x);
    }

    /** G0401 → D0401N（賣方折讓證明單通知）。 */
    static Map<String, Object> allowance(JsonNode m, ProviderAccount account, String sysTime) {
        JsonNode main = m.path("Main"), amount = m.path("Amount"), buyer = main.path("Buyer");
        var x = new LinkedHashMap<String, Object>();
        x.put("INVOICE_CODE", "D0401N");
        x.put("POSID", account.keyId());
        x.put("SELLERID", account.accountCode());
        x.put("POSSN", account.secret());
        x.put("SYSTIME", sysTime);
        x.put("ALLOWANCENUMBER", main.path("AllowanceNumber").asText());
        x.put("ALLOWANCEDATE", day(main.path("AllowanceDate").asText()));
        x.put("IDENTIFIER", buyer.path("Identifier").asText());
        x.put("NAME", buyer.path("Name").asText());
        x.put("ALLOWANCETYPE", main.path("AllowanceType").asText("2"));
        x.put("TAXAMOUNT", amount.path("TaxAmount").asText());
        x.put("TOTALAMOUNT", amount.path("TotalAmount").asText());
        var items = new ArrayList<Map<String, Object>>();
        for (JsonNode item : m.path("Details").path("ProductItem")) {
            var d = new LinkedHashMap<String, Object>();
            d.put("ORIGINALINVOICEDATE", day(item.path("OriginalInvoiceDate").asText()));
            d.put("ORIGINALINVOICENUMBER", item.path("OriginalInvoiceNumber").asText());
            d.put("ORIGINALDESCRIPTION", item.path("OriginalDescription").asText());
            d.put("QUANTITY", item.path("Quantity").asText());
            d.put("UNIT", text(item, "Unit"));
            d.put("UNITPRICE", item.path("UnitPrice").asText());
            d.put("AMOUNT", item.path("Amount").asText());
            d.put("TAX", item.path("Tax").asText());
            d.put("ALLOWANCESEQUENCENUMBER", item.path("AllowanceSequenceNumber").asText("1"));
            d.put("TAXTYPE", item.path("TaxType").asText());
            items.add(d);
        }
        x.put("PRODUCTITEM", items);
        return Map.of("Invoice", x);
    }

    /** G0501 → D0501；INVOICE_NUMBER 填折让单号、INVOICE_DATE 填折让开立日。 */
    static Map<String, Object> voidAllowance(JsonNode m, ProviderAccount account, String sysTime) {
        var x = cancelHead("D0501", account);
        x.put("INVOICE_NUMBER", m.path("CancelAllowanceNumber").asText());
        x.put("INVOICE_DATE", day(m.path("AllowanceDate").asText()));
        cancelTail(x, m, account, sysTime);
        x.put("ALLOWANCETYPE", "2");
        return Map.of("Invoice", x);
    }

    /** Y01 / A01 / C01 共用的 INDEX 报文。 */
    static Map<String, Object> index(String functionCode, ProviderAccount account, String sysTime) {
        var x = new LinkedHashMap<String, Object>();
        x.put("FUNCTIONCODE", functionCode);
        x.put("REPLY", "");
        x.put("MESSAGE", "");
        x.put("SELLERID", account.accountCode());
        x.put("POSID", account.keyId());
        x.put("POSSN", account.secret());
        x.put("SYSTIME", sysTime);
        x.put("ACCOUNT", ZEROS16);
        x.put("APPID", ZEROS16);
        return Map.of("INDEX", x);
    }

    /** Z31 查询已下载字轨 / Z33 查询配号方式：同一形状，期别为民国年 + 双月。 */
    static Map<String, Object> periodQuery(String code, String periodCode, ProviderAccount account, String sysTime) {
        var x = new LinkedHashMap<String, Object>();
        x.put("INVOICE_CODE", code);
        x.put("YEAR_MONTH", periodCode);
        x.put("POSID", account.keyId());
        x.put("POSSN", account.secret());
        x.put("SELLERID", account.accountCode());
        x.put("SYSTIME", sysTime);
        return Map.of("Invoice", x);
    }

    /** Z32 上传下期字轨：一期一字轨一批；无总分支机构时分配统编 = 卖方统编。 */
    static Map<String, Object> uploadTracks(TrackCapability.TrackUpload u, ProviderAccount account, String sysTime) {
        var x = new LinkedHashMap<String, Object>();
        x.put("INVOICE_CODE", "Z32");
        x.put("POSID", account.keyId());
        x.put("SELLERID", account.accountCode());
        x.put("POSSN", account.secret());
        x.put("SYSTIME", sysTime);
        x.put("YEAR_MONTH", u.periodCode());
        x.put("INVOICE_TYPE", u.invoiceType());
        x.put("INVOICE_TRACK", u.track());
        var items = new ArrayList<Map<String, Object>>();
        for (var r : u.ranges()) {
            var i = new LinkedHashMap<String, Object>();
            i.put("COMP_ID", u.sellerTaxId());
            i.put("INVOICE_START", no8(r.startNo()));
            i.put("INVOICE_END", no8(r.endNo()));
            items.add(i);
        }
        x.put("INVOICE_ITEM", items);
        return Map.of("Invoice", x);
    }

    /** E0402 空白未使用字轨：无总分支机构时总公司与分支机构统编都填卖方统编。 */
    static Map<String, Object> blankTracks(BlankRangeCapability.BlankReport b, ProviderAccount account) {
        var x = new LinkedHashMap<String, Object>();
        x.put("INVOICE_CODE", "E0402");
        x.put("POSSN", account.secret());
        x.put("POSID", account.keyId());
        x.put("SELLERID", account.accountCode());
        var main = new LinkedHashMap<String, Object>();
        main.put("HeadBan", b.sellerTaxId());
        main.put("BranchBan", b.sellerTaxId());
        main.put("InvoiceType", b.invoiceType());
        main.put("YearMonth", b.periodCode());
        main.put("InvoiceTrack", b.track());
        x.put("Main", main);
        var items = new ArrayList<Map<String, Object>>();
        for (var r : b.ranges()) {
            var i = new LinkedHashMap<String, Object>();
            i.put("InvoiceBeginNo", no8(r.startNo()));
            i.put("InvoiceEndNo", no8(r.endNo()));
            items.add(i);
        }
        x.put("Details", Map.of("BranchTrackBlankItem", items));
        return Map.of("Invoice", x);
    }

    static String no8(int n) {
        return "%08d".formatted(n);
    }

    private static LinkedHashMap<String, Object> cancelHead(String code, ProviderAccount account) {
        var x = new LinkedHashMap<String, Object>();
        x.put("INVOICE_CODE", code);
        x.put("POSSN", account.secret());
        x.put("POSID", account.keyId());
        return x;
    }

    private static void cancelTail(Map<String, Object> x, JsonNode m, ProviderAccount account, String sysTime) {
        x.put("BUYERID", m.path("BuyerId").asText("0000000000"));
        x.put("SELLERID", account.accountCode());
        x.put("CANCEL_DATE", day(m.path("CancelDate").asText()));
        x.put("CANCEL_TIME", m.path("CancelTime").asText());
        x.put("CANCEL_REASON", m.path("CancelReason").asText());
        x.put("RETURNTAXDOCUMENT_NUMBER", "");
        x.put("REMARK", "");
        x.put("SYSTIME", sysTime);
        x.put("ACCOUNT", ZEROS16);
        x.put("APPID", ZEROS16);
    }

    private static String text(JsonNode node, String field) {
        return TwMappingRules.optional(node, field);
    }

    /** {@code 20261121} → {@code 2026-11-21}。 */
    static String day(String yyyymmdd) {
        return TwMappingRules.date(yyyymmdd, '-');
    }
}
