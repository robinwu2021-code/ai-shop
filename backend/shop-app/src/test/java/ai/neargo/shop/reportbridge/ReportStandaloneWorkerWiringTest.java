package ai.neargo.shop.reportbridge;

import ai.neargo.shop.spi.report.ReportSourceFactsPort;
import ai.neargo.shop.spi.report.ReportDayPort;
import ai.neargo.shop.spi.report.ReportAffectedDaysPort;
import ai.neargo.shop.svc.InternalHttp;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.ClassUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="food.build.shape",matches="standalone")
class ReportStandaloneWorkerWiringTest {
    @Test void workerHasRemoteFactsDayAndCorrectionPortsWithoutPublicControllersOrLocalCore() {
        var configs=new ArrayList<Class<?>>();
        for(String candidate:ImportCandidates.load(AutoConfiguration.class,getClass().getClassLoader()))
            if(candidate.startsWith("ai.neargo.shop.food.config.")) configs.add(ClassUtils.resolveClassName(candidate,getClass().getClassLoader()));
        new ApplicationContextRunner().withPropertyValues("spring.profiles.active=worker","shop.food.enabled=true",
                "shop.food.deployment=standalone","shop.food.migration.mode=off","shop.report.enabled=true","shop.report.food.enabled=true")
                .withBean(InternalHttp.class,()->mock(InternalHttp.class))
                .withConfiguration(AutoConfigurations.of(configs.toArray(Class<?>[]::new))).run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(ReportDayPort.class).hasSingleBean(ReportAffectedDaysPort.class);
                    assertThat(context.getBeansOfType(ReportSourceFactsPort.class)).hasSize(1);
                    assertThat(context.containsBean("bizDiningReportController")).isFalse();
                    assertThat(ClassUtils.isPresent("ai.neargo.shop.food.config.FoodAutoConfiguration",getClass().getClassLoader())).isFalse();
                });
    }
}
