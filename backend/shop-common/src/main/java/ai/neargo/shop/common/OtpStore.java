package ai.neargo.shop.common;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 短信验证码暂存。
 *
 * <p>放在 common 而不是 user 域：**C 端用户登录与 B 端子账号登录都要发码**
 * （见 {@code MerchantStaffServiceImpl}）。留在 user 域的话，商家域要发个验证码
 * 就得依赖整个用户域——为一个 30 行的缓存拖进一个业务模块。
 * 安全整改方案 §2.3 的限流组件也落在这一层，两者将来会合在一起。
 *
 * <p>抽成组件而不是塞进 {@code AuthServiceImpl} 的私有字段，有两个理由：
 * S2 换 Redis 时只改这里；测试能读到发出去的码，从而走**真实的**发码-校验链路，
 * 而不是给生产代码开一个「万能验证码」后门 —— 那种后门一旦漏到线上就是任意账号登录。
 */
@Component
public class OtpStore {

    private static final Duration TTL = Duration.ofMinutes(5);

    private record Entry(String code, Instant expireAt) {
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public void save(String phone, String code) {
        cache.put(phone, new Entry(code, Instant.now().plus(TTL)));
    }

    /** 校验并消费：成功即删除，防重放。 */
    public boolean verifyAndConsume(String phone, String code) {
        Entry e = cache.get(phone);
        if (e == null || Instant.now().isAfter(e.expireAt()) || !e.code().equals(code)) {
            return false;
        }
        cache.remove(phone);
        return true;
    }

    /** 仅供测试与本地联调读取当前有效码。生产没有任何调用方。 */
    public Optional<String> peek(String phone) {
        return Optional.ofNullable(cache.get(phone)).map(Entry::code);
    }
}
