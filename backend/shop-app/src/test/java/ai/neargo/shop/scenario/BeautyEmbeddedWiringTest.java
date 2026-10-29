package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.neargo.shop.config.MybatisPlusConfig;
import ai.neargo.shop.svc.InternalHttp;
import java.util.Optional;
import ai.neargo.shop.config.PlatformDataSourceConfig;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.util.ClassUtils;

class BeautyEmbeddedWiringTest {
    private static Class<?> deploymentConfiguration() {
        return assertDoesNotThrow(
                () -> Class.forName("ai.neargo.shop.config.BeautyDeploymentConfiguration"));
    }

    private static final String WEB = "ai.neargo.shop.beauty.config.BeautyWebAutoConfiguration";
    private static final String CORE = "ai.neargo.shop.beauty.config.BeautyAutoConfiguration";
    private static final String SHAPE = System.getProperty("beauty.build.shape", "retail");

    @Test
    void hostClasspathMatchesSelectedBuild() {
        assertThat(present(WEB)).isEqualTo(!SHAPE.equals("retail"));
        assertThat(present(CORE)).isEqualTo(SHAPE.equals("embedded"));
        assertThat(present("ai.neargo.shop.beauty.server.BeautyApplication")).isFalse();
    }

    @Test
    @EnabledIfSystemProperty(named = "beauty.build.shape", matches = "standalone")
    void standaloneIngressHasNoBeautyCoreJar() {
        assertThat(getClass().getClassLoader().getResource(CORE.replace('.', '/') + ".class")).isNull();
        assertThat(getClass().getClassLoader().getResource(WEB.replace('.', '/') + ".class")).isNotNull();
    }

    @Test
    void enabledDeploymentMustMatchBuildShape() {
        for (String mode : new String[] {"embedded", "standalone"}) {
            hostRunner().withPropertyValues("shop.beauty.enabled=true", "shop.beauty.deployment=" + mode,
                    "spring.profiles.active=api").run(context -> {
                        if (SHAPE.equals(mode)) {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBeansOfType(
                                    ClassUtils.resolveClassName(WEB, getClass().getClassLoader())))
                                    .hasSize(1);
                        } else {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                        }
                    });
        }
    }

    @Test
    void missingRequiredDeploymentFailsClosed() {
        hostRunner().withPropertyValues("shop.beauty.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void invalidDeploymentIsRejected() {
        hostRunner().withPropertyValues("shop.beauty.enabled=true", "shop.beauty.deployment=typo")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void disabledBeautyKeepsRetailBootable() {
        new ApplicationContextRunner()
                .withUserConfiguration(deploymentConfiguration(), PlatformDataSourceConfig.class,
                        MybatisPlusConfig.class, PersistenceProperties.class)
                .withPropertyValues("shop.beauty.enabled=false", "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.datasource.url=jdbc:h2:mem:beauty_host_boot;MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa", "spring.datasource.password=")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SqlSessionFactory.class)
                            .hasSingleBean(MybatisPlusInterceptor.class);
                    var interceptor = context.getBean(MybatisPlusInterceptor.class);
                    assertThat(interceptor.getInterceptors()).map(Object::getClass)
                            .containsExactly(DataPermissionInterceptor.class, PaginationInnerInterceptor.class,
                                    OptimisticLockerInnerInterceptor.class);
                    try (var connection = context.getBean(DataSource.class).getConnection();
                            var result = connection.createStatement().executeQuery("SELECT 1")) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getInt(1)).isEqualTo(1);
                    }
                    assertThat(context.getBeanNamesForType(deploymentConfiguration())).isEmpty();
                });
    }

    private ApplicationContextRunner hostRunner() {
        var configs = new ArrayList<Class<?>>();
        for (String name : new String[] {WEB, CORE}) {
            if (present(name)) {
                configs.add(ClassUtils.resolveClassName(name, getClass().getClassLoader()));
            }
        }
        return new ApplicationContextRunner().withPropertyValues("shop.beauty.migration.mode=validate")
                .withUserConfiguration(deploymentConfiguration())
                .withConfiguration(AutoConfigurations.of(configs.toArray(Class<?>[]::new)));
    }

    private static boolean present(String name) {
        return ClassUtils.isPresent(name, BeautyEmbeddedWiringTest.class.getClassLoader());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MybatisPlusProperties.class)
    static class PersistenceProperties {
    }

    @Test
    void platformScanExcludesBeautyAndPreservesExistingBoundaries() {
        var filters = Arrays.stream(MybatisPlusConfig.class.getAnnotationsByType(MapperScan.class))
                .flatMap(scan -> Arrays.stream(scan.excludeFilters()))
                .flatMap(filter -> Arrays.stream(filter.pattern()))
                .map(Pattern::compile).toList();
        for (String excluded : new String[] {"ai.neargo.shop.beauty.mapper.BeautyMappers$TableMapper",
                "ai.neargo.shop.inventory.mapper.InventoryMappers$StockMapper",
                "ai.neargo.shop.food.mapper.FoodMappers$TableMapper",
                "ai.neargo.shop.pay.mapper.PayMappers$SettleMapper"}) {
            assertThat(filters.stream().anyMatch(pattern -> pattern.matcher(excluded).matches()))
                    .as("global scan must exclude %s", excluded).isTrue();
        }
        assertThat(filters.stream().noneMatch(pattern ->
                pattern.matcher("ai.neargo.shop.trade.mapper.OrderMapper").matches())).isTrue();
    }
    @Test
    @EnabledIfSystemProperty(named = "beauty.build.shape", matches = "standalone")
    void standaloneAutoConfigurationProvidesBothRemoteServicesWithoutCore() {
        Class<?> channel = ClassUtils.resolveClassName("ai.neargo.shop.beauty.config.BeautyChannelAutoConfiguration",
                getClass().getClassLoader());
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(channel,
                ClassUtils.resolveClassName(WEB, getClass().getClassLoader())))
                .withBean(InternalHttp.class, () -> new InternalHttp(service -> Optional.empty(), "test-only-token"))
                .withPropertyValues("shop.beauty.enabled=true", "shop.beauty.deployment=standalone",
                        "spring.profiles.active=api").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("remoteStorePolicyService").getClass().getSimpleName())
                            .isEqualTo("RemoteStorePolicyService");
                    assertThat(context.getBean("remoteServiceDefinitionService").getClass().getSimpleName())
                            .isEqualTo("RemoteServiceDefinitionService");
                    assertThat(context.getBeansOfType(ClassUtils.resolveClassName(
                            "ai.neargo.shop.beauty.api.biz.BizStorePolicyController", getClass().getClassLoader())))
                            .hasSize(1);
                    assertThat(context.getBeansOfType(ClassUtils.resolveClassName(
                            "ai.neargo.shop.beauty.api.biz.BizServiceDefinitionController",
                            getClass().getClassLoader())))
                            .hasSize(1);
                    assertThat(present(CORE)).isFalse();
                });
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(channel))
                .withBean(InternalHttp.class, () -> new InternalHttp(service -> Optional.empty(), "test-only-token"))
                .withPropertyValues("shop.beauty.enabled=false", "shop.beauty.deployment=standalone")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("remoteStorePolicyService")
                        .doesNotHaveBean("remoteServiceDefinitionService"));
    }

}
