package ai.neargo.shop.invoice.channel.cetustek;

import ai.neargo.shop.invoice.channel.base.AbstractSoapInvoiceGateway;
import ai.neargo.shop.invoice.channel.base.HttpTransport;
import ai.neargo.shop.invoice.channel.base.ResultCodeTable;
import ai.neargo.shop.invoice.channel.base.SoapEnvelope;
import ai.neargo.shop.invoice.gateway.AccountCapability;
import ai.neargo.shop.invoice.gateway.CredentialField;
import ai.neargo.shop.invoice.gateway.InvoiceFeature;
import ai.neargo.shop.invoice.channel.tw.TwMappingRules;
import ai.neargo.shop.invoice.channel.tw.TwMappingRules.IssueMode;
import ai.neargo.shop.invoice.gateway.ProviderException;
import ai.neargo.shop.invoice.gateway.ProviderOperation;
import ai.neargo.shop.invoice.gateway.ProviderProfile;
import ai.neargo.shop.invoice.gateway.ProviderProfile.NumberingMode;
import ai.neargo.shop.invoice.gateway.ProviderProfile.ReportMode;
import ai.neargo.shop.invoice.gateway.QrKeySource;
import ai.neargo.shop.invoice.gateway.QueryCapability;
import ai.neargo.shop.invoice.gateway.RangeCapability;
import ai.neargo.shop.invoice.gateway.RangeWindow;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 鯨躍（Cetustek）加值中心，代码 {@code CETUSTEK}，台湾 · SOAP 形态（{@link AbstractSoapInvoiceGateway}）。
 * 协议与字段沿用 UME invoice-web 的 {@code CetustekInvoiceServiceImpl}：开立 UploadInvoice、作废 UploadCancelInvoice、
 * 折让 UploadAllowance、折让作废 UploadCancelAllowance，账号核验 QueryOfficeInfo，号段 ApplyInvoiceNumber →
 * TakeInvoiceNumber，查单 QueryInvoiceStatus。
 *
 * <p><b>报文</b>：税制给的是 MIG 树（见 {@code TwEguiRegime#message}），这里按鲸跃的扁平 XML 重排：
 * 日期 {@code yyyy/MM/dd}；B2C 不带买方识别码；纸本 / 载具 / 捐赠的 DonateMark 分别是 2 / 0 / 1；
 * 免税明细 DType=TN。零税率鲸跃另有通关方式栏位，首期不支持，映射阶段判失败留在任务里，不送出半对的票。
 *
 * <p><b>结果码</b>：见 {@link #CODES}。同一报文重发时鲸跃回「已上传过」（S2 / C5 / A6 / C4），视为送达；
 * 认证失败（invalid）交给上传任务暂停该主体；不认识的码不自动重发。
 *
 * <p><b>网络</b>：鲸跃只放行台湾出口（本机直连 TCP 被拒），线上实例部署在台湾机房；
 * 地址取 {@code shop.invoice.cetustek.url}，默认 UME 一直在用的正式地址。经 SSH 隧道从台湾机器转发时，
 * 地址写隧道本地端口，并配 {@code shop.invoice.cetustek.host-header=invoice.cetustek.com.tw}（鲸跃按虚拟主机区分站点）。
 */
@Component
public class CetustekInvoiceGateway extends AbstractSoapInvoiceGateway
        implements AccountCapability, RangeCapability, QueryCapability {

    static final String CODE = "CETUSTEK";
    /**
     * 能力声明（TDD-发票-供应商能力定义完善）：收四类标准报文（没有注销）；零税率不支持（缺通关方式栏位，映射阶段拒绝）；
     * 凭据 officeid / posno / posid（后两项只写不读）；QR 金钥在核验时回传；号段任意期别可领。
     */
    static final ProviderProfile PROFILE =
            new ProviderProfile(Set.of("TW_EGUI"), NumberingMode.PROVIDER_RANGE, ReportMode.ASYNC_UPLOAD, true)
                    .withUploads(ProviderProfile.STANDARD_UPLOADS)
                    .withFeatures(EnumSet.complementOf(EnumSet.of(InvoiceFeature.ZERO_RATED)))
                    .withCredentials(List.of(
                            new CredentialField(CredentialField.Slot.ACCOUNT_CODE, "officeId", false, null, null),
                            new CredentialField(CredentialField.Slot.KEY_ID, "posNo", true, null, null),
                            new CredentialField(CredentialField.Slot.SECRET, "posId", true, null, null)))
                    .withQrKeySource(QrKeySource.VERIFY)
                    .withRangeWindows(Set.of(RangeWindow.ANY));

    /** 能力回包解读为成功时的内部码（鲸跃没有成功码，成功即数据本身）。 */
    static final String OK = "OK";

    /**
     * 照 UME {@code StatusCode} 与鲸跃回码说明逐行转写。{@code S1 / C1 / A1}（「上传失败」，语义待鲸跃确认）未登记，
     * 按需人工处理；确认可重试后再改表。折让作废 C3 =「没有该折让单号码可作废」同样按需人工。
     */
    static final ResultCodeTable CODES = ResultCodeTable.builder()
            .acceptedFor("ISSUE", "S0", "S2")
            .acceptedFor("VOID", "C0", "C5")
            .acceptedFor("ALLOWANCE", "A0", "A6")
            .acceptedFor("ALLOWANCE_VOID", "C0", "C4")
            .acceptedFor("VERIFY", OK)      // 能力动作：回包解读为成功（见 reply）
            .acceptedFor("RANGE", OK)
            .acceptedFor("QUERY", OK)
            .accountProblem("invalid")      // 认证错误：officeid / posno / posid 不对
            .retryable("error")             // 鲸跃系统错误
            .build();

    private static final String NS = "http://webservice.cetustek.com/";
    private static final Set<String> FAILURES = Set.of("ERROR", "INVALID", "EXIST", "NODATA", "NOTAKE");
    private static final String CONSUMER_ID = TwMappingRules.CONSUMER_ID;

    private final String hostHeader;

    @Autowired
    public CetustekInvoiceGateway(
            @Value("${shop.invoice.cetustek.url:http://invoice.cetustek.com.tw/InvoiceEntitySync/InvoiceAPI}") String url,
            @Value("${shop.invoice.cetustek.timeout-seconds:20}") int timeoutSeconds,
            @Value("${shop.invoice.cetustek.host-header:}") String hostHeader, ObjectMapper json) {
        this(URI.create(url), Duration.ofSeconds(timeoutSeconds), hostHeader, json);
    }

    CetustekInvoiceGateway(URI endpoint, Duration timeout, ObjectMapper json) {
        this(endpoint, timeout, "", json);
    }

    CetustekInvoiceGateway(URI endpoint, Duration timeout, String hostHeader, ObjectMapper json) {
        super(endpoint, new HttpTransport(timeout), json);
        this.hostHeader = hostHeader == null ? "" : hostHeader.trim();
    }

    @Override
    protected Map<String, String> extraHeaders() {
        return hostHeader.isEmpty() ? Map.of() : Map.of("Host", hostHeader);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public ProviderProfile profile() {
        return PROFILE;
    }

    @Override
    protected ResultCodeTable resultCodes() {
        return CODES;
    }

    @Override
    protected String namespace() {
        return NS;
    }

    @Override
    protected LinkedHashMap<String, String> authFields(ProviderAccount account) {
        var f = new LinkedHashMap<String, String>();
        f.put("officeid", account.accountCode());
        f.put("posno", account.keyId());
        f.put("posid", account.secret());
        return f;
    }

    @Override
    protected Prepared prepare(GatewayRequest request, JsonNode m) {
        String type = request.messageType();
        var call = switch (type) {
            case "ISSUE" -> new SoapCall("UploadInvoice", Map.of("invoicexml", invoiceXml(m, request.refNo())));
            case "VOID" -> new SoapCall("UploadCancelInvoice", Map.of("invoicexml", cancelXml(m)));
            case "ALLOWANCE" -> new SoapCall("UploadAllowance", Map.of("allowancexml", allowanceXml(m)));
            case "ALLOWANCE_VOID" -> new SoapCall("UploadCancelAllowance", Map.of("allowancexml", cancelAllowanceXml(m)));
            default -> throw new UnsupportedMessageException("Unsupported message type: " + type);
        };
        return new Prepared(m, call);
    }

    @Override
    protected GatewayReceipt deliver(GatewayRequest request, Prepared prepared) {
        var call = (SoapCall) prepared.payload();
        return receipt(request.messageType(), soap(call.operation(), request.account(), call.fields()), request.refNo());
    }

    /** 一次 SOAP 上传：操作名 + 参数（内层 XML 已按鲸跃格式排好）。 */
    record SoapCall(String operation, Map<String, String> fields) {}

    /** 失败分类：nodata（查无此票、无号可领）→ 查无；其余按码表。 */
    @Override
    protected ProviderException.Kind failureKind(ProviderOperation op, ProviderReply reply,
            GatewayReceipt.Outcome outcome) {
        if ("NODATA".equalsIgnoreCase(reply.code())) return ProviderException.Kind.NOT_FOUND;
        return super.failureKind(op, reply, outcome);
    }

    /** 账号核验：QueryOfficeInfo 回营业人 XML（统编、名称、QRCode）或失败词。 */
    @Override
    public ProviderOffice verify(ProviderAccount account) {
        return call(ProviderOperation.VERIFY, account, () -> reply(soap("QueryOfficeInfo", account, Map.of())), r -> {
            String id = SoapEnvelope.between(r.raw(), "<ID>", "</ID>");
            if (id == null) throw new ProviderException("unexpected", null);
            return new ProviderOffice(id.trim(), text(r.raw(), "Name"), text(r.raw(), "QRCode"));
        });
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
        return call(ProviderOperation.QUERY, account, () -> {
            String result = soap("QueryInvoiceStatus", account, fields);
            String code = switch (result.toUpperCase(Locale.ROOT)) {
                case "0", "1", "2", "3", "NODATA" -> OK;
                case "INVALID", "ERROR" -> result;
                default -> "unexpected:" + result;     // 不认识的回码不猜：未登记 → 终态
            };
            return ProviderReply.of(code, result);
        }, r -> new ProviderInvoiceStatus(switch (r.raw().toUpperCase(Locale.ROOT)) {
            case "0" -> ProviderInvoiceStatus.State.ISSUED;
            case "1" -> ProviderInvoiceStatus.State.VOIDED;
            case "2" -> ProviderInvoiceStatus.State.PARTIALLY_ALLOWED;
            case "3" -> ProviderInvoiceStatus.State.FULLY_ALLOWED;
            default -> ProviderInvoiceStatus.State.NOT_FOUND;
        }, r.raw()));
    }

    /** 领号段两步：ApplyInvoiceNumber 取申请号 → TakeInvoiceNumber 取 {@code 字轨;起;迄;类别}。两步都走能力模板。 */
    @Override
    public AllocatedRange allocate(ProviderAccount account, String periodCode) {
        int even = Integer.parseInt(periodCode.substring(3));
        var apply = new LinkedHashMap<String, String>();
        apply.put("year", periodCode.substring(0, 3));
        apply.put("month", "%02d-%02d".formatted(even - 1, even));
        String applyId = call(ProviderOperation.RANGE, account,
                () -> reply(soap("ApplyInvoiceNumber", account, apply)), ProviderReply::raw);
        return call(ProviderOperation.RANGE, account,
                () -> reply(soap("TakeInvoiceNumber", account, Map.of("applyid", applyId))), r -> {
                    // prefix;start;end;invoiceType，如 AB;12345600;12345649;07
                    String[] parts = r.raw().split(";");
                    if (parts.length < 3) throw new ProviderException("unexpected", null);
                    try {
                        return new AllocatedRange(applyId, parts[0].trim(), Integer.parseInt(parts[1].trim()),
                                Integer.parseInt(parts[2].trim()), parts.length > 3 ? parts[3].trim() : null);
                    } catch (NumberFormatException e) {
                        throw new ProviderException("unexpected", e);
                    }
                });
    }

    /** 鲸跃的能力回包：失败时整串就是失败词（invalid / error / exist / nodata / notake），空回包按 nodata；其余为成功的数据。 */
    private static ProviderReply reply(String result) {
        if (result.isEmpty()) return ProviderReply.of("nodata", result);
        if (FAILURES.contains(result.toUpperCase(Locale.ROOT))) return ProviderReply.of(result, result);
        return ProviderReply.of(OK, result);
    }

    private static String text(String xml, String tag) {
        String v = SoapEnvelope.between(xml, "<" + tag + ">", "</" + tag + ">");
        return v == null || v.isBlank() ? null : v.trim();
    }

    // ---- MIG 树 → 鲸跃 XML ----

    static String invoiceXml(JsonNode m, String orderId) {
        JsonNode main = m.path("Main"), amount = m.path("Amount"), buyer = main.path("Buyer");
        if (TwMappingRules.zeroRated(m))
            throw new UnsupportedMessageException("zero-rated invoices are not supported on Cetustek");
        String date = day(main.path("InvoiceDate").asText());
        var x = new Xml("Invoice");
        x.put("InvoiceNumber", main.path("InvoiceNumber").asText());
        x.put("InvoiceDate", date);
        x.put("InvoiceTime", main.path("InvoiceTime").asText());
        x.put("OrderId", orderId);
        x.put("OrderDate", date);
        if (TwMappingRules.b2b(buyer)) {
            x.put("BuyerIdentifier", buyer.path("Identifier").asText());
            x.put("BuyerName", buyer.path("Name").asText());
        }
        x.put("InvoiceType", main.path("InvoiceType").asText("07"));
        x.put("TaxType", amount.path("TaxType").asText());
        x.put("TaxRate", amount.path("TaxRate").asText("0.05"));
        var mode = TwMappingRules.issueMode(main);
        boolean donate = mode == IssueMode.DONATE, carrier = mode == IssueMode.CARRIER;
        x.put("DonateMark", donate ? "1" : carrier ? "0" : "2");
        if (carrier) {
            x.put("CarrierType", main.path("CarrierType").asText());
            x.put("CarrierId1", main.path("CarrierId1").asText());
            x.put("CarrierId2", main.path("CarrierId2").asText());
        }
        if (donate) x.put("NPOBAN", main.path("NPOBAN").asText());
        x.put("RandomNumber", main.path("RandomNumber").asText());
        // 与 UME 一致送空值：鲸跃正式环境对 "N" 回 M:MailSend（2026-11-22 實測拒收）
        x.put("MailSend", "");
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
            throw new UnsupportedMessageException("zero-rated allowances are not supported on Cetustek");
        var x = new Xml("Allowance");
        x.put("AllowanceNumber", main.path("AllowanceNumber").asText());
        x.put("AllowanceDate", day(main.path("AllowanceDate").asText()));
        x.put("InvoiceNumber", item.path("OriginalInvoiceNumber").asText());
        x.put("InvoiceDate", day(item.path("OriginalInvoiceDate").asText()));
        x.put("InvoiceTime", m.path("ext").path("OriginalInvoiceTime").asText(""));
        if (TwMappingRules.b2b(buyer)) {
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
        return TwMappingRules.date(yyyymmdd, '/');
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
            out.append('<').append(name).append('>').append(SoapEnvelope.escape(value == null ? "" : value))
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
