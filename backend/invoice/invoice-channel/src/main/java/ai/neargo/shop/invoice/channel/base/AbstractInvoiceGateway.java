package ai.neargo.shop.invoice.channel.base;

import ai.neargo.shop.invoice.gateway.InvoiceGateway;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayReceipt.Outcome;
import ai.neargo.shop.invoice.gateway.ProviderException;
import ai.neargo.shop.invoice.gateway.ProviderOperation;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 所有供应商的模板基类（TDD-发票模块 §19；TDD-发票-加值中心模板-鲸跃与汎宇 §1）。两个入口都是 final：
 *
 * <ul>
 *   <li><b>上传</b> {@link #send}：账号核对 → 解析报文 → 交给厂商 {@link #upload} → 异常归类 → 留痕</li>
 *   <li><b>能力动作</b> {@link #call}（核验、领号、查单 …）：账号核对 → 厂商组请求并经形态层送出、解读回包 →
 *       按码表判定 → 留痕 → 成功交给厂商读数据，失败抛 {@link ProviderException}</li>
 * </ul>
 *
 * <p><b>不核对市场</b>：用哪家供应商由系统统一决定（商家选择或系统指定，落在主体配置上），
 * 供应商层只管把交来的报文送出去。
 *
 * <p>上传的异常归类：
 * <ul>
 *   <li>要账号却没给 → {@code ACCOUNT_PROBLEM}（上传任务据此暂停该主体）</li>
 *   <li>{@link AccountMisconfiguredException}（密钥长度不对、解不开回包）→ {@code ACCOUNT_PROBLEM}</li>
 *   <li>{@link UnsupportedMessageException}（厂商不支持这类票，如零税率）→ {@code FINAL}，不送半对的票</li>
 *   <li>{@link ProviderException}（不可达、HTTP 非 2xx）→ {@code RETRYABLE}，原报文留着下一轮补发；
 *       可能已送达的情况由查单对账兜底（§16），供应商对同一号码的重复上传回「已处理过」</li>
 * </ul>
 *
 * <p><b>留痕</b>：每次调用记一行（供应商、动作、账号代号、结果码、耗时）；线上原文只在 DEBUG 级别记，
 * 且账号的密钥值（{@link #secretValues}）一律打码 —— 打码写在基类而不是让厂商各自记得，漏一次就是一次凭据泄露。
 *
 * 子类按对接形态再分（SOAP / 明文 JSON / 加密接口 / 签名接口 / 网络接入点 / 文件交换），厂商类只写映射、码表与能力。
 */
public abstract class AbstractInvoiceGateway implements InvoiceGateway {

    private static final Logger log = LoggerFactory.getLogger(AbstractInvoiceGateway.class);
    static final String MASK = "******";

    protected final ObjectMapper json;

    protected AbstractInvoiceGateway(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public final GatewayReceipt send(GatewayRequest request) {
        long start = System.nanoTime();
        GatewayReceipt receipt;
        if (profile().requiresAccount() && request.account() == null) {
            receipt = GatewayReceipt.accountProblem("provider account not configured");
        } else if (!profile().accepts(ProviderOperation.ofMessageType(request.messageType()))) {
            receipt = GatewayReceipt.rejected("unsupported message type: " + request.messageType());
        } else {
            try {
                receipt = deliver(request, prepare(request, json.readTree(request.payloadJson())));
            } catch (UnsupportedMessageException e) {
                receipt = GatewayReceipt.rejected(e.getMessage());
            } catch (AccountMisconfiguredException e) {
                receipt = GatewayReceipt.accountProblem(tag() + " " + e.getMessage());
            } catch (ProviderException e) {
                String tag = tag() + " " + e.code();
                receipt = switch (e.kind()) {
                    case ACCOUNT -> GatewayReceipt.accountProblem(tag);
                    case UNSUPPORTED, REJECTED -> GatewayReceipt.rejected(tag);
                    default -> GatewayReceipt.retryable(tag);
                };
            }
        }
        log.info("[invoice] {} {} ref={} account={} → {} {} ({}ms)", code(), request.messageType(), request.refNo(),
                accountCode(request.account()), receipt.outcome(),
                receipt.accepted() ? receipt.gatewayRef() : receipt.error(), elapsed(start));
        return receipt;
    }

    /**
     * 预检（TDD-发票-供应商能力定义完善 D2）：不出网，只跑 {@link #prepare}。报文种类不在声明里、或映射阶段拒绝（如零税率）时返回原因。
     * 预检时没有账号也照样映射：用占位账号，账号字段不参与业务判断。
     */
    @Override
    public final Optional<String> preflight(GatewayRequest request) {
        if (!profile().accepts(ProviderOperation.ofMessageType(request.messageType())))
            return Optional.of("unsupported message type: " + request.messageType());
        var r = request.account() != null ? request : new GatewayRequest(request.regimeCode(), request.messageType(),
                request.refNo(), request.payloadJson(), PREFLIGHT_ACCOUNT);
        try {
            prepare(r, json.readTree(r.payloadJson()));
            return Optional.empty();
        } catch (UnsupportedMessageException e) {
            return Optional.of(e.getMessage());
        }
    }

    static final ProviderAccount PREFLIGHT_ACCOUNT = new ProviderAccount("PREFLIGHT", "PREFLIGHT", "PREFLIGHT");

    /**
     * 第一步 · 准备（纯映射，不出网）：税制报文 → 厂商报文。不支持的内容抛 {@link UnsupportedMessageException}。
     * 默认原样交给 {@link #deliver}；厂商在这里放映射，预检与真实上传共用。
     */
    protected Prepared prepare(GatewayRequest request, JsonNode message) {
        return new Prepared(message, null);
    }

    /** 第二步 · 送出并判码。默认走旧的 {@link #upload}（文件交换、测试桩、样例形态仍覆盖它）。 */
    protected GatewayReceipt deliver(GatewayRequest request, Prepared prepared) {
        return upload(request, prepared.message());
    }

    /** 旧的一步式上传；改为覆盖 {@link #prepare} + {@link #deliver} 的厂商不必实现。 */
    protected GatewayReceipt upload(GatewayRequest request, JsonNode message) {
        throw new UnsupportedOperationException(code() + " must override deliver or upload");
    }

    /** @param payload 厂商报文（汎宇 Map、鲸跃 XML 文本 …），由 {@link #prepare} 产出、{@link #deliver} 使用 */
    public record Prepared(JsonNode message, Object payload) {}

    /**
     * 能力动作的模板。厂商的能力方法只写一行：{@code return call(op, account, () -> 送出并解读, reply -> 读数据)}。
     *
     * @param exchange 厂商组请求、经形态层送出、把回包解读成 {@link ProviderReply}（结果码 + 数据）
     * @param read     判定为成功后，从回包读出能力接口要的结果；读不出时自己抛 {@link ProviderException}
     * @throws ProviderException 没配账号（{@code account_missing}）、不可达（形态层抛出）、结果码非成功（{@link #failureCode}）
     */
    protected final <T> T call(ProviderOperation op, ProviderAccount account, Supplier<ProviderReply> exchange,
            Function<ProviderReply, T> read) {
        Objects.requireNonNull(op, "op");
        long start = System.nanoTime();
        if (profile().requiresAccount() && account == null) {
            log.warn("[invoice] {} {} account=- → account_missing", code(), op);
            throw new ProviderException(ProviderException.Kind.ACCOUNT, "account_missing", null, null);
        }
        ProviderReply reply;
        try {
            reply = exchange.get();
        } catch (ProviderException e) {
            log.warn("[invoice] {} {} account={} → {} ({}ms)", code(), op, accountCode(account), e.code(), elapsed(start));
            throw e;
        }
        Outcome outcome = resultCodes().classify(op.name(), reply.code());
        log.info("[invoice] {} {} account={} → {} {} ({}ms)", code(), op, accountCode(account), outcome, reply.code(),
                elapsed(start));
        if (outcome != Outcome.ACCEPTED)
            throw new ProviderException(failureKind(op, reply, outcome), failureCode(op, reply), failureDetail(op, reply),
                    null);
        return read.apply(reply);
    }

    /**
     * 能力动作失败的分类（D4）：默认按码表 —— 账号问题 → {@link ProviderException.Kind#ACCOUNT}、可重试 →
     * {@code UNAVAILABLE}、其余 → {@code REJECTED}。厂商可改写特例（查无 → {@code NOT_FOUND}）。
     */
    protected ProviderException.Kind failureKind(ProviderOperation op, ProviderReply reply, Outcome outcome) {
        return switch (outcome) {
            case ACCOUNT_PROBLEM -> ProviderException.Kind.ACCOUNT;
            case RETRYABLE -> ProviderException.Kind.UNAVAILABLE;
            default -> ProviderException.Kind.REJECTED;
        };
    }

    /** 能力动作失败时的厂商原文说明（如汎宇 MESSAGE）；默认没有。 */
    protected String failureDetail(ProviderOperation op, ProviderReply reply) {
        return null;
    }

    /** 能力动作失败时抛给领域的代码；默认是厂商结果码的小写（便于对着厂商文档查）。厂商可按动作改写成统一的词。 */
    protected String failureCode(ProviderOperation op, ProviderReply reply) {
        return reply.code() == null ? "unexpected" : reply.code().toLowerCase(Locale.ROOT);
    }

    /** 厂商的结果码表；没有同步结果码的形态（文件交换）可不覆盖。 */
    protected ResultCodeTable resultCodes() {
        return ResultCodeTable.builder().build();
    }

    /** 留痕时要打码的值；默认账号的密钥标识与密钥都打码（鲸跃 posno / posid，汎宇 POSID / POSSN）。 */
    protected Set<String> secretValues(ProviderAccount account) {
        if (account == null) return Set.of();
        var s = new java.util.HashSet<String>();
        if (account.keyId() != null && !account.keyId().isBlank()) s.add(account.keyId());
        if (account.secret() != null && !account.secret().isBlank()) s.add(account.secret());
        return s;
    }

    /** 形态层送出后调：线上原文按 DEBUG 记，密钥值打码。 */
    protected final void traceWire(String operation, ProviderAccount account, String request, String response) {
        if (!log.isDebugEnabled()) return;
        log.debug("[invoice] {} {} >>> {}", code(), operation, mask(request, account));
        log.debug("[invoice] {} {} <<< {}", code(), operation, mask(response, account));
    }

    /** 把文本里出现的密钥值替换成掩码；XML 转义后的写法也一并打码（SOAP 信封里是转义后的值）。 */
    protected final String mask(String text, ProviderAccount account) {
        if (text == null) return null;
        String out = text;
        for (String v : secretValues(account)) {
            String escaped = SoapEnvelope.escape(v);
            if (!escaped.equals(v)) out = out.replace(escaped, MASK);
            out = out.replace(v, MASK);
        }
        return out;
    }

    /** 按码表把结果码变成回执；成功的回执号形如 {@code CETUSTEK:S0:INV-1}。 */
    protected final GatewayReceipt receipt(String messageType, String resultCode, String refNo) {
        String code = ResultCodeTable.normalize(resultCode);
        String tag = tag() + " " + resultCode;
        return switch (resultCodes().classify(messageType, resultCode)) {
            case ACCEPTED -> GatewayReceipt.accepted(code() + ":" + code + ":" + refNo);
            case RETRYABLE -> GatewayReceipt.retryable(tag);
            case ACCOUNT_PROBLEM -> GatewayReceipt.accountProblem(tag);
            case FINAL -> GatewayReceipt.rejected(tag);
        };
    }

    private String tag() {
        return code().toLowerCase(Locale.ROOT);
    }

    private static String accountCode(ProviderAccount account) {
        return account == null ? "-" : account.accountCode();
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * 厂商回包的标准化：结果码 + 供应商单号 + 标准数据（厂商自定键）+ 原文。
     * 原文只给厂商的 {@code read} 用（如鲸跃 XML），不进日志。
     */
    public record ProviderReply(String code, String providerRef, Map<String, String> data, String raw) {
        public ProviderReply {
            data = data == null ? Map.of() : Map.copyOf(data);
        }

        public static ProviderReply of(String code, String raw) {
            return new ProviderReply(code, null, Map.of(), raw);
        }

        @Override
        public String toString() {
            return "ProviderReply[" + code + "]";
        }
    }

    /** 厂商不支持这类报文（映射阶段拒绝）。 */
    public static class UnsupportedMessageException extends RuntimeException {
        public UnsupportedMessageException(String message) {
            super(message);
        }
    }

    /** 账号里的密钥配错（长度不对、解不开回包）：重发多少次都一样，按账号问题暂停该主体。 */
    public static class AccountMisconfiguredException extends RuntimeException {
        public AccountMisconfiguredException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
