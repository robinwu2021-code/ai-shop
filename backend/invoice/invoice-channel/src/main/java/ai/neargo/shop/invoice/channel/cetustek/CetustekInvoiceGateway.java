package ai.neargo.shop.invoice.channel.cetustek;

import ai.neargo.shop.invoice.gateway.InvoiceProviderGateway;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 鯨躍（Cetustek）加值中心通道，代码 {@code CETUSTEK}。协议与字段沿用 UME invoice-web 的
 * {@code CetustekInvoiceServiceImpl}：开立 UploadInvoice、作废 UploadCancelInvoice、折让 UploadAllowance、折让作废 UploadCancelAllowance，
 * 账号核验 QueryOfficeInfo，号段 ApplyInvoiceNumber → TakeInvoiceNumber，查单 QueryInvoiceStatus。
 *
 * <p><b>报文</b>：税制给的是 MIG 树（见 {@code TwEguiRegime#message}），这里按鲸跃的扁平 XML 重排：
 * 日期 {@code yyyy/MM/dd}；B2C 不带买方识别码；纸本 / 载具 / 捐赠的 DonateMark 分别是 2 / 0 / 1；
 * 免税明细 DType=TN。零税率鲸跃另有通关方式栏位，首期不支持，报文直接判失败留在任务里，不送出半对的票。
 *
 * <p><b>结果码</b>：见 {@link CetustekResultCodes}。同一报文重发时鲸跃回「已上传过」（S2 / C5 / A6），视为送达；
 * 认证失败（invalid）交给上传任务暂停该主体；不认识的码不自动重发。网络不通由 {@link CetustekSoapClient} 抛出，按可重试处理。
 *
 * <p><b>网络</b>：鲸跃只放行台湾出口（本机直连 TCP 被拒），线上实例部署在台湾机房；
 * 地址取 {@code shop.invoice.cetustek.url}，默认 UME 一直在用的正式地址。
 */
@Component
public class CetustekInvoiceGateway implements InvoiceProviderGateway {

    static final String CODE = "CETUSTEK";
    private static final Set<String> FAILURES = Set.of("ERROR", "INVALID", "EXIST", "NODATA", "NOTAKE");
    private static final String CONSUMER_ID = "0000000000";

    private final CetustekSoapClient soap;
    private final ObjectMapper json;

    @Autowired
    public CetustekInvoiceGateway(
            @Value("${shop.invoice.cetustek.url:http://invoice.cetustek.com.tw/InvoiceEntitySync/InvoiceAPI}") String url,
            @Value("${shop.invoice.cetustek.timeout-seconds:20}") int timeoutSeconds, ObjectMapper json) {
        this(new CetustekSoapClient(URI.create(url), Duration.ofSeconds(timeoutSeconds)), json);
    }

    CetustekInvoiceGateway(CetustekSoapClient soap, ObjectMapper json) {
        this.soap = soap;
        this.json = json;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public GatewayReceipt send(GatewayRequest request) {
        if (!"TW_EGUI".equals(request.regimeCode()))
            return GatewayReceipt.rejected("Cetustek only serves TW_EGUI");
        if (request.account() == null) return GatewayReceipt.accountProblem("provider account not configured");
        JsonNode m = json.readTree(request.payloadJson());
        String operation, field, xml;
        try {
            switch (request.messageType()) {
                case "ISSUE" -> {
                    operation = "UploadInvoice";
                    field = "invoicexml";
                    xml = invoiceXml(m, request.refNo());
                }
                case "VOID" -> {
                    operation = "UploadCancelInvoice";
                    field = "invoicexml";
                    xml = cancelXml(m);
                }
                case "ALLOWANCE" -> {
                    operation = "UploadAllowance";
                    field = "allowancexml";
                    xml = allowanceXml(m);
                }
                case "ALLOWANCE_VOID" -> {
                    operation = "UploadCancelAllowance";
                    field = "allowancexml";
                    xml = cancelAllowanceXml(m);
                }
                default -> {
                    return GatewayReceipt.rejected("Unsupported message type: " + request.messageType());
                }
            }
        } catch (UnsupportedOperationException e) {
            return GatewayReceipt.rejected(e.getMessage());
        }
        String result = soap.call(operation, request.account(), Map.of(field, xml));
        return CetustekResultCodes.receipt(request.messageType(), result, request.refNo());
    }

    @Override
    public ProviderOffice verify(ProviderAccount account) {
        String result = soap.call("QueryOfficeInfo", account, Map.of());
        failIfCode(result);
        String id = CetustekSoapClient.between(result, "<ID>", "</ID>");
        if (id == null) throw new ProviderException("unexpected", null);
        return new ProviderOffice(id.trim(), text(result, "Name"), text(result, "QRCode"));
    }

    /**
     * 查单（UME {@code CetustekInvoiceServiceImpl#queryInvoiceStatus}）：回 0 已开立 / 1 已作废 / 2 部分折让 / 3 完全折让；
     * {@code nodata} 视为鲸跃侧没有这张票。日期格式沿用上传的 {@code yyyy/MM/dd}（待鲸跃确认，TDD-发票模块 §16）。
     */
    @Override
    public ProviderInvoiceStatus queryStatus(ProviderAccount account, String fiscalNumber, LocalDate invoiceDate) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("invoicenumber", fiscalNumber);
        fields.put("invoicedate", invoiceDate.format(DateTimeFormatter.ofPattern("yyyy/MM/dd")));
        String result = soap.call("QueryInvoiceStatus", account, fields);
        var state = switch (result.toUpperCase(Locale.ROOT)) {
            case "0" -> ProviderInvoiceStatus.State.ISSUED;
            case "1" -> ProviderInvoiceStatus.State.VOIDED;
            case "2" -> ProviderInvoiceStatus.State.PARTIALLY_ALLOWED;
            case "3" -> ProviderInvoiceStatus.State.FULLY_ALLOWED;
            case "NODATA" -> ProviderInvoiceStatus.State.NOT_FOUND;
            case "INVALID", "ERROR" -> throw new ProviderException(result.toLowerCase(Locale.ROOT), null);
            default -> throw new ProviderException("unexpected:" + result, null);
        };
        return new ProviderInvoiceStatus(state, result);
    }

    @Override
    public AllocatedRange allocate(ProviderAccount account, String periodCode) {
        int even = Integer.parseInt(periodCode.substring(3));
        var apply = new LinkedHashMap<String, String>();
        apply.put("year", periodCode.substring(0, 3));
        apply.put("month", "%02d-%02d".formatted(even - 1, even));
        String applyId = soap.call("ApplyInvoiceNumber", account, apply);
        failIfCode(applyId);
        String taken = soap.call("TakeInvoiceNumber", account, Map.of("applyid", applyId));
        failIfCode(taken);
        // prefix;start;end;invoiceType，如 AB;12345600;12345649;07
        String[] parts = taken.split(";");
        if (parts.length < 3) throw new ProviderException("unexpected", null);
        try {
            return new AllocatedRange(applyId, parts[0].trim(), Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()), parts.length > 3 ? parts[3].trim() : null);
        } catch (NumberFormatException e) {
            throw new ProviderException("unexpected", e);
        }
    }

    private static void failIfCode(String result) {
        if (result.isEmpty()) throw new ProviderException("nodata", null);
        if (FAILURES.contains(result.toUpperCase(Locale.ROOT))) throw new ProviderException(result.toLowerCase(Locale.ROOT), null);
    }

    private static String text(String xml, String tag) {
        String v = CetustekSoapClient.between(xml, "<" + tag + ">", "</" + tag + ">");
        return v == null || v.isBlank() ? null : v.trim();
    }

    // ---- MIG 树 → 鲸跃 XML ----

    static String invoiceXml(JsonNode m, String orderId) {
        JsonNode main = m.path("Main"), amount = m.path("Amount"), buyer = main.path("Buyer");
        if ("2".equals(amount.path("TaxType").asText()) || amount.path("ZeroTaxSalesAmount").asLong() != 0)
            throw new UnsupportedOperationException("zero-rated invoices are not supported on Cetustek");
        String date = day(main.path("InvoiceDate").asText());
        var x = new Xml("Invoice");
        x.put("InvoiceNumber", main.path("InvoiceNumber").asText());
        x.put("InvoiceDate", date);
        x.put("InvoiceTime", main.path("InvoiceTime").asText());
        x.put("OrderId", orderId);
        x.put("OrderDate", date);
        boolean b2b = !CONSUMER_ID.equals(buyer.path("Identifier").asText());
        if (b2b) {
            x.put("BuyerIdentifier", buyer.path("Identifier").asText());
            x.put("BuyerName", buyer.path("Name").asText());
        }
        x.put("InvoiceType", main.path("InvoiceType").asText("07"));
        x.put("TaxType", amount.path("TaxType").asText());
        x.put("TaxRate", amount.path("TaxRate").asText("0.05"));
        boolean donate = main.hasNonNull("NPOBAN"), carrier = main.hasNonNull("CarrierType");
        x.put("DonateMark", donate ? "1" : carrier ? "0" : "2");
        if (carrier) {
            x.put("CarrierType", main.path("CarrierType").asText());
            x.put("CarrierId1", main.path("CarrierId1").asText());
            x.put("CarrierId2", main.path("CarrierId2").asText());
        }
        if (donate) x.put("NPOBAN", main.path("NPOBAN").asText());
        x.put("RandomNumber", main.path("RandomNumber").asText());
        x.put("MailSend", "N");
        x.put("SaleAmount", amount.path("SalesAmount").asText());
        x.put("FreeTaxSalesAmount", amount.path("FreeTaxSalesAmount").asText());
        x.put("ZeroTaxSalesAmount", amount.path("ZeroTaxSalesAmount").asText());
        x.put("TaxAmount", amount.path("TaxAmount").asText());
        x.put("TotalAmount", amount.path("TotalAmount").asText());
        x.put("PayWay", "5");
        x.open("Details");
        for (JsonNode item : m.path("Details").path("ProductItem")) {
            x.open("ProductItem");
            x.put("ProductionCode", item.path("SequenceNumber").asText());
            x.put("Description", item.path("Description").asText());
            x.put("Quantity", item.path("Quantity").asText());
            x.put("Unit", item.path("Unit").asText(""));
            x.put("UnitPrice", item.path("UnitPrice").asText());
            x.put("Amount", item.path("Amount").asText());
            x.put("DType", dType(item.path("TaxType").asText()));
            x.close("ProductItem");
        }
        x.close("Details");
        return x.done();
    }

    static String cancelXml(JsonNode m) {
        var x = new Xml("Invoice");
        x.put("InvoiceNumber", m.path("CancelInvoiceNumber").asText());
        x.put("InvoiceDate", day(m.path("InvoiceDate").asText()));
        x.put("BuyerId", m.path("BuyerId").asText(CONSUMER_ID));
        x.put("CancelDate", day(m.path("CancelDate").asText()));
        x.put("CancelTime", m.path("CancelTime").asText());
        x.put("Reason", m.path("CancelReason").asText());
        x.put("ReturnTaxDocumentNumber", "");
        x.put("Remark", "");
        return x.done();
    }

    static String allowanceXml(JsonNode m) {
        JsonNode main = m.path("Main"), amount = m.path("Amount"), buyer = main.path("Buyer");
        JsonNode item = m.path("Details").path("ProductItem").path(0);
        if ("2".equals(item.path("TaxType").asText()))
            throw new UnsupportedOperationException("zero-rated allowances are not supported on Cetustek");
        var x = new Xml("Allowance");
        x.put("AllowanceNumber", main.path("AllowanceNumber").asText());
        x.put("AllowanceDate", day(main.path("AllowanceDate").asText()));
        x.put("InvoiceNumber", item.path("OriginalInvoiceNumber").asText());
        x.put("InvoiceDate", day(item.path("OriginalInvoiceDate").asText()));
        x.put("InvoiceTime", m.path("ext").path("OriginalInvoiceTime").asText(""));
        if (!CONSUMER_ID.equals(buyer.path("Identifier").asText())) {
            x.put("BuyerIdentifier", buyer.path("Identifier").asText());
            x.put("BuyerName", buyer.path("Name").asText());
        }
        x.put("TaxType", item.path("TaxType").asText());
        x.put("Reason", item.path("OriginalDescription").asText());
        x.put("SaleAmount", amount.path("TotalAmount").asText());
        x.put("TaxAmount", amount.path("TaxAmount").asText());
        x.open("Details");
        x.open("ProductItem");
        x.put("ProductionCode", item.path("AllowanceSequenceNumber").asText("1"));
        x.put("Description", item.path("OriginalDescription").asText());
        x.put("Quantity", item.path("Quantity").asText());
        x.put("Unit", "");
        x.put("UnitPrice", item.path("UnitPrice").asText());
        x.put("Amount", item.path("Amount").asText());
        x.put("Tax", item.path("Tax").asText());
        x.put("DType", dType(item.path("TaxType").asText()));
        x.close("ProductItem");
        x.close("Details");
        return x.done();
    }

    /** G0501 → 鲸跃作废折让（UME {@code SoapCancelAllowanceData}：AllowanceNumber / AllowanceDate / BuyerId / CancelDate / CancelTime / Reason / Remark）。 */
    static String cancelAllowanceXml(JsonNode m) {
        var x = new Xml("Allowance");
        x.put("AllowanceNumber", m.path("CancelAllowanceNumber").asText());
        x.put("AllowanceDate", day(m.path("AllowanceDate").asText()));
        x.put("BuyerId", m.path("BuyerId").asText(CONSUMER_ID));
        x.put("CancelDate", day(m.path("CancelDate").asText()));
        x.put("CancelTime", m.path("CancelTime").asText());
        x.put("Reason", m.path("CancelReason").asText());
        x.put("Remark", "");
        return x.done();
    }

    /** MIG 课税别 → 鲸跃明细 DType：应税留空，免税 TN。 */
    private static String dType(String migTaxType) {
        return "3".equals(migTaxType) ? "TN" : "";
    }

    /** {@code 20261121} → {@code 2026/11/21}。 */
    static String day(String yyyymmdd) {
        if (yyyymmdd.length() != 8) return yyyymmdd;
        return yyyymmdd.substring(0, 4) + "/" + yyyymmdd.substring(4, 6) + "/" + yyyymmdd.substring(6);
    }

    /** 只拼固定元素名，值一律转义。 */
    private static final class Xml {
        private final StringBuilder out = new StringBuilder();
        private final String root;

        Xml(String root) {
            this.root = root;
            out.append('<').append(root).append('>');
        }

        void put(String name, String value) {
            out.append('<').append(name).append('>').append(CetustekSoapClient.escape(value == null ? "" : value))
                    .append("</").append(name).append('>');
        }

        void open(String name) {
            out.append('<').append(name).append('>');
        }

        void close(String name) {
            out.append("</").append(name).append('>');
        }

        String done() {
            return out.append("</").append(root).append('>').toString();
        }
    }
}
