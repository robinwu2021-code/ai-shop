package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.inventory.domain.mapper.InventoryMappers;
import ai.neargo.shop.config.FoodDeploymentConfiguration;
import ai.neargo.shop.config.MybatisPlusConfig;
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

class FoodEmbeddedWiringTest {
    private static final String WEB = "ai.neargo.shop.food.config.FoodWebAutoConfiguration";
    private static final String CORE = "ai.neargo.shop.food.config.FoodAutoConfiguration";
    private static final String SHAPE = System.getProperty("food.build.shape", "retail");

    @Test
    void hostClasspathMatchesSelectedBuild() {
        assertThat(present(WEB)).isEqualTo(!SHAPE.equals("retail"));
        assertThat(present(CORE)).isEqualTo(SHAPE.equals("embedded"));
        assertThat(present("ai.neargo.shop.food.server.FoodApplication")).isFalse();
        assertThat(present("ai.neargo.shop.food.port.FoodPricingSourceFactsPortImpl"))
                .isEqualTo(!SHAPE.equals("retail"));
        for(String service:new String[]{"DiningSession","Check","CheckCart","DiningPricingFacts","Reservation","Dish","OptionGroup","Recipe","Menu",
                "DiningOrder","DiningCheckout","DiningReturn","BusinessReturn","BusinessShare","DiningBatchQuery",
                "KitchenTask","BusinessClose","FoodPrint",
                "ProductionConsumption","KitchenReturn","Queue","DiningPickupVerification"}) {
            assertThat(present("ai.neargo.shop.food.service."+service+"Service")).isEqualTo(!SHAPE.equals("retail"));
            assertThat(present("ai.neargo.shop.food.foodclient.Remote"+service+"Service")).isEqualTo(!SHAPE.equals("retail"));
            assertThat(present("ai.neargo.shop.food.service.impl."+service+"ServiceImpl")).isEqualTo(SHAPE.equals("embedded"));
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "food.build.shape", matches = "standalone")
    void standaloneIngressHasNoFoodCoreJar() {
        assertThat(getClass().getClassLoader().getResource(CORE.replace('.', '/') + ".class")).isNull();
        assertThat(getClass().getClassLoader().getResource(WEB.replace('.', '/') + ".class")).isNotNull();
    }

    @Test
    void enabledDeploymentMustMatchBuildShape() {
        for (String mode : new String[] {"embedded", "standalone"}) {
            hostRunner().withPropertyValues("shop.food.enabled=true", "shop.food.deployment=" + mode,
                    "spring.profiles.active=api").run(context -> {
                        if (SHAPE.equals(mode)) {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBeansOfType(ClassUtils.resolveClassName(WEB, getClass().getClassLoader())))
                                    .hasSize(1);
                        } else {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                        }
                    });
        }
    }

    @Test
    void invalidDeploymentIsRejected() {
        hostRunner().withPropertyValues("shop.food.enabled=true", "shop.food.deployment=typo")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void disabledFoodKeepsRetailBootable() {
        new ApplicationContextRunner()
                .withUserConfiguration(FoodDeploymentConfiguration.class, PlatformDataSourceConfig.class,
                        MybatisPlusConfig.class, PersistenceProperties.class)
                .withPropertyValues("shop.food.enabled=false", "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.datasource.url=jdbc:h2:mem:food_host_boot;MODE=MySQL;DB_CLOSE_DELAY=-1",
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
                    assertThat(context.getBeanNamesForType(FoodDeploymentConfiguration.class)).isEmpty();
                });
    }

    private ApplicationContextRunner hostRunner() {
        var configs = new ArrayList<Class<?>>();
        for (String name : new String[] {WEB, CORE}) {
            if (present(name)) {
                configs.add(ClassUtils.resolveClassName(name, getClass().getClassLoader()));
            }
        }
        return new ApplicationContextRunner().withPropertyValues("shop.food.migration.mode=off").withUserConfiguration(FoodDeploymentConfiguration.class)
                .withConfiguration(AutoConfigurations.of(configs.toArray(Class<?>[]::new)));
    }

    private static boolean present(String name) {
        return ClassUtils.isPresent(name, FoodEmbeddedWiringTest.class.getClassLoader());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MybatisPlusProperties.class)
    static class PersistenceProperties {
    }

    @Test
    void platformScanExcludesFoodAndPreservesExistingBoundaries() {
        var filters = Arrays.stream(MybatisPlusConfig.class.getAnnotationsByType(MapperScan.class))
                .flatMap(scan -> Arrays.stream(scan.excludeFilters()))
                .flatMap(filter -> Arrays.stream(filter.pattern()))
                .map(Pattern::compile).toList();
        for (String excluded : new String[] {"ai.neargo.shop.food.mapper.FoodMappers$TableMapper",
                "ai.neargo.shop.pay.mapper.PayMappers$SettleMapper"}) {
            assertThat(filters.stream().anyMatch(pattern -> pattern.matcher(excluded).matches()))
                    .as("global scan must exclude %s", excluded).isTrue();
        }
        assertThat(filters.stream().noneMatch(pattern ->
                pattern.matcher("ai.neargo.shop.trade.mapper.OrderMapper").matches())).isTrue();
        // 进销存搬进独立服务（ADR-046）后包名不在 ai.neargo.shop 下，全局扫描根本够不着它 ——
        // 比「靠排除规则挡住」更硬。这里守的是「它别被挪回扫描根之下」
        String inventoryMapper = InventoryMappers.BalanceMapper.class.getName();
        assertThat(Arrays.stream(MybatisPlusConfig.class.getAnnotationsByType(MapperScan.class))
                .flatMap(scan -> Arrays.stream(scan.basePackages()))
                .noneMatch(base -> inventoryMapper.startsWith(base + "."))).isTrue();
    }
}
