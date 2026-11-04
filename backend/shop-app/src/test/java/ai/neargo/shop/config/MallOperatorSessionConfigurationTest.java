package ai.neargo.shop.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.auth.store.FreshAccountReadDataSource;
import ai.neargo.shop.platform.auth.OperatorIdentityLoader;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

class MallOperatorSessionConfigurationTest {
    private ApplicationContextRunner runner(boolean reader) {
        var ds = new FreshAccountReadDataSource(mock(DataSource.class), mock(DataSource.class));
        var perms = mock(LivePermResolver.class);
        when(perms.supportsFreshReads()).thenReturn(reader);
        return new ApplicationContextRunner()
                .withUserConfiguration(MallOperatorSessionConfiguration.class)
                .withBean(DataSource.class, () -> ds)
                .withBean(
                        org.springframework.transaction.PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(ds))
                .withBean(LivePermResolver.class, () -> perms)
                .withBean(OperatorIdentityLoader.class, () -> mock(OperatorIdentityLoader.class));
    }

    @Test
    void legacyDefaultsDoNotInstallStrictMallOps() {
        runner(false)
                .run(
                        c -> {
                            assertNull(c.getStartupFailure());
                            assertEquals(0, c.getBeansOfType(MallOperatorSessions.class).size());
                        });
    }

    @Test
    void mallHttpRefusesNonDbOpsAndMissingFreshReader() {
        runner(true)
                .withPropertyValues("commerce.enabled=true", "commerce.mall.http.enabled=true")
                .run(c -> assertNotNull(c.getStartupFailure()));
        runner(false)
                .withPropertyValues(
                        "commerce.enabled=true",
                        "commerce.mall.http.enabled=true",
                        "shop.auth.token-store-by-realm.operator=db")
                .run(c -> assertNotNull(c.getStartupFailure()));
    }

    @Test
    void explicitlySupportedDbOpsWorksWithPaymentFlagsOff() {
        runner(true)
                .withPropertyValues(
                        "commerce.enabled=true",
                        "commerce.mall.http.enabled=true",
                        "shop.auth.token-store-by-realm.operator=db",
                        "commerce.mall.payment.enabled=false",
                        "commerce.mall.refund.enabled=false")
                .run(
                        c -> {
                            assertNull(c.getStartupFailure());
                            assertNotNull(c.getBean(MallOperatorSessions.class).verifier());
                        });
    }
}
