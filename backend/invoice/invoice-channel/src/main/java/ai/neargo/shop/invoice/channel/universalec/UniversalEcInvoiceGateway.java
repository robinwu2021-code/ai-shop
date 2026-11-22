package ai.neargo.shop.invoice.channel.universalec;

import ai.neargo.shop.invoice.channel.base.AbstractPlainJsonInvoiceGateway;
import ai.neargo.shop.invoice.channel.base.HttpTransport;
import ai.neargo.shop.invoice.channel.base.ResultCodeTable;
import ai.neargo.shop.invoice.gateway.AccountCapability;
import ai.neargo.shop.invoice.gateway.BlankRangeCapability;
import ai.neargo.shop.invoice.gateway.ProviderException;
import ai.neargo.shop.invoice.gateway.ProviderOperation;
import ai.neargo.shop.invoice.gateway.ProviderProfile;
import ai.neargo.shop.invoice.gateway.ProviderProfile.NumberingMode;
import ai.neargo.shop.invoice.gateway.ProviderProfile.ReportMode;
import ai.neargo.shop.invoice.gateway.RangeCapability;
import ai.neargo.shop.invoice.gateway.TrackCapability;
import ai.neargo.shop.invoice.regime.tw.TwPeriods;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 汎宇電商（einvoice.com.tw）加值中心，代码 {@code UNIVERSALEC}，台湾 · 明文 JSON 形态（{@link AbstractPlainJsonInvoiceGateway}）。
 * 依据《電子發票POS機傳輸規格書 JSON MIG4.1》（TDD-发票-汎宇接入、TDD-发票-加值中心模板-鲸跃与汎宇）。
 *
 * <p><b>账号</b>：{@code accountCode} = SELLERID（营业人统编），{@code keyId} = POSID，{@code secret} = POSSN（通道金钥）。
 *
 * <p><b>动作</b>：开立 C0401、作废 C0501、折让 D0401N、折让作废 D0501（报文见 {@link UniversalEcMessages}）；
 * 核验 Y01；领号段当期 A01 / 下期 C01；字轨管理 Z33 配号方式 / Z31 已下载字轨 / Z32 上传下期字轨；空白字轨 E0402。
 * 汎宇没有发票状态查询，不实现查单；注销 C0701 / Z11 另起一期。
 *
 * <p><b>地址</b>：{@code shop.invoice.universalec.url}，默认测试机；正式机为 {@code https://eposw.einvoice.com.tw/GetInvoice.ashx}。
 */
@Component
public class UniversalEcInvoiceGateway extends AbstractPlainJsonInvoiceGateway
        implements AccountCapability, RangeCapability, TrackCapability, BlankRangeCapability {

    static final String CODE = "UNIVERSALEC";
    static final ProviderProfile PROFILE =
            new ProviderProfile(Set.of("TW_EGUI"), NumberingMode.PROVIDER_RANGE, ReportMode.ASYNC_UPLOAD, true);

    /**
     * 照规格书「ERROR_CODE 回傳訊息代號說明」逐行转写。重发时的「已存在」（开立 E02、作废 E07）视为送达；
     * REPLY=0 而没给码（如「取號時間過長」）记作 {@code REPLY0} 可重试；其余与未登记的码不自动重发。
     */
    static final ResultCodeTable CODES = ResultCodeTable.builder()
            .accepted("0000")
            .acceptedFor("ISSUE", "E02")
            .acceptedFor("VOID", "E07")
            .accountProblem("S020", "S022", "S024", "S026")
            .retryable("S010", "S012", "S030", "S032", "S034", "REPLY0")
            .build();

    private static final DateTimeFormatter SYSTIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(TwPeriods.TAIPEI);

    private final Clock clock;

    @Autowired
    public UniversalEcInvoiceGateway(
            @Value("${shop.invoice.universalec.url:https://epostw.einvoice.com.tw/GetInvoice.ashx}") String url,
            @Value("${shop.invoice.universalec.timeout-seconds:20}") int timeoutSeconds, ObjectMapper json) {
        this(URI.create(url), Duration.ofSeconds(timeoutSeconds), json, Clock.systemUTC());
    }

    UniversalEcInvoiceGateway(URI endpoint, Duration timeout, ObjectMapper json, Clock clock) {
        super(endpoint, new HttpTransport(timeout), json);
        this.clock = clock;
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
    protected String replyRoot() {
        return "INDEX";
    }

    @Override
    protected String codeField() {
        return "ERROR_CODE";
    }

    @Override
    protected String replyField() {
        return "REPLY";
    }

    @Override
    protected String successCode() {
        return "0000";
    }

    @Override
    protected String referenceField() {
        return "INVOICENUMBER";
    }

    @Override
    protected GatewayReceipt upload(GatewayRequest request, JsonNode message) {
        String type = request.messageType();
        ProviderAccount account = request.account();
        Map<String, Object> payload = switch (type) {
            case "ISSUE" -> UniversalEcMessages.issue(message, account, now());
            case "VOID" -> UniversalEcMessages.voidInvoice(message, account, now());
            case "ALLOWANCE" -> UniversalEcMessages.allowance(message, account, now());
            case "ALLOWANCE_VOID" -> UniversalEcMessages.voidAllowance(message, account, now());
            default -> throw new UnsupportedMessageException("Unsupported message type: " + type);
        };
        return receipt(type, exchange(account, type, payload).code(), request.refNo());
    }

    /** Y01 取系统时间：汎宇确认 POS 已注册且未停用即回 REPLY=1。 */
    @Override
    public ProviderOffice verify(ProviderAccount account) {
        return call(ProviderOperation.VERIFY, account,
                () -> exchange(account, "Y01", UniversalEcMessages.index("Y01", account, now())),
                r -> new ProviderOffice(r.data().getOrDefault("SELLERID", account.accountCode()), null, null));
    }

    /**
     * 当期走 A01、下一期走 C01；其它期别汎宇没有对应接口，不调用。汎宇不回申请号，
     * 以「供应商 + 期别 + 字轨 + 起号」作申请号，同一段重复取回由领域按号段去重。
     */
    @Override
    public AllocatedRange allocate(ProviderAccount account, String periodCode) {
        String current = TwPeriods.periodOf(clock.instant());
        String next = TwPeriods.periodOf(TwPeriods.periodEnd(current));
        String fn = periodCode.equals(current) ? "A01" : periodCode.equals(next) ? "C01" : null;
        if (fn == null) throw new ProviderException("period_unsupported", null);
        return call(ProviderOperation.RANGE, account,
                () -> exchange(account, fn, UniversalEcMessages.index(fn, account, now())), r -> {
                    var d = r.data();
                    if (!periodCode.equals(d.get("TAXMONTH"))) throw new ProviderException("period_mismatch", null);
                    String track = d.get("INVOICEHEADER");
                    try {
                        String start = d.get("INVOICESTART").trim();
                        return new AllocatedRange(CODE + ":" + periodCode + ":" + track + start, track,
                                Integer.parseInt(start), Integer.parseInt(d.get("INVOICEEND").trim()), d.get("TYPE"));
                    } catch (RuntimeException e) {
                        throw new ProviderException("unexpected", e);
                    }
                });
    }

    /** Z33：1 自动配号、2 手动配号、0 或「查無」为无配号记录。 */
    @Override
    public AllocationMode allocationMode(ProviderAccount account, String periodCode) {
        return call(ProviderOperation.TRACK_MODE, account,
                () -> noDataAsEmpty(exchange(account, "Z33",
                        UniversalEcMessages.periodQuery("Z33", periodCode, account, now()))),
                r -> switch (r.data().getOrDefault("INVSTATUS", "0")) {
                    case "1" -> AllocationMode.AUTO;
                    case "2" -> AllocationMode.MANUAL;
                    default -> AllocationMode.NONE;
                });
    }

    /** Z31：回 INVOICE_DATA 数组；没有资料时汎宇填字轨 00 / 号码 00000000，过滤掉。 */
    @Override
    public List<TrackRange> downloadedTracks(ProviderAccount account, String periodCode) {
        return call(ProviderOperation.TRACK_QUERY, account,
                () -> noDataAsEmpty(exchange(account, "Z31",
                        UniversalEcMessages.periodQuery("Z31", periodCode, account, now()))),
                r -> {
                    String data = r.data().get("INVOICE_DATA");
                    if (data == null) return List.of();
                    var out = new ArrayList<TrackRange>();
                    try {
                        for (JsonNode d : json.readTree(data)) {
                            String track = d.path("INVOICE_TRACK").asText();
                            if (track.isBlank() || "00".equals(track)) continue;
                            out.add(new TrackRange(d.path("TYPE").asText(null), track,
                                    Integer.parseInt(d.path("INVOICE_START").asText().trim()),
                                    Integer.parseInt(d.path("INVOICE_END").asText().trim())));
                        }
                    } catch (RuntimeException e) {
                        throw new ProviderException("unexpected", e);
                    }
                    return List.copyOf(out);
                });
    }

    /** Z32：只接受下一期（领域已校验区间）；汎宇收下后约 15 分钟排程配号。 */
    @Override
    public void uploadNextTracks(ProviderAccount account, TrackUpload upload) {
        call(ProviderOperation.TRACK_UPLOAD, account,
                () -> exchange(account, "Z32", UniversalEcMessages.uploadTracks(upload, account, now())), r -> null);
    }

    /** E0402：同一期别、字轨、发票类别的未用区间一次报。 */
    @Override
    public void reportBlank(ProviderAccount account, BlankReport report) {
        call(ProviderOperation.BLANK_REPORT, account,
                () -> exchange(account, "E0402", UniversalEcMessages.blankTracks(report, account)), r -> null);
    }

    /**
     * 汎宇的失败只有「REPLY + 中文说明」：核验失败统一报 invalid（账号），领号失败统一报 nodata（无配号可用）；
     * 其余动作有说明就把说明交给用户（如「失敗：字軌區間不存在」），便于对照汎宇文档。
     */
    @Override
    protected String failureCode(ProviderOperation op, ProviderReply reply) {
        return switch (op) {
            case VERIFY -> "invalid";
            case RANGE -> "nodata";
            default -> {
                String msg = reply.data().get("MESSAGE");
                yield msg == null ? super.failureCode(op, reply) : msg.length() > 120 ? msg.substring(0, 120) : msg;
            }
        };
    }

    /** 查询类动作汎宇用「失敗：查無…」表示没有资料：当作成功的空结果，而不是错误。 */
    private static ProviderReply noDataAsEmpty(ProviderReply r) {
        String msg = r.data().get("MESSAGE");
        if (!"0000".equals(r.code()) && msg != null && msg.contains("查無")) return new ProviderReply("0000", null, Map.of(), null);
        return r;
    }

    private String now() {
        return SYSTIME.format(clock.instant());
    }
}
