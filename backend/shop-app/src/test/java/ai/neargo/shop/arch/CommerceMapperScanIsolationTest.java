package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.neargo.shop.config.MybatisPlusConfig;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.ClassUtils;

import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;

/** 真正宿主全局扫描也必须遵守插件关闭，不只检查插件AutoConfiguration。 */
class CommerceMapperScanIsolationTest {
    @Test
    void globalHostMapperScanExcludesCommerce() {
        var scans = MybatisPlusConfig.class.getAnnotationsByType(MapperScan.class);
        var broad =
                Arrays.stream(scans)
                        .filter(
                                scan ->
                                        Arrays.asList(scan.basePackages())
                                                .contains("ai.neargo.shop"))
                        .findFirst()
                        .orElseThrow();
        assertThat(
                        Arrays.stream(broad.excludeFilters())
                                .flatMap(filter -> Arrays.stream(filter.pattern()))
                                .anyMatch(
                                        regex ->
                                                Pattern.matches(
                                                        regex,
                                                        "ai.neargo.shop.commerce.mapper.CommerceMappers$AccountMapper")))
                .isTrue();
    }

    @Test
    void realHostScannerDoesNotRegisterCommerceWhenDisabled() throws Exception {
        String mapper = "ai.neargo.shop.commerce.mapper.CommerceMappers$AccountMapper";
        assumeTrue(
                ClassUtils.isPresent(mapper, getClass().getClassLoader()),
                "commerce-embedded build only");
        Class<?> type = ClassUtils.forName(mapper, getClass().getClassLoader());
        new ApplicationContextRunner()
                .withUserConfiguration(MybatisPlusConfig.class)
                .withPropertyValues(
                        "shop.commerce.enabled=false",
                        "spring.datasource.url=jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBeanNamesForType(type)).isEmpty();
                        });
    }
}
