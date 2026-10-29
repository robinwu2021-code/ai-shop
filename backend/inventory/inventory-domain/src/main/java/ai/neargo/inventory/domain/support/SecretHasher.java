package ai.neargo.inventory.domain.support;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 开放接口凭证的密钥哈希（BCrypt）。
 *
 * <p><b>与 ai-shop 的口令哈希同一算法</b>：库里已存的凭证哈希由商城那份写入（一律 BCrypt，
 * 凭证从诞生起就没用过一期的占位哈希），换实现后必须照样校验得过。
 * {@code shop-inventory} 的 {@code DomainRuleParityTest} 用两边互相校验对方的产物。
 *
 * <p><b>刻意不认 BCrypt 之外的格式</b>：一期占位哈希只准留在商城的口令哈希里做存量验证
 * （{@code ArchitectureTest#legacyPasswordHashIsContained}），凭证用不着它。
 */
@Component
public class SecretHasher {

    private static final String BCRYPT_PREFIX = "$2";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public String encode(String raw) {
        return encoder.encode(raw == null ? "" : raw);
    }

    public boolean matches(String raw, String stored) {
        if (stored == null || stored.isBlank()) {
            return false;
        }
        return stored.startsWith(BCRYPT_PREFIX) && encoder.matches(raw == null ? "" : raw, stored);
    }
}
