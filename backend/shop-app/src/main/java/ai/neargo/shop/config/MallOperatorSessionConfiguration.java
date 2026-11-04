package ai.neargo.shop.config;

import ai.neargo.auth.store.*;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.auth.store.*;
import ai.neargo.shop.platform.auth.OperatorIdentityLoader;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.Optional;

import javax.sql.DataSource;

/** New mall OPS only. Existing login and permission fallback policies are unchanged. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "commerce",
        name = {"enabled", "mall.http.enabled"},
        havingValue = "true")
public class MallOperatorSessionConfiguration {
    @Bean
    MallOperatorSessions mallOperatorSessions(
            Environment environment,
            DataSource dataSource,
            PlatformTransactionManager manager,
            OperatorIdentityLoader identities,
            LivePermResolver permissions) {
        if (!TokenStoreSelection.usesDb(environment, Realm.OPERATOR))
            throw new IllegalStateException(
                    "Mall OPS requires shop.auth.token-store-by-realm.operator=db and a new DB"
                        + " login");
        if (!permissions.supportsFreshReads())
            throw new IllegalStateException(
                    "Mall OPS requires an authoritative fresh permission reader");
        if (!(dataSource instanceof FreshAccountReadDataSource)
                || !(manager instanceof DataSourceTransactionManager jdbc)
                || jdbc.getDataSource() != dataSource)
            throw new IllegalStateException(
                    "Mall OPS requires the shared primary transaction resource and bounded fresh"
                        + " pool");
        IdentityLoader<LoginUser> strict =
                new IdentityLoader<>() {
                    public Optional<LoginUser> load(String no) {
                        return identities.loadFresh(no);
                    }

                    public SubjectKind kind() {
                        return SubjectKind.OPS;
                    }
                };
        return new MallOperatorSessions(
                new DbAccountSessionVerifier(
                        new SessionDao(JdbcClient.create(dataSource), SessionProfiles.OPERATOR),
                        new CompositeIdentityLoader<>(strict),
                        Clock.systemDefaultZone(),
                        manager,
                        Realm.OPERATOR));
    }
}
