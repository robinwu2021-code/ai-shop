package ai.neargo.shop.config;

import ai.neargo.auth.store.LoginEvent;
import ai.neargo.auth.store.LoginLogDao;
import ai.neargo.auth.store.LoginLogWriter;
import ai.neargo.auth.store.SessionProfile;
import ai.neargo.shop.auth.LoginAuditor;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.RequestMetaContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.EnumMap;
import java.util.Map;

/**
 * 登录审计的装配。**与 {@code shop.auth.token-store} 无关，一直开着。**
 *
 * <p>审计不该因为会话后端还没切库就停止工作 —— 那正好是切换前后最需要对照的时期。
 * 三张日志表由迁移建好，写不写它与会话存在哪里是两件事。
 */
@Configuration
public class LoginAuditConfig {

    /**
     * 三张会话表与三张日志表都在平台库。
     *
     * <p>将来某一端拆去独立库时，只需给那一端换一个 {@link JdbcClient} ——
     * DAO 一行不改，这正是把它做成构造参数的理由。
     */
    @Bean
    JdbcClient authJdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    Map<Realm, LoginLogWriter> loginLogWriters(JdbcClient authJdbcClient) {
        Map<Realm, LoginLogWriter> writers = new EnumMap<>(Realm.class);
        writers.put(Realm.CONSUMER, writer(authJdbcClient, SessionProfiles.CONSUMER));
        writers.put(Realm.MERCHANT, writer(authJdbcClient, SessionProfiles.MERCHANT));
        writers.put(Realm.OPERATOR, writer(authJdbcClient, SessionProfiles.OPERATOR));
        return writers;
    }

    private static LoginLogWriter writer(JdbcClient jdbc, SessionProfile p) {
        return new LoginLogWriter(new LoginLogDao(jdbc, p), p);
    }

    /**
     * <b>常开，不看 {@code shop.auth.token-store} 是什么。</b>
     *
     * <p>成功与失败都走这里 —— 原先成功是在 {@code DbTokenStore} 的签发处落的，
     * 而那条路只在 db 形态下存在，生产（ehcache）于是一条成功记录都没有。
     * 见 {@link LoginAuditor} 的类注释。
     */
    @Bean
    LoginAuditor loginAuditor(Map<Realm, LoginLogWriter> loginLogWriters) {
        return new LoginAuditor() {
            @Override
            public void failed(Realm realm, String principal, String reason) {
                RequestMetaContext.Meta meta = RequestMetaContext.current();
                loginLogWriters.get(realm).failure(
                        LoginEvent.LOGIN_FAILED, principal, reason,
                        meta == null ? null : meta.ip(),
                        meta == null ? null : meta.userAgent());
            }

            @Override
            public void succeeded(Realm realm, String userNo) {
                RequestMetaContext.Meta meta = RequestMetaContext.current();
                loginLogWriters.get(realm).success(
                        LoginEvent.LOGIN, userNo, null,
                        meta == null ? null : meta.ip(),
                        meta == null ? null : meta.userAgent());
            }
        };
    }
}
