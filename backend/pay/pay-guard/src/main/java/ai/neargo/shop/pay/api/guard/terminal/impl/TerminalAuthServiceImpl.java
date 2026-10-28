package ai.neargo.shop.pay.api.guard.terminal.impl;

import ai.neargo.shop.pay.api.guard.terminal.TerminalAuthService;

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
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** {@link TerminalAuthService} 的实现（职责与约束见接口注释） */
@Service
public class TerminalAuthServiceImpl implements TerminalAuthService {

    public static final Duration WINDOW = Duration.ofMinutes(5);

    private final TerminalService terminals;
    private final ObjectProvider<KeyRegistry> keys;
    private final ObjectProvider<KeyVault> vault;
    private final NonceStore nonces;

    public TerminalAuthServiceImpl(TerminalService terminals, ObjectProvider<KeyRegistry> keys, ObjectProvider<KeyVault> vault,
                               NonceStore nonces) {
        this.terminals = terminals;
        this.keys = keys;
        this.vault = vault;
        this.nonces = nonces;
    }


    @Override
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
    @Override
    public PayCaller callerFor(String terminalSn, String operatorNo) {
        PayTerminal t = terminals.find(terminalSn).filter(x -> "ACTIVE".equals(x.getStatus()))
                .orElseThrow(() -> BizException.of(ErrorCode.UNAUTHORIZED));
        return new PayCaller(CallerType.TERMINAL, t.getId(), Set.of(t.getStoreId()),
                operatorNo == null || operatorNo.isBlank() ? null : operatorNo.trim(), null, null);
    }

    /** 响应签名（P2）：用设备密钥当前版本；签不了为空（终端拿不到签名就不认这次「成功」） */
    @Override
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
