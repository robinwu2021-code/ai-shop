package ai.neargo.shop.pay.api.guard.terminal;

import ai.neargo.secret.api.KeyRegistry;
import ai.neargo.secret.api.KeyVault;
import ai.neargo.secret.api.KeyVersionRef;
import ai.neargo.secret.api.SecretException;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.channel.api.CallerType;
import ai.neargo.shop.pay.openapp.NonceStore;
import ai.neargo.shop.pay.openapp.SignatureCanonicalizer;
import ai.neargo.shop.pay.terminal.entity.PayTerminal;
import ai.neargo.shop.pay.terminal.service.TerminalService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * POS 终端鉴权（pay-guard TDD §2.2 C；开发计划 M6.2）：终端存在且 {@code ACTIVE} → 时间窗 → 设备密钥验签 → nonce → 调用方圈到
 * 终端绑定的那一家门店。待签串同开放 API（{@link SignatureCanonicalizer}），第 4 行的 AppId 换成终端号。
 *
 * <p>设备密钥不出密钥模块：只交版本引用去算 HMAC（{@link KeyVault#mac}），常量时间比较。挂失吊销后它不在可验签的版本里，
 * 下一次请求即失败。{@link PayCaller} 只在这里（守卫）构造 —— 与开放 API、收银台、内部调用并列的合法构造点。
 */
@Service
public class TerminalAuthService {

    static final Duration WINDOW = Duration.ofMinutes(5);

    private final TerminalService terminals;
    private final ObjectProvider<KeyRegistry> keys;
    private final ObjectProvider<KeyVault> vault;
    private final NonceStore nonces;

    public TerminalAuthService(TerminalService terminals, ObjectProvider<KeyRegistry> keys, ObjectProvider<KeyVault> vault,
                               NonceStore nonces) {
        this.terminals = terminals;
        this.keys = keys;
        this.vault = vault;
        this.nonces = nonces;
    }

    public record SignedRequest(String terminalSn, String timestamp, String nonce, String signature, String method,
                                String path, Map<String, List<String>> query, String bodySha256Hex) {
    }

    /** @param failure 只进日志与请求记录，不回给终端 */
    public record Result(PayTerminal terminal, Integer keyVersion, String failure, boolean replayed, boolean expired) {

        static Result ok(PayTerminal t, int version) {
            return new Result(t, version, null, false, false);
        }

        static Result failed(String why) {
            return new Result(null, null, why, false, false);
        }

        public boolean ok() {
            return terminal != null;
        }
    }

    public Result authenticate(SignedRequest r) {
        if (blank(r.terminalSn()) || blank(r.timestamp()) || blank(r.nonce()) || blank(r.signature())) {
            return Result.failed("缺少签名头");
        }
        PayTerminal t = terminals.find(r.terminalSn()).orElse(null);
        if (t == null || !"ACTIVE".equals(t.getStatus()) || t.getSecKeyId() == null) {
            return Result.failed("终端不存在或不是生效状态");
        }
        long ts;
        try {
            ts = Long.parseLong(r.timestamp().trim());
        } catch (NumberFormatException e) {
            return new Result(null, null, "时间戳不是毫秒数", false, true);
        }
        if (Math.abs(System.currentTimeMillis() - ts) > WINDOW.toMillis()) {
            return new Result(null, null, "时间窗外", false, true);
        }
        if (r.nonce().length() < 8 || r.nonce().length() > 64) {
            return Result.failed("nonce 长度应为 8–64");
        }
        String toSign = SignatureCanonicalizer.stringToSign(r.method(), r.path(), r.query(), t.getTerminalSn(),
                r.timestamp().trim(), r.nonce(), r.bodySha256Hex());
        Optional<Integer> version = verify(t, toSign, r.signature());
        if (version.isEmpty()) {
            return Result.failed("签名不符");
        }
        // nonce 在验签之后抢占：没签对的请求不往表里写东西（同开放 API）
        if (!nonces.claim("TERMINAL", t.getId(), r.nonce(), WINDOW)) {
            return new Result(null, null, "nonce 重放", true, false);
        }
        return Result.ok(t, version.get());
    }

    /** 终端的调用方：门店圈到绑定的那一家；收银员取自 {@code X-Operator}（登录后才有） */
    public PayCaller callerFor(String terminalSn, String operatorNo) {
        PayTerminal t = terminals.find(terminalSn).filter(x -> "ACTIVE".equals(x.getStatus()))
                .orElseThrow(() -> BizException.of(ErrorCode.UNAUTHORIZED));
        return new PayCaller(CallerType.TERMINAL, t.getId(), Set.of(t.getStoreId()),
                operatorNo == null || operatorNo.isBlank() ? null : operatorNo.trim(), null, null);
    }

    /** 响应签名（P2）：用设备密钥当前版本；签不了为空（终端拿不到签名就不认这次「成功」） */
    public Optional<String> sign(String terminalSn, byte[] data) {
        try {
            PayTerminal t = terminals.find(terminalSn).orElse(null);
            KeyRegistry registry = keys.getIfAvailable();
            KeyVault v = vault.getIfAvailable();
            if (t == null || t.getSecKeyId() == null || registry == null || v == null) {
                return Optional.empty();
            }
            return Optional.of(Base64.getEncoder().encodeToString(v.mac(registry.current(t.getSecKeyId()), data)));
        } catch (SecretException e) {
            return Optional.empty();
        }
    }

    private Optional<Integer> verify(PayTerminal t, String toSign, String signature) {
        byte[] given;
        try {
            given = Base64.getDecoder().decode(signature.trim());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        KeyRegistry registry = keys.getIfAvailable();
        KeyVault v = vault.getIfAvailable();
        if (registry == null || v == null) {
            return Optional.empty();
        }
        try {
            byte[] data = toSign.getBytes(StandardCharsets.UTF_8);
            for (KeyVersionRef ref : registry.verificationVersions(t.getSecKeyId())) {
                if (MessageDigest.isEqual(v.mac(ref, data), given)) {
                    return Optional.of(ref.versionIndex());
                }
            }
        } catch (SecretException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
