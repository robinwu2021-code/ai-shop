package ai.neargo.shop.auth.store;

import ai.neargo.auth.store.AuthCache;
import ai.neargo.auth.store.IdentityLoader;
import ai.neargo.auth.store.LoginEvent;
import ai.neargo.auth.store.LoginLogWriter;
import ai.neargo.auth.store.RevokeReason;
import ai.neargo.auth.store.SessionDao;
import ai.neargo.auth.store.SessionProfile;
import ai.neargo.auth.store.SessionRow;
import ai.neargo.auth.store.TokenHash;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.RequestMetaContext;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 会话存进数据库，本地留两级缓存。**一个类，三端各装配一次。**
 *
 * <p>相比 {@link EhcacheTokenStore} 的本质差别只有一条：<b>权威在库</b>。
 * 于是多实例可以共享登录态，而 {@link #revokeUser} 能跨实例生效 ——
 * 后者是「停用后立即无法操作」这条契约在多副本下唯一的实现方式。
 *
 * <h2>两级缓存，按变化频率分层</h2>
 * <pre>
 * L1-会话  tokenHash → (userNo, expiresAt)   会话内**永不变** → TTL 长
 * L1-身份  userNo    → LoginUser             随用户表变       → TTL 短
 * </pre>
 * 放一层里的话，TTL 要么迁就会话（改了角色迟迟不生效），
 * 要么迁就身份（令牌白白反复回源查库）。
 *
 * <h2>两个旋钮分开拧</h2>
 * {@code cacheTtl} 大 → 回源少（<b>轻</b>）；{@code revokePoll} 小 → 踢人快（<b>安全</b>）。
 * 两者互不影响。把它们当成一个旋钮（「缓存久 = 踢人慢」）是最常见的误解，
 * 而按那个误解设计，C 端只能在「贵」和「不安全」之间二选一。
 *
 * <h2>不做负缓存</h2>
 * 查不到就是查不到，**不缓存「不存在」**。否则用户刚在实例 A 登录，
 * 若 B 之前缓存过该令牌的 miss，登录后的头几秒会**间歇性 401** ——
 * 而这种错「重试一下就好了」，最容易被归因成网络问题然后长期存在。
 * 无效令牌的压力由前缀校验（不查库）与登录限流承担，那里本来就有闸门。
 */
public class DbTokenStore implements TokenStore {

    private static final Logger log = LoggerFactory.getLogger(DbTokenStore.class);

    /** 一次撤销轮询最多处理多少条。远大于正常量，只是防一次异常放大。 */
    private static final int REVOKE_BATCH = 500;

    private final Realm realm;
    private final SessionProfile profile;
    private final SessionDao sessions;
    private final IdentityLoader<LoginUser> identities;
    private final AuthCache<String, CachedSession> sessionCache;
    private final AuthCache<String, LoginUser> identityCache;
    private final LoginLogWriter audit;
    private final Clock clock;

    /** 撤销轮询的水位线：只处理它之后新撤销的。 */
    private volatile LocalDateTime revokeWatermark;

    /** 会话缓存的载荷。**只有两样** —— 身份不在这里，它随用户表变。 */
    public record CachedSession(String userNo, LocalDateTime expiresAt, LocalDateTime lastSeenAt) {
    }

    public DbTokenStore(Realm realm, SessionProfile profile, SessionDao sessions,
                        IdentityLoader<LoginUser> identities,
                        AuthCache<String, CachedSession> sessionCache,
                        AuthCache<String, LoginUser> identityCache,
                        LoginLogWriter audit,
                        Clock clock) {
        this.realm = realm;
        this.profile = profile;
        this.sessions = sessions;
        this.identities = identities;
        this.sessionCache = sessionCache;
        this.identityCache = identityCache;
        this.audit = audit;
        this.clock = clock;
        this.revokeWatermark = LocalDateTime.now(clock);
    }

    @Override
    public String issue(SessionData data) {
        LoginUser user = data.user();
        if (user.realm() != realm) {
            // 装配错了：把 C 端的登录塞进了运营端的 store。
            // 不拦的话，令牌会带 otk_ 前缀却指向一个消费者 —— 而这正是分池要消灭的状态
            throw new IllegalArgumentException(
                    "会话的 realm(%s) 与本 store(%s) 不符".formatted(user.realm(), realm));
        }
        String token = TokenStore.newToken(realm);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = now.plus(profile.sessionTtl());

        sessions.insert(TokenHash.of(token), user.userNo(), now, expiresAt);
        sessionCache.put(TokenHash.of(token), new CachedSession(user.userNo(), expiresAt, now));
        identityCache.put(user.userNo(), user);
        /*
         * **这里刻意不写 LOGIN。**
         *
         * 从签发处落看似省事（三端登录最终都走到这一步），但它只在 db 形态下存在 ——
         * 生产走的是 ehcache，于是「登录成功」在生产一条都没有。
         * LOGIN 已挪到 LoginAuditor，由业务层的登录端点显式调；
         * 那条路与会话存在哪里无关，切换存储不影响它。
         *
         * 留在这里的只有 LOGOUT / REVOKED / ORPHAN_SESSION —— 那三件事
         * 本身就是会话表上的事件，业务层没有可靠的时机去记。
         */
        return token;
    }

    @Override
    public Optional<SessionData> get(String token) {
        // 第一道：前缀。跨池令牌在这里就被拒，**不查库、不进缓存**
        if (token == null || !token.startsWith(profile.tokenPrefix())) {
            return Optional.empty();
        }
        String hash = TokenHash.of(token);
        LocalDateTime now = LocalDateTime.now(clock);

        CachedSession cached = sessionCache.get(hash);
        if (cached == null) {
            Optional<SessionRow> row = sessions.findByHash(hash);
            if (row.isEmpty() || !row.get().isLive(now)) {
                return Optional.empty();   // **不做负缓存**，见类注释
            }
            cached = new CachedSession(row.get().userNo(), row.get().expiresAt(),
                    row.get().lastSeenAt());
            sessionCache.put(hash, cached);
        }
        if (!cached.expiresAt().isAfter(now)) {
            sessionCache.evict(hash);
            return Optional.empty();
        }
        touchIfStale(hash, cached, now);
        return identityOf(cached.userNo()).map(SessionData::of);
    }

    /**
     * 身份现读现算，**不从会话里取**。
     *
     * <p>于是改角色、改数据域、改昵称都是<b>下一个请求就生效</b>，不必踢人；
     * 而账号被停用时 {@link IdentityLoader} 返回空 —— 这让「停用后立即失效」
     * 多了一道保险，不必只依赖踢人那条路径。
     */
    private Optional<LoginUser> identityOf(String userNo) {
        LoginUser cachedUser = identityCache.get(userNo);
        if (cachedUser != null) {
            return Optional.of(cachedUser);
        }
        Optional<LoginUser> loaded = identities.load(userNo);
        if (loaded.isEmpty()) {
            // 令牌有效但用户查不到（数据清过、库不一致）。**不能给一个空身份放行** ——
            // 那是一个没有任何权限的幽灵身份在系统里游走：多数接口会把它挡住所以不报错，
            // 直到碰上一个只判「登录了没」的接口
            log.warn("孤儿会话：令牌有效但用户不存在或不可用 pool={} userNo={}",
                    profile.poolName(), userNo);
            // 这不是「没登录」，是**数据不一致**（令牌还在、人没了）。必须看得见
            audit(LoginEvent.ORPHAN_SESSION, userNo, "USER_NOT_FOUND", false);
            return Optional.empty();
        }
        identityCache.put(userNo, loaded.get());
        return loaded;
    }

    /** {@code last_seen_at} 节流写回 —— 每请求写会把这张表变成全库写最频繁的表。 */
    private void touchIfStale(String hash, CachedSession cached, LocalDateTime now) {
        LocalDateTime last = cached.lastSeenAt();
        if (last != null && last.plus(profile.lastSeenThrottle()).isAfter(now)) {
            return;
        }
        sessions.touch(hash, now);
        sessionCache.put(hash, new CachedSession(cached.userNo(), cached.expiresAt(), now));
    }

    /**
     * 会话内容变了。
     *
     * <p><b>在这个实现里它退化成「剔身份缓存」</b>：会话表不存身份，
     * 下一个请求自然会重新加载。旧实现要把整份 {@code LoginUser} 重写进存储，
     * 那正是「第二个真源」的来源。
     */
    @Override
    public void refresh(String token, SessionData data) {
        if (token == null || !token.startsWith(profile.tokenPrefix())) {
            return;
        }
        identityCache.evict(data.user().userNo());
    }

    @Override
    public void revoke(String token) {
        if (token == null || !token.startsWith(profile.tokenPrefix())) {
            return;
        }
        String hash = TokenHash.of(token);
        Optional<SessionRow> row = sessions.findByHash(hash);
        sessions.revoke(hash, RevokeReason.LOGOUT, LocalDateTime.now(clock));
        sessionCache.evict(hash);
        row.ifPresent(r -> audit(LoginEvent.LOGOUT, r.userNo(), null, true));
    }

    /**
     * 踢掉某个主体的全部会话。一条 UPDATE，**跨实例生效**。
     *
     * <p>本地的即时性靠**复用撤销轮询**：写完库立刻在本进程跑一次
     * {@link #pollRevocations()}，于是「本实例立刻、其它实例最长一个轮询周期」——
     * 一个机制两处用，不必再维护一份「user_no → 本地缓存里的哪些令牌」的反向索引，
     * 而那种索引正是最容易与真实状态漂移的东西。
     */
    @Override
    public int revokeUser(String userNo) {
        int n = sessions.revokeByUser(userNo, RevokeReason.DISABLED, LocalDateTime.now(clock));
        identityCache.evict(userNo);
        pollRevocations();
        if (n > 0) {
            audit(LoginEvent.REVOKED, userNo, RevokeReason.DISABLED.name() + " x" + n, true);
        }
        return n;
    }

    /**
     * 撤销传播：把「上次之后新撤销的」逐条剔出本地缓存。
     *
     * <p>由调度按 {@link SessionProfile#revokePoll} 调用，也在 {@link #revokeUser} 之后立刻调一次。
     *
     * <p><b>只剔那几条，不清空整个缓存</b> —— 清空会在踢一个人时让所有在线用户的
     * 下一次请求一起回源，把一次撤销放大成库上的尖峰。
     *
     * @return 这一轮剔掉了几条
     */
    public int pollRevocations() {
        // **水位线在查询之前取。** 反过来的话，查询与赋值之间发生的撤销会两头落空：
        // 这一轮没查到，下一轮又被新水位线排除
        LocalDateTime pollStart = LocalDateTime.now(clock);
        List<String> hashes = sessions.findRevokedSince(revokeWatermark, REVOKE_BATCH);
        for (String hash : hashes) {
            sessionCache.evict(hash);
        }
        // 推到本轮开始时刻，而不是最后一条的时间：后者在同一时刻有多条时会漏掉。
        // 与 findRevokedSince 的闭区间配合，边界那一条会被重复剔一次 —— 幂等，无后果
        revokeWatermark = pollStart;
        if (!hashes.isEmpty()) {
            log.debug("撤销传播 pool={} 剔除 {} 条", profile.poolName(), hashes.size());
        }
        return hashes.size();
    }

    /**
     * 落一条审计。**IP/UA 取自 {@link RequestMetaContext}** —— 过滤器给每个请求都设了，
     * 登录接口也不例外。取不到（比如后台线程调 revokeUser）就留空，不猜。
     */
    private void audit(LoginEvent event, String userNo, String reason, boolean ok) {
        RequestMetaContext.Meta meta = RequestMetaContext.current();
        String ip = meta == null ? null : meta.ip();
        String ua = meta == null ? null : meta.userAgent();
        if (ok) {
            this.audit.success(event, userNo, reason, ip, ua);
        } else {
            this.audit.failure(event, userNo, reason, ip, ua);
        }
    }

    /** 审计里丢了多少条（异步队列满）。**丢了要看得见**，否则「日志少了」永远查不出来。 */
    public long auditDropped() {
        return audit.dropped();
    }

    /** 命中率等，暴露成指标用。条目上限设小了只表现为「查库变多」，没有指标只能靠猜。 */
    public List<AuthCache.Stats> cacheStats() {
        return List.of(sessionCache.stats(), identityCache.stats());
    }
}
