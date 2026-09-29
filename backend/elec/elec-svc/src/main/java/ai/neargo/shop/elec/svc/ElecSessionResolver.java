package ai.neargo.shop.elec.svc;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.svc.client.ServiceCalls;
import ai.neargo.shop.svc.ServiceName;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌 → 是谁。问主系统，<b>有效的结果缓存 60 秒</b>。
 *
 * <p><b>缓存的代价</b>：用户退出登录后，旧令牌在元器件这边最多还能用 60 秒
 * （主系统自己的撤销轮询是 5 秒）。元器件第一步没有资金类动作，这个窗口可接受；
 * 将来有了，要么缩短、要么改成主系统推撤销。
 *
 * <p><b>不做负缓存</b>（与主系统 DbTokenStore 同一个理由）：刚登录的令牌第一次来就被记成无效，
 * 用户会在接下来一分钟里一直是「未登录」。
 *
 * <p>缓存键是令牌的 SHA-256，不是令牌本身：进程堆转储里不该躺着一堆可用的令牌。
 */
@Component
public class ElecSessionResolver {

    static final Duration TTL = Duration.ofSeconds(60);

    /** 超过这么多条就整个清掉重来。简单，且不会因为缓存无限长大把进程拖垮 */
    private static final int MAX_ENTRIES = 20_000;

    private record Entry(ElecInternal.Session session, Instant until) {
    }

    private final MainSystemApi main;
    private final Clock clock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ElecSessionResolver(MainSystemApi main) {
        this(main, Clock.systemUTC());
    }

    ElecSessionResolver(MainSystemApi main, Clock clock) {
        this.main = main;
        this.clock = clock;
    }

    /**
     * @throws ai.neargo.svc.client.ServiceCallException 主系统调不通。调用方据此回 503，
     *         <b>不能当成「令牌无效」</b> —— 那会让端上清掉令牌重新登录，而用户什么都没做错
     */
    public ElecInternal.Session resolve(String token) {
        String key = sha256(token);
        Instant now = clock.instant();
        Entry hit = cache.get(key);
        if (hit != null && hit.until().isAfter(now)) {
            return hit.session();
        }
        ElecInternal.Session s = ServiceCalls.call(ServiceName.PLATFORM,
                () -> main.session(new ElecInternal.SessionReq(token)));
        if (s != null && s.valid()) {
            if (cache.size() >= MAX_ENTRIES) {
                cache.clear();
            }
            cache.put(key, new Entry(s, now.plus(TTL)));
        } else {
            cache.remove(key);
        }
        return s == null ? ElecInternal.Session.invalid(false) : s;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
