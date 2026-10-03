package ai.neargo.shop.common;

import ai.neargo.shop.common.ratelimit.RateLimiter;
import ai.neargo.shop.common.ratelimit.RateRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(OtpStore.class);

    private static final Duration TTL = Duration.ofMinutes(5);

    /**
     * 验码失败闸（安全整改方案 §2.2 的第 ④ 道）。
     *
     * <p><b>这是四道闸里最重要的一道</b>：①②③ 防的是骚扰与费用，**④ 防的是账号被攻破**。
     * 6 位数字只有 100 万种组合，没有失败次数限制就等于没有验证码 ——
     * 脚本跑几分钟就必中，而每一次尝试在日志里都长得像一次正常的登录失败。
     *
     * <p><b>不受 {@code shop.otp.rate-limit} 开关影响，永远开着。</b>
     * 那个开关是为了让整套用例能反复给同一号码发码；而这一道只在**输错**时才计数，
     * 正常用例（用对的码）根本碰不到它。给它留开关，等于给「关掉最重要那道闸」
     * 留一个理由。
     */
    private static final RateRule FAIL_RULE = RateRule.of("otp.fail", Duration.ofMinutes(15), 5);

    private final RateLimiter limiter;

    public OtpStore(RateLimiter limiter) {
        this.limiter = limiter;
    }

    /**
     * @param code **明文**。
     *
     *             <p>安全整改方案 §2.4 提出改存哈希（防堆转储 / 未来 Redis 快照泄露），
     *             **2026-08-13 决定不做**：验证码只活 5 分钟，且前面已经有四道闸
     *             （发码三道 + 验码失败锁定）；而明文可读在排查「他到底收没收到、
     *             收到的是不是这条」时是实打实的价值。
     *             这个威胁要先攻破服务器才成立，那时泄露的远不止验证码。
     */
    private record Entry(String code, Instant expireAt) {
    }


    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public void save(String phone, String code) {
        cache.put(phone, new Entry(code, Instant.now().plus(TTL)));
    }

    /**
     * 校验并消费：成功即删除，防重放。
     *
     * <p>失败会计数；连续错满 {@link #FAIL_RULE} 后**锁定并作废当前码** ——
     * 不作废的话，锁定期一过攻击者接着用同一条码继续猜，等于只是让他歇一会。
     *
     * @throws BizException {@link ErrorCode#OTP_LOCKED} 已被锁定
     */
    public boolean verifyAndConsume(String phone, String code) {
        Entry e = cache.get(phone);
        boolean ok = e != null && !Instant.now().isAfter(e.expireAt())
                && e.code().equals(code);
        if (ok) {
            cache.remove(phone);
            // 成功即清零：**按「连续失败」计数而不是累计失败** ——
            // 累计的话，一个用她自己号码用了半年的人迟早会被自己锁掉
            limiter.reset(failKey(phone));
            return true;
        }
        if (!limiter.tryAcquire(failKey(phone), FAIL_RULE).allowed()) {
            cache.remove(phone);
            log.warn("[otp] {} 连续输错验证码达上限，已锁定 —— 这是撞码的典型形状",
                    Masks.phone(phone));
            throw BizException.of(ErrorCode.OTP_LOCKED, FAIL_RULE.window().toMinutes());
        }
        return false;
    }

    /** 仅供测试与本地联调读取当前有效码。生产没有任何调用方。 */
    public Optional<String> peek(String phone) {
        return Optional.ofNullable(cache.get(phone)).map(Entry::code);
    }

    private static String failKey(String phone) {
        return "otp:fail:" + phone;
    }

}
