package ai.neargo.shop.printing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ai.neargo.shop.printing.config.PrintingConfiguration;
import ai.neargo.shop.printing.port.PrintPortImpl;
import ai.neargo.shop.printing.service.PrintService;
import ai.neargo.shop.spi.printing.PrintPort;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** The shared configuration must expose one SPI owner and respect an explicitly provided transport substitute. */
class PrintingPortConfigurationTest {
    private final PrintService service = mock(PrintService.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PrintingConfiguration.class)
            .withBean(PrintService.class, () -> service)
            .withBean("transactionManager", DataSourceTransactionManager.class,
                    () -> new DataSourceTransactionManager(mock(DataSource.class)));

    @Test
    void defaultPortDelegatesToTheExistingLocalService() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PrintService.class).hasSingleBean(PrintPort.class);
            assertThat(context.getBean(PrintPort.class)).isInstanceOf(PrintPortImpl.class);
            var scope = new PrintPort.Scope("E", "S", "A");
            context.getBean(PrintPort.class).get(scope, "J");
            verify(service).get(scope, "J");
        });
    }

    @Test
    void providedPortSubstituteIsNotDuplicatedOrReplaced() {
        var replacement = mock(PrintPort.class);
        runner.withBean(PrintPort.class, () -> replacement).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PrintService.class).hasSingleBean(PrintPort.class);
            assertThat(context.getBean(PrintPort.class)).isSameAs(replacement);
            assertThat(context.getBeansOfType(PrintPortImpl.class)).isEmpty();
        });
    }
}
