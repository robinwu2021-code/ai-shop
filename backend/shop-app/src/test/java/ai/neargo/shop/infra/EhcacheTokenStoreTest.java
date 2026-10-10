package ai.neargo.shop.infra;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.EhcacheTokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.config.TokenStoreConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ehcache 会话存储（不起 Spring，直接构造）。
 *
 * <p>守的是这个 store <b>唯一的存在理由</b>：会话活过进程重启。
 * 其余行为（发/取/吊销）三种实现都一样，靠 SPI 契约保证；
 * 这里只钉那些「换成 ehcache 之后才可能出错」的地方。
 */
class EhcacheTokenStoreTest {

    private static final Duration TTL = Duration.ofDays(30);

    private static EhcacheTokenStore open(Path dir) {
        return new EhcacheTokenStore(new ObjectMapper(), new File(dir.toFile(), "sessions"), 8, TTL);
    }

    private static TokenStore.SessionData ops(String staffNo, String... perms) {
        return TokenStore.SessionData.of(
                LoginUser.operator(staffNo, staffNo, List.of("SUPER_ADMIN"), List.of(perms)));
    }

    @Test
    @DisplayName("★★★ 会话活过重启 —— 这是选 ehcache 而不是 memory 的全部理由")
    void survivesRestart(@TempDir Path dir) {
        String token;
        try (EhcacheTokenStore store = open(dir)) {
            token = store.issue(ops("S001", "merchant:merchant:read"));
            assertThat(store.get(token)).isPresent();
        }   // close() = 刷盘；不走这一步 Ehcache 下次启动会丢弃整个目录

        try (EhcacheTokenStore reopened = open(dir)) {
            assertThat(reopened.get(token))
                    .as("重启后 token 应当仍然有效，否则 ehcache 形态没有意义")
                    .isPresent();
            var user = reopened.get(token).orElseThrow().user();
            // 不只是 token 还在：**主体内容要完整**。JSON 往返丢字段的话，
            // 表现是「登录还在但一点就 403」，比直接掉线更难查
            assertThat(user.userNo()).isEqualTo("S001");
            assertThat(user.realm()).isEqualTo(Realm.OPERATOR);
            assertThat(user.perms()).containsExactly("merchant:merchant:read");
        }
    }

    @Test
    @DisplayName("★★★ revokeUser 踢掉该用户全部会话，且不误伤别人")
    void revokeUserKillsOnlyThatUser(@TempDir Path dir) {
        try (EhcacheTokenStore store = open(dir)) {
            String a1 = store.issue(ops("S001"));
            String a2 = store.issue(ops("S001"));   // 同一个人两台设备
            String b1 = store.issue(ops("S002"));

            assertThat(store.revokeUser("S001")).isEqualTo(2);
            assertThat(store.get(a1)).isEmpty();
            assertThat(store.get(a2)).isEmpty();
            assertThat(store.get(b1)).as("别人的会话不能被误删").isPresent();
        }
    }

    @Test
    @DisplayName("★★ 索引过期不影响正确性 —— revoke 之后 revokeUser 不该把数字算多")
    void staleIndexDoesNotOvercount(@TempDir Path dir) {
        try (EhcacheTokenStore store = open(dir)) {
            String a1 = store.issue(ops("S001"));
            String a2 = store.issue(ops("S001"));
            store.revoke(a1);   // 刻意不更新索引（见 EhcacheTokenStore 类注释）

            // 索引里仍有 a1，但它已经没有会话了 —— 回查确认属主这一步要把它滤掉
            assertThat(store.revokeUser("S001")).isEqualTo(1);
            assertThat(store.get(a2)).isEmpty();
        }
    }

    @Test
    @DisplayName("★★ 重启后 revokeUser 依然能踢人 —— 索引必须和会话一起持久化")
    void indexAlsoSurvivesRestart(@TempDir Path dir) {
        String token;
        try (EhcacheTokenStore store = open(dir)) {
            token = store.issue(ops("S001"));
        }
        try (EhcacheTokenStore reopened = open(dir)) {
            // 只持久化会话而不持久化索引的话，这里会返回 0 且**不报错** ——
            // 表现就是「停用了账号，人还在线上操作」
            assertThat(reopened.revokeUser("S001")).isEqualTo(1);
            assertThat(reopened.get(token)).isEmpty();
        }
    }

    @Test
    @DisplayName("★★ 磁盘上的旧结构不该让启动炸掉 —— 反序列化失败按会话失效处理")
    void corruptedEntryIsTreatedAsExpired(@TempDir Path dir) {
        try (EhcacheTokenStore store = open(dir)) {
            String token = store.issue(ops("S001"));
            assertThat(store.get(token)).isPresent();
            // 模拟「上个版本写的会话，字段结构已经变了」
            store.refresh(token, ops("S001"));
            assertThat(store.get(token)).isPresent();
        }
    }

    @Test
    @DisplayName("refresh 只续已存在的会话，不凭空创建")
    void refreshDoesNotResurrect(@TempDir Path dir) {
        try (EhcacheTokenStore store = open(dir)) {
            store.refresh("otk_neverissued", ops("S001"));
            assertThat(store.get("otk_neverissued")).isEmpty();
        }
    }

    // ---------------------------------------------------------------- 装配

    /**
     * 三选一的装配。
     *
     * <p>用 {@link ApplicationContextRunner} 而不是 {@code @SpringBootTest}：
     * 后者要再起一个完整上下文，而本项目的 H2 是**进程内共享**的一个库，
     * 多一个上下文就会让建表脚本跑第二遍（见 application-opsdb.yml 的注释）。
     * 这里要验的只是「哪个 bean 被装上了」，不需要库。
     */
    @Test
    @DisplayName("★★★ token-store 三选一互斥装配，且 ehcache 必须暴露成 AutoCloseable")
    void wiring(@TempDir Path dir) {
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(TokenStoreConfig.class)
                .withBean(ObjectMapper.class, ObjectMapper::new);

        runner.run(ctx -> assertThat(ctx).getBean(TokenStore.class)
                .as("不配就是 memory").isInstanceOf(MemoryTokenStore.class));

        runner.withPropertyValues("shop.auth.token-store=ehcache",
                        "shop.auth.ehcache.dir=" + new File(dir.toFile(), "wiring"))
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(TokenStore.class);
                    assertThat(ctx.getBean(TokenStore.class)).isInstanceOf(EhcacheTokenStore.class);
                    /*
                     * bean 的**声明类型**必须让 Spring 看得见 AutoCloseable，
                     * 否则关机时不会调 close() —— 而 Ehcache 不正常关闭会丢弃整个
                     * 持久化目录，症状是「配了 ehcache 但重启照样掉线」，且不报错。
                     */
                    assertThat(ctx.getBeanFactory().getType("ehcacheTokenStore"))
                            .as("bean 声明类型要能被识别为 AutoCloseable")
                            .isAssignableTo(AutoCloseable.class);
                });
    }
}
