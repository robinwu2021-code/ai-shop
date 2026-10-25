package ai.neargo.shop.printbridge;

import ai.neargo.shop.printing.config.PrintingConfiguration;
import ai.neargo.shop.printing.service.PrintService;
import ai.neargo.shop.printing.api.internal.InternalPrintController;
import ai.neargo.shop.spi.printing.PrintPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrintRecoveryConfigurationTest {
    @Test void canonicalRecoveryExistsWithCenterDisabledAndEnabled() {
        for(boolean enabled:new boolean[]{false,true}) {
            var owner=mock(PrintService.class);
            new ApplicationContextRunner().withUserConfiguration(PrintingConfiguration.class,InternalPrintController.class)
                    .withBean(PrintService.class,()->owner)
                    .withBean("transactionManager",org.springframework.transaction.PlatformTransactionManager.class,
                            ()->new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                    new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                            "jdbc:h2:mem:print-recovery-config-"+enabled+";DB_CLOSE_DELAY=-1","sa","")))
                    .withPropertyValues("shop.print.enabled="+enabled,"shop.services.internal-token=trusted-token")
                    .run(context->{
                        assertThat(context).hasSingleBean(InternalPrintController.class);
                        var controller=context.getBean(InternalPrintController.class);
                        var scope=new PrintPort.Scope("E","S","ADMIN");
                        var req=new PrintService.DeviceResolution("D",1,"A","queue stopped","proof",true,"KEY");
                        controller.resolveDeviceUncertainty(new InternalPrintController.ResolveDeviceCommand(scope,req),"trusted-token");
                        verify(owner).resolveDeviceUncertainty(scope,req);
                        controller.resolveDeviceUncertainty(new InternalPrintController.ResolveDeviceCommand(scope,req),"wrong-token");
                        verifyNoMoreInteractions(owner);
                    });
        }
    }
}
