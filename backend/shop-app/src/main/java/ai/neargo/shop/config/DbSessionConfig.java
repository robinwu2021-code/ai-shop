package ai.neargo.shop.config;

import ai.neargo.auth.store.AuthCache;
import ai.neargo.auth.store.IdentityLoader;
import ai.neargo.auth.store.LoginLogDao;
import ai.neargo.auth.store.LoginLogWriter;
import ai.neargo.auth.store.SessionDao;
import ai.neargo.auth.store.SessionProfile;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.RealmRoutingTokenStore;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.auth.store.DbTokenStore;
import ai.neargo.shop.merchant.auth.MerchantIdentityLoader;
import ai.neargo.shop.platform.auth.OperatorIdentityLoader;
import ai.neargo.shop.user.auth.ConsumerIdentityLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Value;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 会话进库的装配（{@code shop.auth.token-store=db}，或按端覆盖成 {@code db}）。
 *
 * <p><b>支持一端一端地切。</b>{@code shop.auth.token-store-by-realm.operator=db}
 * 只把运营端搬进库，C 端与 B 端仍走原来那个共享存储 ——
 * 于是会话外置可以先在十几个运营账号上跑一天，再推到全量用户。
 * 判定见 {@link TokenStoreSelection}。
 *
 * <p><b>三个池各一套：DAO、两级缓存、身份加载器。</b>缓存**必须分开** ——
 * 共用一份等于把刚在存储层分开的边界，在内存里又合上了。
 *
 * <p>业务代码注入的仍然是 {@link TokenStore}：{@link RealmRoutingTokenStore}
 * 按令牌前缀/会话 realm 自动分发，没有一处业务需要知道有三个池。
 * 唯一的例外是 {@code revokeUser} —— 主体号不带池信息，必须显式指明，
 * 见 {@link TokenStores} 的类注释。
 */
@Configuration
@Conditional(TokenStoreSelection.AnyRealmUsesDb.class)
public class DbSessionConfig {

    private static final Logger log = LoggerFactory.getLogger(DbSessionConfig.class);

    @Bean
    @Primary
    RealmRoutingTokenStore realmRoutingTokenStore(
            JdbcClient authJdbcClient,
            ConsumerIdentityLoader consumers,
            MerchantIdentityLoader merchants,
            OperatorIdentityLoader operators,
            java.util.Map<Realm, LoginLogWriter> loginLogWriters,
            Environment env,
            @Qualifier(TokenStoreConfig.SHARED) ObjectProvider<TokenStore> sharedStore) {

        Map<Realm, TokenStore> byRealm = new EnumMap<>(Realm.class);
        byRealm.put(Realm.CONSUMER, TokenStoreSelection.usesDb(env, Realm.CONSUMER)
                ? store(authJdbcClient, Realm.CONSUMER, SessionProfiles.CONSUMER,
                        // ⚠️ 过渡期：C 端池里还装着 B 端会话，见 TransitionalConsumerIdentityLoader
                        new TransitionalConsumerIdentityLoader(consumers, merchants),
                        loginLogWriters.get(Realm.CONSUMER))
                : shared(sharedStore, Realm.CONSUMER, env));
        byRealm.put(Realm.MERCHANT, TokenStoreSelection.usesDb(env, Realm.MERCHANT)
                ? store(authJdbcClient, Realm.MERCHANT, SessionProfiles.MERCHANT,
                        merchants, loginLogWriters.get(Realm.MERCHANT))
                : shared(sharedStore, Realm.MERCHANT, env));
        byRealm.put(Realm.OPERATOR, TokenStoreSelection.usesDb(env, Realm.OPERATOR)
                ? store(authJdbcClient, Realm.OPERATOR, SessionProfiles.OPERATOR,
                        operators, loginLogWriters.get(Realm.OPERATOR))
                : shared(sharedStore, Realm.OPERATOR, env));

        log.info("会话存储按端装配：{}", TokenStoreSelection.all(env));
        return new RealmRoutingTokenStore(byRealm);
    }

    /**
     * 还没切的那些端，继续用原来那一个共享存储。
     *
     * <p>拿不到就<b>启动失败</b>，不回落到内存：回落的表现是「这一端的人
     * 每次重启全部掉线」，而那和配置写错完全是两回事，现场分不出来。
     */
    private static TokenStore shared(ObjectProvider<TokenStore> provider,
                                     Realm realm, Environment env) {
        TokenStore s = provider.getIfAvailable();
        if (s == null) {
            throw new IllegalStateException(
                    "%s 端配的是 %s，但容器里没有对应的共享存储 bean —— 检查 %s"
                            .formatted(realm, TokenStoreSelection.kindOf(env, realm),
                                    TokenStoreSelection.GLOBAL));
        }
        return s;
    }

    private static DbTokenStore store(JdbcClient jdbc, Realm realm, SessionProfile p,
                                      IdentityLoader<LoginUser> identities,
                                      LoginLogWriter audit) {
        int entries = SessionProfiles.cacheEntries(p);
        return new DbTokenStore(realm, p, new SessionDao(jdbc, p), identities,
                // **堆内、绝不落盘**：这里出现 disk 层就是把 2026-08-24 那次全员掉线装了回来。
                // 本缓存的权威都在库里，丢了只是回源查一次
                new AuthCache<>("auth." + p.poolName() + ".session",
                        String.class, DbTokenStore.CachedSession.class, p.cacheTtl(), entries),
                new AuthCache<>("auth." + p.poolName() + ".identity",
                        String.class, LoginUser.class, p.identityTtl(), entries),
                // 审计从签发/撤销处落，**不必改任何登录代码**（写入器由 LoginAuditConfig 共享）
                audit,
                Clock.systemDefaultZone());
    }

    /**
     * 撤销传播。
     *
     * <p><b>刻意不用 {@code @Scheduled}。</b>本仓库有一条架构守卫：
     * 每个 {@code @Scheduled} 方法都必须加 {@code @SchedulerLock}，
     * 否则多实例下会各跑一遍 —— 那条规则是对的，但<b>对这一条恰恰相反</b>：
     * 撤销传播剔的是**本进程的本地缓存**，它必须**每个实例都跑**。
     * 加了分布式锁，就只有抢到锁的那台会剔缓存，其余各台继续认着已被踢掉的会话 ——
     * 而那正是这整套改造要消灭的状态。
     *
     * <p>所以这里自己排，而不是去给守卫开一个例外：
     * 守卫的语义（「凡 @Scheduled 必须加锁」）保持为真，本任务只是不属于它管的那一类。
     */
    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService revocationPollScheduler(
            RealmRoutingTokenStore routing,
            @Value("${shop.auth.revoke-poll-ms:5000}") long pollMs) {

        /*
         * **只轮询真的进了库的那些端。** 分批切换时另外两端还是共享存储，
         * 它们没有「别的实例撤销了会话」这件事可传播 ——
         * 无脑强转会在启动时 ClassCastException，而那条报错指向的是轮询器，
         * 与真因（某一端还没切）隔着十万八千里。
         */
        List<DbTokenStore> stores = java.util.Arrays.stream(Realm.values())
                .map(routing::of)
                .filter(DbTokenStore.class::isInstance)
                .map(DbTokenStore.class::cast)
                .toList();

        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auth-revoke-poll");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(() -> {
            for (DbTokenStore s : stores) {
                try {
                    s.pollRevocations();
                } catch (RuntimeException e) {
                    /*
                     * 一个池抖了不能带停另外两个；更要紧的是**不能让异常逃出这个
                     * Runnable** —— scheduleWithFixedDelay 会因此把任务整个取消，
                     * 于是撤销传播从此不再跑，而没有任何地方会说它停了。
                     */
                    log.error("撤销轮询失败，本轮跳过 pool={} 异常={}",
                            s, e.getClass().getSimpleName(), e);
                }
            }
        }, pollMs, pollMs, TimeUnit.MILLISECONDS);

        log.info("撤销轮询已启动，间隔 {}ms", pollMs);
        return exec;
    }
}
