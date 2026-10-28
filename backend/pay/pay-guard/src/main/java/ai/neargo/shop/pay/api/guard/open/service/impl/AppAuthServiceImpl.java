package ai.neargo.shop.pay.api.guard.open.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import ai.neargo.shop.pay.channel.api.CallerType;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayRefundMapper;
import ai.neargo.shop.pay.mapper.openapp.OpenAppMappers.PayAppGrantMapper;
import ai.neargo.shop.pay.mapper.openapp.OpenAppMappers.PayAppMapper;
import ai.neargo.shop.pay.openapp.AppKeyResolver;
import ai.neargo.shop.pay.openapp.NonceStore;
import ai.neargo.shop.pay.openapp.SignatureCanonicalizer;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import ai.neargo.shop.pay.openapp.entity.PayAppGrant;
import ai.neargo.shop.pay.api.guard.open.service.AppAuthService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ai.neargo.shop.pay.api.guard.ConditionalOnGuardEntry;
import ai.neargo.shop.pay.api.guard.PortalFace;

/** {@link AppAuthService} 的实现 */
@ConditionalOnGuardEntry(PortalFace.OPEN)
@Service
public class AppAuthServiceImpl implements AppAuthService {

    static final Duration WINDOW = Duration.ofSeconds(300);

    private final PayAppMapper apps;
    private final PayAppGrantMapper grants;
    private final PayRefundMapper refunds;
    private final AppKeyResolver keys;
    private final NonceStore nonces;
    private final boolean enabled;
    private final boolean sandboxEnvironment;

    public AppAuthServiceImpl(PayAppMapper apps, PayAppGrantMapper grants, PayRefundMapper refunds, AppKeyResolver keys,
                              NonceStore nonces, @Value("${shop.pay.open.enabled:false}") boolean enabled,
                              @Value("${shop.pay.open.environment:production}") String environment) {
        this.apps = apps;
        this.grants = grants;
        this.refunds = refunds;
        this.keys = keys;
        this.nonces = nonces;
        this.enabled = enabled;
        this.sandboxEnvironment = "sandbox".equals(environment);
    }

    @Override
    public Result authenticate(SignedRequest r) {
        if (!enabled) {
            return new Result(null, "开放 API 未开启（shop.pay.open.enabled）");
        }
        if (blank(r.appNo()) || blank(r.timestamp()) || blank(r.nonce()) || blank(r.signature())) {
            return new Result(null, "缺签名头");
        }
        PayApp app = find(r.appNo());
        if (app == null || !"ACTIVE".equals(app.getStatus())) {
            return new Result(null, "接入方不存在或未启用");
        }
        // 沙箱 app 只能打沙箱环境，生产 app 只能打生产环境 —— 两套密钥、两个域名（安全规范 §2.3）
        if (Boolean.TRUE.equals(app.getSandbox()) != sandboxEnvironment) {
            return new Result(null, "接入方环境与本环境不符");
        }
        if (!ipAllowed(app.getIpWhitelist(), r.clientIp())) {
            return new Result(null, "来源 IP 不在白名单：" + r.clientIp());
        }
        long ts;
        try {
            ts = Long.parseLong(r.timestamp().trim());
        } catch (NumberFormatException e) {
            return new Result(null, "时间戳不是毫秒数");
        }
        long skew = Math.abs(System.currentTimeMillis() - ts);
        if (skew > WINDOW.toMillis()) {
            return new Result(null, "时间窗外（偏差 " + skew / 1000 + " 秒）");
        }
        if (r.nonce().length() < 8 || r.nonce().length() > 64) {
            return new Result(null, "nonce 长度应为 8–64");
        }
        String toSign = SignatureCanonicalizer.stringToSign(r.method(), r.path(), r.query(), r.appNo(), r.timestamp().trim(),
                r.nonce(), r.bodySha256Hex());
        boolean signed = keys.hmacSecretsOf(r.appNo()).stream().anyMatch(secret -> matches(secret, toSign, r.signature()));
        if (!signed) {
            return new Result(null, "签名不符");
        }
        // nonce 放在验签之后抢占：不让没签对的请求往表里写东西（安全规范 §5.4；TDD 偏差）
        if (!nonces.claim("APP", app.getId(), r.nonce(), WINDOW)) {
            return new Result(null, "nonce 重放");
        }
        return new Result(app, null);
    }

    @Override
    public PayApp requireActive(String appNo) {
        PayApp app = find(appNo);
        if (app == null || !"ACTIVE".equals(app.getStatus())) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return app;
    }

    @Override
    public void requireScope(PayApp app, String scope) {
        Set<String> scopes = Arrays.stream(app.getScopes() == null ? new String[0] : app.getScopes().split(","))
                .map(String::trim).collect(Collectors.toSet());
        if (!scopes.contains(scope)) {
            throw BizException.of(ErrorCode.PAY_FORBIDDEN);
        }
    }

    @Override
    public PayCaller callerFor(PayApp app, String grantAction) {
        Set<Long> stores = DataScopeContext.executeWithoutScope(() -> grants.selectList(
                        Wrappers.<PayAppGrant>lambdaQuery().eq(PayAppGrant::getPayAppId, app.getId())
                                .eq(PayAppGrant::getStatus, "ACTIVE")))
                .stream()
                .filter(g -> Arrays.stream(g.getActions().split(",")).map(String::trim).anyMatch(grantAction::equals))
                .map(PayAppGrant::getStoreId)
                .collect(Collectors.toSet());
        return new PayCaller(CallerType.PARTNER, app.getId(), stores, "APP:" + app.getAppNo(), null, null);
    }

    @Override
    public void checkDailyRefund(PayApp app, long amountMinor) {
        long limit = app.getDailyRefundLimitMinor() == null ? 0L : app.getDailyRefundLimitMinor();
        if (limit <= 0) {
            throw BizException.of(ErrorCode.PAY_APP_REFUND_LIMIT, 0L);
        }
        long used = DataScopeContext.executeWithoutScope(() -> refunds.selectList(Wrappers.<PayRefund>lambdaQuery()
                        .eq(PayRefund::getSourceType, CallerType.PARTNER.name())
                        .eq(PayRefund::getSourceId, app.getId())
                        .notIn(PayRefund::getStatus, List.of("REJECTED", "FAILED"))
                        .ge(PayRefund::getCreatedAt, LocalDate.now(ZoneOffset.UTC).atStartOfDay())))
                .stream().mapToLong(x -> x.getAmountMinor() == null ? 0L : x.getAmountMinor()).sum();
        if (used + amountMinor > limit) {
            throw BizException.of(ErrorCode.PAY_APP_REFUND_LIMIT, Math.max(0L, limit - used));
        }
    }

    @Override
    public void checkDailyRefund(Long payAppId, long amountMinor) {
        PayApp app = payAppId == null ? null : DataScopeContext.executeWithoutScope(() -> apps.selectById(payAppId));
        if (app == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        checkDailyRefund(app, amountMinor);
    }

    private PayApp find(String appNo) {
        return appNo == null ? null : DataScopeContext.executeWithoutScope(() -> apps.selectOne(
                Wrappers.<PayApp>lambdaQuery().eq(PayApp::getAppNo, appNo).last("LIMIT 1")));
    }

    private static boolean matches(String secret, String toSign, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = Base64.getEncoder().encode(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected, signature.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /** 白名单为空 = 不限；支持单个 IP 与 IPv4 CIDR */
    static boolean ipAllowed(String whitelist, String ip) {
        if (whitelist == null || whitelist.isBlank()) {
            return true;
        }
        if (ip == null) {
            return false;
        }
        for (String entry : whitelist.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) {
                continue;
            }
            if (e.contains("/") ? inCidr(ip, e) : e.equals(ip)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inCidr(String ip, String cidr) {
        try {
            String[] p = cidr.split("/");
            byte[] net = InetAddress.getByName(p[0]).getAddress();
            byte[] addr = InetAddress.getByName(ip).getAddress();
            if (net.length != addr.length) {
                return false;
            }
            int bits = Integer.parseInt(p[1]);
            for (int i = 0; i < net.length && bits > 0; i++, bits -= 8) {
                int mask = bits >= 8 ? 0xff : (0xff << (8 - bits)) & 0xff;
                if ((net[i] & mask) != (addr[i] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
