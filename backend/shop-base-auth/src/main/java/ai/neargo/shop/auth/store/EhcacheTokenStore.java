package ai.neargo.shop.auth.store;

import ai.neargo.shop.auth.TokenStore;
import org.ehcache.Cache;
import org.ehcache.PersistentCacheManager;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ExpiryPolicyBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.EntryUnit;
import org.ehcache.config.units.MemoryUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Ehcache 会话（<b>单机持久化</b>形态）。堆内缓存 + 本地磁盘，进程重启不掉线。
 *
 * <p><b>它解决的是 {@link MemoryTokenStore} 的哪个问题</b>：memory store 每次重启后
 * 所有人都要重新登录 —— 开发期烦，生产上是每次发版把全部在线用户踢下线。
 * 而为此引入 Redis 意味着多一个必须运维的进程。Ehcache 落在中间：
 * 零外部依赖，但会话活过重启。
 *
 * <p><b>它<u>不</u>解决什么 —— 部署前必须想清楚这一条</b>：磁盘是<b>本机</b>的，
 * 多副本部署时每个实例各存各的，同一个人被负载均衡打到另一个实例上就是未登录。
 * 横向扩容仍然必须 {@code token-store=redis}。这不是缺陷，是这个形态的定义。
 *
 * <h2>存 JSON 不存 Java 序列化</h2>
 * 与 {@link RedisTokenStore} 同一个理由，而在这里更要紧：磁盘上的数据会**跨版本存活**。
 * Java 序列化下，改一个会话字段就会让重启后的反序列化整片失败，
 * 而那批数据还赖在磁盘上，不删掉的话每次启动都炸一次。
 *
 * <h2>反向索引是「提示」，不是「真相」</h2>
 * {@code userNo → token 列表} 用来让 {@link #revokeUser} 不必全表扫。
 * <b>但它允许过期</b>：{@link #revoke} 不去更新它。双写维护一致的索引，
 * 一定会在某条异常路径上漏掉一次更新，而那种不一致的表现是「停用了账号但没踢下线」
 * —— 恰恰是最不能出错的那个场景。
 *
 * <p>所以这里反过来：索引只负责给出**候选**，真正删之前逐条回查会话确认属主。
 * 索引里的死 token 只是让 revokeUser 多读几条，读完顺手剔除。
 */
public class EhcacheTokenStore implements TokenStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EhcacheTokenStore.class);

    private static final String SESSION_CACHE = "shopSessions";
    private static final String USER_INDEX_CACHE = "shopSessionUserIndex";

    /** 索引里 token 的分隔符。token 本身是 {@code otk_/ctk_ + hex}，不含它 */
    private static final String SEP = ",";

    private final PersistentCacheManager cacheManager;
    private final Cache<String, String> sessions;
    private final Cache<String, String> userIndex;
    private final ObjectMapper mapper;

    /**
     * @param dir    磁盘目录。**每个实例必须独占一个** —— Ehcache 会对目录加文件锁，
     *               两个实例指同一个目录时后启动的那个直接起不来
     * @param diskMb 磁盘上限；写满后按 LRU 淘汰，表现为最久没活动的人掉线
     * @param ttl    会话有效期，与其余 store 一致
     */
    public EhcacheTokenStore(ObjectMapper mapper, File dir, int diskMb, Duration ttl) {
        this.mapper = mapper;
        this.cacheManager = CacheManagerBuilder.newCacheManagerBuilder()
                .with(CacheManagerBuilder.persistence(dir))
                .withCache(SESSION_CACHE, cacheConfig(diskMb, ttl))
                // 索引小得多，但**必须同样持久化**：只有会话活过重启而索引没有的话，
                // 重启后停用账号就踢不到人了 —— 而且不报错
                .withCache(USER_INDEX_CACHE, cacheConfig(Math.max(1, diskMb / 4), ttl))
                .build(true);
        this.sessions = cacheManager.getCache(SESSION_CACHE, String.class, String.class);
        this.userIndex = cacheManager.getCache(USER_INDEX_CACHE, String.class, String.class);
        log.info("会话存储 = ehcache（持久化目录 {}，磁盘上限 {}MB，TTL {} 天）"
                + " —— 单机形态，多副本部署请改用 redis", dir.getAbsolutePath(), diskMb, ttl.toDays());
    }

    private static CacheConfigurationBuilder<String, String> cacheConfig(int diskMb, Duration ttl) {
        return CacheConfigurationBuilder.newCacheConfigurationBuilder(
                        String.class, String.class,
                        ResourcePoolsBuilder.newResourcePoolsBuilder()
                                // 堆内只放热点条目，冷的落盘；true = 重启后保留
                                .heap(2_000, EntryUnit.ENTRIES)
                                .disk(diskMb, MemoryUnit.MB, true))
                /*
                 * timeToLive 而不是 timeToIdle：会话有效期是**从签发算起**的固定 30 天，
                 * 与「最近有没有活动」无关。用 idle 的话，一个一直在用的会话永不过期，
                 * 等于没有有效期。refresh() 重写条目会重置这个 TTL，那是显式续期。
                 */
                .withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(ttl));
    }

    @Override
    public String issue(SessionData data) {
        String token = TokenStore.newToken(data.user().realm());
        sessions.put(token, toJson(data));
        addToIndex(data.user().userNo(), token);
        return token;
    }

    @Override
    public Optional<SessionData> get(String token) {
        String json = sessions.get(token);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(json, SessionData.class));
        } catch (Exception e) {
            /*
             * 结构不兼容 —— 磁盘持久化下这**必然会发生**：改了会话字段之后，
             * 上一个版本写在磁盘上的条目还在。当作会话失效让人重登，并把它删掉，
             * 否则每次启动都会在同一条数据上反复抛。
             */
            log.debug("会话反序列化失败，按失效处理：{}", e.toString());
            sessions.remove(token);
            return Optional.empty();
        }
    }

    @Override
    public void refresh(String token, SessionData data) {
        // 与 memory store 一致：只续已存在的会话，不凭空创建
        if (sessions.containsKey(token)) {
            sessions.put(token, toJson(data));
        }
    }

    @Override
    public void revoke(String token) {
        // 不动索引：见类注释「反向索引是提示，不是真相」
        sessions.remove(token);
    }

    @Override
    public int revokeUser(String userNo) {
        if (userNo == null || userNo.isBlank()) {
            return 0;
        }
        String raw = userIndex.get(userNo);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        int killed = 0;
        for (String token : raw.split(SEP)) {
            if (token.isBlank()) {
                continue;
            }
            // 回查确认属主再删：索引可能过期，而**删错人的会话比漏删更糟**
            if (get(token).filter(d -> userNo.equals(d.user().userNo())).isPresent()) {
                sessions.remove(token);
                killed++;
            }
        }
        userIndex.remove(userNo);   // 这个人的会话已全部处理，索引连同死 token 一起清掉
        return killed;
    }

    /** 索引追加。**去重**：同一个人反复登录会让索引无限变长，而它是按 TTL 整条过期的 */
    private void addToIndex(String userNo, String token) {
        if (userNo == null || userNo.isBlank()) {
            return;
        }
        String raw = userIndex.get(userNo);
        Set<String> tokens = new LinkedHashSet<>();
        if (raw != null && !raw.isBlank()) {
            for (String t : raw.split(SEP)) {
                if (!t.isBlank()) {
                    tokens.add(t);
                }
            }
        }
        tokens.add(token);
        userIndex.put(userNo, String.join(SEP, tokens));
    }

    private String toJson(SessionData data) {
        try {
            return mapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalStateException("session serialize failed", e);
        }
    }

    /**
     * 关闭 CacheManager —— <b>磁盘持久化必须走这一步</b>。
     *
     * <p>不正常关闭的话 Ehcache 下次启动会认定磁盘状态不可信而丢弃整个持久化目录，
     * 表现就是「重启后所有人还是掉线了」，而日志里只有一行不起眼的 WARN。
     * Spring 会对实现了 {@link AutoCloseable} 的 bean 自动调它。
     */
    @Override
    public void close() {
        cacheManager.close();
    }

    /** 当前会话条目数。仅供诊断与测试 —— Ehcache 没有 size()，只能数迭代器 */
    public int sessionCount() {
        List<String> keys = new ArrayList<>();
        sessions.forEach(e -> keys.add(e.getKey()));
        return keys.size();
    }
}
