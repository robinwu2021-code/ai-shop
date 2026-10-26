package ai.neargo.shop.platform.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 运营登录防撞库（支付服务-安全规范 §2.7，G9）：15 分钟窗口内，同账号 5 次失败即锁、同 IP 20 次失败即限。
 * <p>
 * <b>只数失败</b>：按 IP 数全部尝试的话，办公网出口（以及测试，全从 127.0.0.1 来）登录几十次就把整个出口锁了。
 * 登录成功清掉该账号的失败计数（不清 IP 的 —— 攻击者夹带一次自己的正确登录不能洗掉 IP 上的记录）。
 * <p>
 * 单实例内存实现，与 {@code PasswordResetTokens} 同一前提；多实例部署时换共享存储（TDD 偏差）。
 */
@Component
public class OpsLoginGuard {

    static final Duration WINDOW = Duration.ofMinutes(15);
    static final int ACCOUNT_MAX_FAILURES = 5;
    static final int IP_MAX_FAILURES = 20;
    private static final int MAX_KEYS = 50_000;

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    /** 账号或 IP 已达上限 → true（调用方据此拒绝，连密码都不验） */
    public boolean blocked(String username, String ip) {
        Instant now = Instant.now();
        return count(accountKey(username), now) >= ACCOUNT_MAX_FAILURES
                || ip != null && count(ipKey(ip), now) >= IP_MAX_FAILURES;
    }

    public void failed(String username, String ip) {
        if (failures.size() > MAX_KEYS) {
            failures.clear();   // 被灌爆时宁可全清（放宽）也不让内存无界增长
        }
        Instant now = Instant.now();
        record(accountKey(username), now);
        if (ip != null) {
            record(ipKey(ip), now);
        }
    }

    public void succeeded(String username) {
        failures.remove(accountKey(username));
    }

    private int count(String key, Instant now) {
        Deque<Instant> q = failures.get(key);
        if (q == null) {
            return 0;
        }
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(WINDOW))) {
                q.pollFirst();
            }
            return q.size();
        }
    }

    private void record(String key, Instant now) {
        Deque<Instant> q = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(now);
            while (q.size() > IP_MAX_FAILURES) {
                q.pollFirst();
            }
        }
    }

    private static String accountKey(String username) {
        return "u:" + (username == null ? "" : username.trim().toLowerCase(Locale.ROOT));
    }

    private static String ipKey(String ip) {
        return "ip:" + ip;
    }
}
