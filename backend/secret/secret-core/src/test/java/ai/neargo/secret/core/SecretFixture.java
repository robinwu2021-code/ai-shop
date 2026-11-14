package ai.neargo.secret.core;

import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.store.SecretStoreTestSupport;
import ai.neargo.secret.store.SecretStoreTestSupport.Store;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 一套库 + 注入式主密钥 + 可拨动的时钟，组装与宿主相同的三个对象 */
final class SecretFixture {

    final Store store;
    final MovableClock clock = new MovableClock(Instant.parse("2026-10-26T00:00:00Z"));
    final List<InjectedMasterKeyProvider.Entry> masterKeys = new ArrayList<>();
    InjectedMasterKeyProvider provider;
    MasterKeyBook book;
    DefaultSecretVault vault;
    DefaultKeyRegistry registry;
    DatabaseKeyVault keyVault;
    SecretLifecycleService lifecycle;
    final String env;

    SecretFixture(String env) {
        this(env, SecretStoreTestSupport.freshStore());
    }

    private SecretFixture(String env, Store store) {
        this.env = env;
        this.store = store;
    }

    /** 同一个库，换一个环境的进程（主密钥清单清空，需重新 addMasterKey + start） */
    SecretFixture restartAs(String otherEnv) {
        return new SecretFixture(otherEnv, store);
    }

    static SecretFixture withPayKey() {
        SecretFixture f = new SecretFixture("SANDBOX");
        f.addMasterKey("pay-sandbox-a", SecretNamespace.PAY, true);
        return f.start();
    }

    /** 新增一把主密钥，返回它的材料（Base64）—— 只有测试会拿到这个字符串 */
    String addMasterKey(String alias, SecretNamespace ns, boolean active) {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String material = Base64.getEncoder().encodeToString(raw);
        if (active) {
            // 原来那把 active 降为只解密（仍留在进程里，等重新包裹完再撤）
            masterKeys.replaceAll(e -> e.active() && e.namespace() == ns
                    ? new InjectedMasterKeyProvider.Entry(e.alias(), e.namespace(), e.material(), false) : e);
        }
        masterKeys.add(new InjectedMasterKeyProvider.Entry(alias, ns, material, active));
        return material;
    }

    void dropMasterKey(String alias) {
        masterKeys.removeIf(e -> e.alias().equals(alias));
    }

    /** 按当前主密钥清单（重新）装配 —— 等于一次进程重启 */
    SecretFixture start() {
        provider = new InjectedMasterKeyProvider(List.copyOf(masterKeys));
        SecretSettings settings = new SecretSettings(env, clock);
        book = new MasterKeyBook(store.mapper(ai.neargo.secret.store.mapper.SecretMappers.MasterKeyMapper.class),
                provider, settings);
        var mappers = SecretStoreMappers.of(store.session());
        vault = new DefaultSecretVault(mappers, book, provider, settings, store.txManager());
        registry = new DefaultKeyRegistry(mappers, vault, settings, store.txManager());
        keyVault = new DatabaseKeyVault(mappers, registry, settings);
        lifecycle = new SecretLifecycleService(mappers, book, provider, vault, settings, store.txManager());
        book.sync();
        return this;
    }

    static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
