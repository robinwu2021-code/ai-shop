package ai.neargo.shop.pay.terminal.service;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.KeyPurpose;
import ai.neargo.secret.api.KeyRegistry;
import ai.neargo.secret.api.KeyRegistry.Generation;
import ai.neargo.secret.api.KeyRegistry.IssuedSecret;
import ai.neargo.secret.api.KeyRegistry.NewKey;
import ai.neargo.secret.api.SecretException;
import ai.neargo.secret.api.SecretFormat;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.mapper.terminal.TerminalMappers.PayTerminalMapper;
import ai.neargo.shop.pay.openapp.SignatureCanonicalizer;
import ai.neargo.shop.pay.terminal.entity.PayTerminal;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * POS 终端的登记与生命周期（pay-guard 开发计划 M6.3；代码清单 §4.2 {@code TerminalService}）：登记 → 发一次性激活码 → 终端激活
 * （生成设备密钥，只此一次下发）→ 心跳 → 挂失（吊销密钥，下一次请求即 401）。
 *
 * <p><b>设备密钥的两人控制</b>：生成人记发激活码的运营（{@code updated_by}），启用人记终端本身（{@code TERMINAL:<sn>}）——
 * 一个人在后台、一台设备在门店，两样凑齐才生效；密钥模块照常强制「启用人 ≠ 录入人」。
 */
@Service
public class TerminalService {

    public static final String OWNER_TYPE = "TERMINAL";
    static final Duration ACTIVATION_TTL = Duration.ofMinutes(10);
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> USABLE = Set.of("ACTIVE", "ROTATING", "VERIFIED", "DRAFT");

    private final PayTerminalMapper terminals;
    private final ObjectProvider<KeyRegistry> keys;

    public TerminalService(PayTerminalMapper terminals, ObjectProvider<KeyRegistry> keys) {
        this.terminals = terminals;
        this.keys = keys;
    }

    /** 激活结果：设备密钥明文只在这一次响应里，服务端不能再次展示（丢了就挂失重登记） */
    public record Activated(String terminalSn, Long storeId, String deviceSecret, String keySerial) {

        @Override
        public String toString() {
            return "Activated[terminalSn=" + terminalSn + ", storeId=" + storeId + ", keySerial=" + keySerial + "]";
        }
    }

    public PayTerminal register(String terminalSn, Long storeId, Long payMerchantId, String model, long dailyRefundLimitMinor,
                                String operatorNo) {
        if (blank(terminalSn) || storeId == null || payMerchantId == null || blank(model) || dailyRefundLimitMinor < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (find(terminalSn).isPresent()) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        PayTerminal t = new PayTerminal();
        t.setTerminalSn(terminalSn.trim());
        t.setStoreId(storeId);
        t.setPayMerchantId(payMerchantId);
        t.setModel(model);
        t.setStatus("REGISTERED");
        t.setDailyRefundLimitMinor(dailyRefundLimitMinor);
        t.setCreatedBy(operatorNo);
        t.setUpdatedBy(operatorNo);
        terminals.insert(t);
        return t;
    }

    /** 一次性激活码：10 分钟、单次、库里只存 SHA-256。返回明文（只此一次） */
    public String issueActivationCode(String terminalSn, String operatorNo) {
        PayTerminal t = require(terminalSn);
        if (!"REGISTERED".equals(t.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        PayTerminal patch = new PayTerminal();
        patch.setId(t.getId());
        patch.setActivationCodeSha256(sha256(code.toString()));
        patch.setActivationExpireAt(now().plus(ACTIVATION_TTL));
        patch.setUpdatedBy(operatorNo);
        terminals.updateById(patch);
        return code.toString();
    }

    /**
     * 终端用激活码换设备密钥（守卫不验签的唯一接口）。激活码不对、过期、已用、终端不是待激活 —— 一律同一个 401，
     * 不告诉对方差在哪。
     */
    public Activated activate(String code, String deviceFingerprint, String appVersion) {
        if (blank(code) || blank(deviceFingerprint)) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        PayTerminal t = terminals.selectOne(Wrappers.<PayTerminal>lambdaQuery()
                .eq(PayTerminal::getActivationCodeSha256, sha256(code.trim().toUpperCase())));
        if (t == null || !"REGISTERED".equals(t.getStatus()) || t.getActivationExpireAt() == null
                || t.getActivationExpireAt().isBefore(now())) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        KeyRegistry registry = registry();
        String issuer = t.getUpdatedBy() == null ? "SYSTEM" : t.getUpdatedBy();
        IssuedSecret issued = secret(() -> {
            long keyId = registry.register(new NewKey(owner(t), KeyPurpose.INBOUND_HMAC, "terminal " + t.getTerminalSn(), null,
                    "HMAC_SHA256", "SYMMETRIC_SECRET", issuer));
            IssuedSecret s = registry.generate(keyId, new Generation(SecretFormat.BASE64URL_32B, null, "T", issuer,
                    "terminal activation"));
            registry.markVerified(s.ref(), "PAY:pay_terminal:" + t.getId(), issuer);
            registry.enable(s.ref(), "TERMINAL:" + t.getTerminalSn(), "PAY:pay_terminal:" + t.getId());
            return s;
        });
        PayTerminal patch = new PayTerminal();
        patch.setId(t.getId());
        patch.setStatus("ACTIVE");
        patch.setSecKeyId(issued.ref().keyId());
        patch.setDeviceFingerprint(deviceFingerprint);
        patch.setAppVersion(appVersion);
        patch.setActivatedAt(now());
        patch.setLastSeenAt(now());
        terminals.updateById(patch);
        // 激活码单次：清掉哈希（updateById 跳过 null，单独置空）
        terminals.update(null, Wrappers.<PayTerminal>lambdaUpdate().eq(PayTerminal::getId, t.getId())
                .set(PayTerminal::getActivationCodeSha256, null).set(PayTerminal::getActivationExpireAt, null));
        return new Activated(t.getTerminalSn(), t.getStoreId(), new String(issued.plaintext(), StandardCharsets.UTF_8),
                issued.externalKeyId());
    }

    public void heartbeat(Long terminalId, String appVersion, String readerKit) {
        PayTerminal patch = new PayTerminal();
        patch.setId(terminalId);
        patch.setLastSeenAt(now());
        patch.setAppVersion(appVersion);
        patch.setReaderKit(readerKit);
        terminals.updateById(patch);
    }

    /** 挂失：终端置 LOST、设备密钥所有可用版本吊销 —— 下一次签名请求即 401。重新启用要重新登记、重新激活 */
    public void reportLost(String terminalSn, String operatorNo, String reason) {
        PayTerminal t = require(terminalSn);
        PayTerminal patch = new PayTerminal();
        patch.setId(t.getId());
        patch.setStatus("LOST");
        patch.setUpdatedBy(operatorNo);
        terminals.updateById(patch);
        if (t.getSecKeyId() != null) {
            KeyRegistry registry = registry();
            secret(() -> {
                registry.versions(t.getSecKeyId()).stream().filter(v -> USABLE.contains(v.status()))
                        .forEach(v -> registry.revoke(v.ref(), operatorNo, blank(reason) ? "terminal lost" : reason));
                return null;
            });
        }
    }

    public Optional<PayTerminal> find(String terminalSn) {
        return blank(terminalSn) ? Optional.empty() : Optional.ofNullable(terminals.selectOne(
                Wrappers.<PayTerminal>lambdaQuery().eq(PayTerminal::getTerminalSn, terminalSn.trim())));
    }

    public PayTerminal require(String terminalSn) {
        return find(terminalSn).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }

    /** @param storeId 空 = 全部门店（运营） */
    public List<PayTerminal> list(Long storeId) {
        return terminals.selectList(Wrappers.<PayTerminal>lambdaQuery().eq(storeId != null, PayTerminal::getStoreId, storeId)
                .orderByDesc(PayTerminal::getId).last("LIMIT 200"));
    }

    /** 设备密钥的归属：{@code sec_key(PAY, TERMINAL, pay_terminal.id)} */
    public static KeyOwner owner(PayTerminal t) {
        return new KeyOwner(SecretNamespace.PAY, OWNER_TYPE, t.getId());
    }

    private KeyRegistry registry() {
        KeyRegistry r = keys.getIfAvailable();
        if (r == null) {
            throw BizException.of(ErrorCode.PAY_KEY_SERVICE_UNAVAILABLE);
        }
        return r;
    }

    private static <T> T secret(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (SecretException e) {
            throw BizException.of(e.reason() == SecretException.Reason.NOT_FOUND ? ErrorCode.NOT_FOUND
                    : ErrorCode.PAY_KEY_SERVICE_UNAVAILABLE);
        }
    }

    static String sha256(String s) {
        return SignatureCanonicalizer.sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
