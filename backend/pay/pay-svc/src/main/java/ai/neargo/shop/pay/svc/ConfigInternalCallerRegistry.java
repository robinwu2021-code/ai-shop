package ai.neargo.shop.pay.svc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 过渡实现（⏸，密钥模块就绪后换）：每个调用方一把凭据，部署环境里只放它的 SHA-256 ——
 * {@code shop.pay.internal-callers.MALL=<sha256 hex>}。取代全仓共享的 {@code shop.services.internal-token}：
 * 一把令牌泄露不再等于所有调用方的权限都泄露。
 *
 * <p>比对用 {@link MessageDigest#isEqual}（常量时间，R12）；没配的调用方一律不认。
 */
@Component
public class ConfigInternalCallerRegistry implements InternalCallerRegistry {

    private final Map<String, String> sha256ByCaller;

    public ConfigInternalCallerRegistry(Environment env) {
        this.sha256ByCaller = Binder.get(env).bind("shop.pay.internal-callers", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
    }

    @Override
    public Optional<String> resolve(String callerCode, String credential) {
        if (callerCode == null || credential == null || credential.isEmpty()) {
            return Optional.empty();
        }
        String expected = sha256ByCaller.get(callerCode);
        if (expected == null || expected.isBlank()) {
            return Optional.empty();
        }
        byte[] given = sha256(credential).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8), given)
                ? Optional.of(callerCode) : Optional.empty();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
