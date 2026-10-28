package ai.neargo.shop.pay.ops;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.KeyPurpose;
import ai.neargo.secret.api.KeyRegistry;
import ai.neargo.secret.api.KeyRegistry.KeySummary;
import ai.neargo.secret.api.KeyVersionRef;
import ai.neargo.secret.api.SecretException;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.entity.PayApiRequestLog;
import ai.neargo.shop.pay.checkout.entity.PayQrCode;
import ai.neargo.shop.pay.mapper.access.AccessMappers.PayApiRequestLogMapper;
import ai.neargo.shop.pay.mapper.checkout.CheckoutMappers.PayQrCodeMapper;
import ai.neargo.shop.pay.mapper.openapp.OpenAppMappers.PayAppGrantMapper;
import ai.neargo.shop.pay.mapper.openapp.OpenAppMappers.PayAppMapper;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import ai.neargo.shop.pay.openapp.entity.PayAppGrant;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 支付运营的接入与密钥视图（开发计划 M5b）：接入方与授权、接入方密钥（只看指纹 / 吊销）、请求记录、静态收款码、平台密钥。
 * 与 {@link PayOpsFacade} 同一条接法（ops-web → {@code shop-app} {@code /ops/payments/**} → {@code payclient} → 这里）。
 *
 * <p><b>没有一样材料出得去</b>：密钥只给指纹、序列号、状态与经手人；请求记录给的是写入时就已脱敏的报文。
 * 写操作（吊销、启用）的操作人显式传入；启用由密钥模块强制「启用人 ≠ 录入人」。
 */
@Component
public class PayAccessOpsFacade {

    /** 接入方页面看的两种密钥：签名用的 AppSecret、字段加密用的 EncryptKey */
    private static final Map<String, KeyPurpose> APP_CREDENTIALS = Map.of("APP_SECRET", KeyPurpose.INBOUND_HMAC,
            "ENCRYPT_KEY", KeyPurpose.PARTNER_DATA);
    /** 接入方的密钥在接入方页面看；平台密钥页只列其余归属（渠道平台证书、收款商户、平台加密私钥…） */
    private static final Set<String> APP_OWNER_TYPES = Set.of("PARTNER_APP", "INTERNAL_APP");
    private static final int MAX_PAGE_SIZE = 100;

    private final PayAppMapper apps;
    private final PayAppGrantMapper grants;
    private final PayApiRequestLogMapper logs;
    private final PayQrCodeMapper codes;
    private final ObjectProvider<KeyRegistry> keys;

    public PayAccessOpsFacade(PayAppMapper apps, PayAppGrantMapper grants, PayApiRequestLogMapper logs,
                              PayQrCodeMapper codes, ObjectProvider<KeyRegistry> keys) {
        this.apps = apps;
        this.grants = grants;
        this.logs = logs;
        this.codes = codes;
        this.keys = keys;
    }

    public record AppRow(String appNo, String name, String ownerType, String status, String scopes, boolean sandbox,
                         String signType, String webhookUrl, Integer rateLimitQps, Long dailyRefundLimitMinor,
                         int activeGrants) {
    }

    public record GrantRow(Long storeId, String actions, String status, String grantedBy, Long grantedAt, Long revokedAt) {
    }

    /** @param kind {@code APP_SECRET} / {@code ENCRYPT_KEY}；{@code serial} 是对外的 KeySerial（可空） */
    public record CredentialRow(String kind, int version, String status, String fingerprint, String serial,
                                String enteredBy) {
    }

    public record RequestLogFilter(String entry, String appNo, String sourceService, String traceId, String outcome,
                                   Long fromMs, Long toMs) {
    }

    public record RequestLogRow(Long id, String traceId, String entry, String api, String payAction, String appNo,
                                String operatorNo, String sourceService, String sourceModule, String sourceInstance,
                                String sourceIp, String originClient, String originApi, String clientIp,
                                Integer httpStatus, String resultCode, String outcome, Integer elapsedMs,
                                Integer signKeyVersion, Long receivedAt) {
    }

    /** 已脱敏的报文：写请求记录时就脱过，这里原样给 */
    public record RequestLogDetail(RequestLogRow row, String requestMasked, String responseMasked) {
    }

    public record QrRow(String qrCode, Long storeId, String kind, String tableRef, String currency, long singleLimitMinor,
                        long dailyLimitMinor, String status, String createdByType, Long createdAt) {
    }

    public record PlatformKeyRow(long keyId, String ownerType, long ownerId, String direction, String usage, String name,
                                 String counterparty, String status, int currentVersion, Long expiresAt) {
    }

    public record KeyVersionRow(int version, String status, String fingerprint, String serial, String enteredBy) {
    }

    // ---- 接入方与授权（M5b.1） ----

    public List<AppRow> apps() {
        Map<Long, Long> active = grants.selectList(Wrappers.<PayAppGrant>lambdaQuery().eq(PayAppGrant::getStatus, "ACTIVE"))
                .stream().collect(Collectors.groupingBy(PayAppGrant::getPayAppId, Collectors.counting()));
        return apps.selectList(Wrappers.<PayApp>lambdaQuery().orderByAsc(PayApp::getId)).stream()
                .map(a -> new AppRow(a.getAppNo(), a.getName(), a.getOwnerType(), a.getStatus(), a.getScopes(),
                        Boolean.TRUE.equals(a.getSandbox()), a.getSignType(), a.getWebhookUrl(), a.getRateLimitQps(),
                        a.getDailyRefundLimitMinor(), active.getOrDefault(a.getId(), 0L).intValue()))
                .toList();
    }

    public List<GrantRow> grants(String appNo) {
        PayApp app = app(appNo);
        return grants.selectList(Wrappers.<PayAppGrant>lambdaQuery().eq(PayAppGrant::getPayAppId, app.getId())
                        .orderByDesc(PayAppGrant::getId)).stream()
                .map(g -> new GrantRow(g.getStoreId(), g.getActions(), g.getStatus(), g.getGrantedBy(), ms(g.getGrantedAt()),
                        ms(g.getRevokedAt())))
                .toList();
    }

    // ---- 接入方密钥：只看指纹 / 吊销（M5b.1） ----

    public List<CredentialRow> credentials(String appNo) {
        PayApp app = app(appNo);
        KeyRegistry registry = registry();
        List<CredentialRow> out = new ArrayList<>();
        for (String kind : List.of("APP_SECRET", "ENCRYPT_KEY")) {
            secret(() -> registry.find(owner(app), APP_CREDENTIALS.get(kind))).ifPresent(k ->
                    secret(() -> registry.versions(k.keyId())).forEach(v -> out.add(new CredentialRow(kind,
                            v.ref().versionIndex(), v.status(), v.fingerprint(), v.externalKeyId(), v.enteredBy()))));
        }
        return out;
    }

    /** 吊销即时生效：下一次用这一版签的请求就验不过（密钥模块按版本状态挑可验签的版本） */
    public void revokeCredential(String appNo, String kind, int version, String reason, String operatorNo) {
        KeyPurpose purpose = APP_CREDENTIALS.get(kind);
        if (purpose == null || version <= 0 || reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String operator = requireOperator(operatorNo);
        PayApp app = app(appNo);
        KeyRegistry registry = registry();
        long keyId = secret(() -> registry.find(owner(app), purpose)).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND))
                .keyId();
        secret(() -> {
            registry.revoke(new KeyVersionRef(keyId, version), operator, reason);
            return null;
        });
    }

    // ---- 请求记录（M5b.2） ----

    public List<RequestLogRow> requestLogs(RequestLogFilter f, int page, int size) {
        LambdaQueryWrapper<PayApiRequestLog> q = Wrappers.<PayApiRequestLog>lambdaQuery()
                .eq(present(f.entry()), PayApiRequestLog::getEntry, f.entry())
                .eq(present(f.appNo()), PayApiRequestLog::getAppNo, f.appNo())
                .eq(present(f.sourceService()), PayApiRequestLog::getSourceService, f.sourceService())
                .eq(present(f.traceId()), PayApiRequestLog::getTraceId, f.traceId())
                .eq(present(f.outcome()), PayApiRequestLog::getOutcome, f.outcome())
                .ge(f.fromMs() != null, PayApiRequestLog::getReceivedAt, time(f.fromMs()))
                .lt(f.toMs() != null, PayApiRequestLog::getReceivedAt, time(f.toMs()))
                .orderByDesc(PayApiRequestLog::getId)
                .last(limit(page, size));
        return logs.selectList(q).stream().map(PayAccessOpsFacade::row).toList();
    }

    public RequestLogDetail requestLog(Long id) {
        PayApiRequestLog l = id == null ? null : logs.selectById(id);
        if (l == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return new RequestLogDetail(row(l), l.getRequestMasked(), l.getResponseMasked());
    }

    // ---- 静态收款码（M5b.4；停用在 PayOpsFacade#disableQrCode） ----

    public List<QrRow> qrCodes(Long storeId, String status, int page, int size) {
        return codes.selectList(Wrappers.<PayQrCode>lambdaQuery()
                        .eq(storeId != null, PayQrCode::getStoreId, storeId)
                        .eq(present(status), PayQrCode::getStatus, status)
                        .orderByDesc(PayQrCode::getId)
                        .last(limit(page, size))).stream()
                .map(c -> new QrRow(c.getQrCode(), c.getStoreId(), c.getKind(), c.getTableRef(), c.getCurrency(),
                        c.getSingleLimitMinor() == null ? 0 : c.getSingleLimitMinor(),
                        c.getDailyLimitMinor() == null ? 0 : c.getDailyLimitMinor(), c.getStatus(), c.getCreatedByType(),
                        ms(c.getCreatedAt())))
                .toList();
    }

    // ---- 平台证书与密钥（M5b.3） ----

    public List<PlatformKeyRow> platformKeys() {
        KeyRegistry registry = registry();
        return secret(() -> registry.keys(SecretNamespace.PAY)).stream()
                .filter(k -> !APP_OWNER_TYPES.contains(k.info().owner().ownerType()))
                .map(PayAccessOpsFacade::row)
                .toList();
    }

    public List<KeyVersionRow> platformKeyVersions(long keyId) {
        KeyRegistry registry = registry();
        requirePlatformKey(registry, keyId);
        return secret(() -> registry.versions(keyId)).stream()
                .map(v -> new KeyVersionRow(v.ref().versionIndex(), v.status(), v.fingerprint(), v.externalKeyId(),
                        v.enteredBy()))
                .toList();
    }

    /** 两人控制在密钥模块：启用人与录入人相同 → {@link ErrorCode#PAY_APPROVAL_SAME_PERSON} */
    public void enablePlatformKey(long keyId, int version, String operatorNo) {
        String operator = requireOperator(operatorNo);
        KeyRegistry registry = registry();
        requirePlatformKey(registry, keyId);
        secret(() -> {
            registry.enable(new KeyVersionRef(keyId, version), operator, "OPS:" + operator);
            return null;
        });
    }

    public void revokePlatformKey(long keyId, int version, String reason, String operatorNo) {
        if (reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String operator = requireOperator(operatorNo);
        KeyRegistry registry = registry();
        requirePlatformKey(registry, keyId);
        secret(() -> {
            registry.revoke(new KeyVersionRef(keyId, version), operator, reason);
            return null;
        });
    }

    /** 平台密钥页的动作只认平台密钥：接入方的密钥走接入方页面（各自的权限码） */
    private static void requirePlatformKey(KeyRegistry registry, long keyId) {
        boolean platform = secret(() -> registry.keys(SecretNamespace.PAY)).stream()
                .anyMatch(k -> k.info().keyId() == keyId && !APP_OWNER_TYPES.contains(k.info().owner().ownerType()));
        if (!platform) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }

    private KeyRegistry registry() {
        KeyRegistry r = keys.getIfAvailable();
        if (r == null) {
            throw BizException.of(ErrorCode.PAY_KEY_SERVICE_UNAVAILABLE);
        }
        return r;
    }

    /** 密钥模块的拒绝换成平台错误码；原因里的内部状态不外露 */
    private static <T> T secret(Supplier<T> call) {
        try {
            return call.get();
        } catch (SecretException e) {
            throw switch (e.reason()) {
                case NOT_FOUND -> BizException.of(ErrorCode.NOT_FOUND);
                case DUAL_CONTROL -> BizException.of(ErrorCode.PAY_APPROVAL_SAME_PERSON);
                case ILLEGAL_STATE -> BizException.of(ErrorCode.CONFLICT);
                default -> BizException.of(ErrorCode.PAY_KEY_SERVICE_UNAVAILABLE);
            };
        }
    }

    private PayApp app(String appNo) {
        PayApp app = appNo == null ? null : apps.selectOne(Wrappers.<PayApp>lambdaQuery().eq(PayApp::getAppNo, appNo));
        if (app == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return app;
    }

    private static KeyOwner owner(PayApp app) {
        return new KeyOwner(SecretNamespace.PAY, "INTERNAL".equals(app.getOwnerType()) ? "INTERNAL_APP" : "PARTNER_APP",
                app.getId());
    }

    private static RequestLogRow row(PayApiRequestLog l) {
        return new RequestLogRow(l.getId(), l.getTraceId(), l.getEntry(), l.getApi(), l.getPayAction(), l.getAppNo(),
                l.getOperatorNo(), l.getSourceService(), l.getSourceModule(), l.getSourceInstance(), l.getSourceIp(),
                l.getOriginClient(), l.getOriginApi(), l.getClientIp(), l.getHttpStatus(), l.getResultCode(), l.getOutcome(),
                l.getElapsedMs(), l.getSignKeyVersion(), ms(l.getReceivedAt()));
    }

    private static PlatformKeyRow row(KeySummary k) {
        return new PlatformKeyRow(k.info().keyId(), k.info().owner().ownerType(), k.info().owner().ownerId(),
                k.info().purpose().direction().name(), k.info().purpose().usage().name(), k.name(), k.counterparty(),
                k.info().status(), k.info().currentVersion(), ms(k.info().expiresAt()));
    }

    private static String requireOperator(String operatorNo) {
        if (operatorNo == null || operatorNo.isBlank()) {
            throw BizException.of(ErrorCode.PAY_REFUND_OPERATOR_REQUIRED);
        }
        return operatorNo;
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private static String limit(int page, int size) {
        int s = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int p = Math.max(1, page);
        return "LIMIT " + s + " OFFSET " + (long) (p - 1) * s;
    }

    private static Long ms(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static LocalDateTime time(Long ms) {
        return ms == null ? null : LocalDateTime.ofEpochSecond(Math.floorDiv(ms, 1000), (int) Math.floorMod(ms, 1000) * 1_000_000,
                ZoneOffset.UTC);
    }
}
