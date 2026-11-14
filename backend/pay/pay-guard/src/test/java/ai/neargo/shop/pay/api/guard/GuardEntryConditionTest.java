package ai.neargo.shop.pay.api.guard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;

/**
 * 按 {@code shop.pay.guard.entries} 装配各入口的守卫（ADR-045；开发计划 M1.3）：
 * 同一个 jar 被 {@code shop-app}（内嵌）与 {@code pay-svc}（支付服务进程）引用，各自只装需要的入口。
 */
class GuardEntryConditionTest {

    @Configuration(proxyBeanMethods = false)
    static class OneBeanPerEntry {

        @Bean
        @ConditionalOnGuardEntry(PortalFace.INTERNAL)
        String internalGuard() {
            return "INTERNAL";
        }

        @Bean
        @ConditionalOnGuardEntry(PortalFace.OPEN)
        String openGuard() {
            return "OPEN";
        }

        @Bean
        @ConditionalOnGuardEntry(PortalFace.OPS)
        String opsGuard() {
            return "OPS";
        }

        @Bean
        @ConditionalOnGuardEntry(PortalFace.BIZ)
        String bizGuard() {
            return "BIZ";
        }

        @Bean
        @ConditionalOnGuardEntry(PortalFace.CHECKOUT)
        String checkoutGuard() {
            return "CHECKOUT";
        }

        @Bean
        @ConditionalOnGuardEntry(PortalFace.TERMINAL)
        String terminalGuard() {
            return "TERMINAL";
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(OneBeanPerEntry.class);

    @Test
    @DisplayName("★★★ 不配置 = 内嵌形态（shop-app 今天的样子）：对外五个入口都装，内部入口不装")
    void unsetMeansEmbeddedDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasBean("openGuard").hasBean("opsGuard").hasBean("bizGuard")
                    .hasBean("checkoutGuard").hasBean("terminalGuard");
            assertThat(ctx).doesNotHaveBean("internalGuard");
        });
    }

    @Test
    @DisplayName("★★★ pay-svc 配 INTERNAL：只装内部入口，对外入口一个都不装")
    void internalOnly() {
        runner.withPropertyValues("shop.pay.guard.entries=INTERNAL").run(ctx -> {
            assertThat(ctx).hasBean("internalGuard");
            assertThat(ctx).doesNotHaveBean("openGuard").doesNotHaveBean("opsGuard").doesNotHaveBean("bizGuard")
                    .doesNotHaveBean("checkoutGuard").doesNotHaveBean("terminalGuard");
        });
    }

    @Test
    @DisplayName("★★ 逗号分隔、去空格、不分大小写")
    void listIsTrimmedAndCaseInsensitive() {
        runner.withPropertyValues("shop.pay.guard.entries= open , Internal ").run(ctx -> {
            assertThat(ctx).hasBean("openGuard").hasBean("internalGuard");
            assertThat(ctx).doesNotHaveBean("opsGuard");
        });
    }

    @Test
    @DisplayName("★★ 写错入口名启动即失败，并列出可选值 —— 不静默少装一个入口")
    void unknownEntryFailsFast() {
        runner.withPropertyValues("shop.pay.guard.entries=OPEN,TERMNIAL").run(ctx -> {
            assertThat(ctx).hasFailed();
            // 是否被容器包一层随 Spring 版本变；取最具体的那个异常判，两种都成立
            assertThat(NestedExceptionUtils.getMostSpecificCause(ctx.getStartupFailure()))
                    .hasMessageContaining("TERMNIAL").hasMessageContaining("INTERNAL").hasMessageContaining("TERMINAL");
        });
    }
}
