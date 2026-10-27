package ai.neargo.shop.pay.svc;

import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort;
import ai.neargo.shop.spi.entitlement.EntitlementSaleSourcePort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.pay.svc.client.port.CoreEntitlementGrantSourcePortImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付域独立进程能不能装起来（S20）。
 *
 * <h2>此前这件事只有部署那一刻才知道</h2>
 * 这个模块<b>一个测试都没有</b>。而 {@link PayApplication} 的类注释里
 * 记着三条只有真启动才会暴露的问题：
 * <ul>
 *   <li>Spring Security 的自动配置从存储层传递进来，任何请求都 401 且响应体为空；</li>
 *   <li>待远程 Port 的桩把 {@code Object.equals} 也拦了，容器自己用不了它；</li>
 *   <li>某个 Port 在 pay 侧已有本地实现，桩成了第二个 bean，两个 bean 撞车。</li>
 * </ul>
 * 三条都是<b>装配</b>问题 —— 主应用那 1700 条测试一条都碰不到，
 * 因为它们跑的是另一个进程的上下文。
 *
 * <p>{@code scripts/smoke-boot.sh} 也在验这件事，但它要先打包、只在部署前跑。
 * 这一条搬进 {@code mvn test}，于是每次都跑，且失败时给的是
 * Spring 自己的那份缺失清单 —— 比任何设计文档都准。
 *
 * <h2>它<b>不</b>验什么</h2>
 * 不验能接流量。支付域依赖 11 个业务侧 Port，实现都在业务模块里，
 * 而这个进程刻意不引它们。「装得起来但用不了」是这一步的定义，不是缺陷。
 */
@SpringBootTest(classes = PayApplication.class)
@ActiveProfiles("svctest")
class PayApplicationBootTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("★★★ 独立进程的上下文装得起来 —— 此前这件事只有部署那刻才知道")
    void contextLoads() {
        assertThat(ctx.getBean("payTxManager")).isSameAs(ctx.getBean("transactionManager"));
        assertThat(ctx.getBeanDefinitionCount())
                .as("上下文是空的 —— 那说明扫描包压根没生效，这条冒烟什么都没验")
                .isGreaterThan(50);
    }

    @Test
    @DisplayName("★★★ 鉴权链没有被装上 —— 装上的话这个进程的每个请求都是 401 且响应体为空")
    void securityChainStaysOut() {
        /*
         * 2026-09-01 第一次启动这个进程时撞过：neargo-common-security 经
         * pay-domain → shop-store-mybatis → shop-base-auth 传递进来，
         * classpath 上有 spring-security-config，Boot 就自动配一套
         * 「所有请求都要认证」。
         *
         * **摘掉直接依赖不等于树干净** —— 当时验过「pay-domain 的依赖树里 auth 归零」，
         * 而这一条是从存储层传进来的。所以判据放在<b>上下文里有没有那个 bean</b>，
         * 不是放在依赖树上。
         */
        assertThat(ctx.getBeanNamesForType(
                org.springframework.security.web.SecurityFilterChain.class))
                .as("鉴权链装上了 —— 这个进程只暴露 /internal（共享密钥）"
                        + "与 /callback（通道验签），两者都不该走用户鉴权链。"
                        + "症状是任何请求都 401 且响应体为空，日志里没有任何线索")
                .isEmpty();
    }

    @Test
    @DisplayName("★★★ 审计填充用仓库自有那份 —— 框架那份填 regionId / Instant，与实体字段对不上")
    void auditFillIsTheRepositoryOne() {
        /*
         * neargo-common-data 与 security 走同一条路进来（存储层 → shop-base-auth），
         * 它的 DataAutoConfiguration 带一个 @ConditionalOnMissingBean 的 MetaObjectHandler。
         * shop-app 在 application.yml 里排掉了它；这个进程只扫 ai.neargo.shop.pay，
         * 若也没排，谁来填 createdAt / createdBy / tenantNo 就取决于有没有别的 bean 先占位 ——
         * 框架那份写的是 regionId 与 Instant，实体上一个都没有，结果是这些列被写成 null。
         */
        assertThat(ctx.getBeanNamesForType(ai.neargo.common.data.DataAutoConfiguration.class))
                .as("框架的数据自动配置装上了 —— 主应用排掉了它，独立形态必须一致")
                .isEmpty();
        assertThat(ctx.getBeansOfType(com.baomidou.mybatisplus.core.handlers.MetaObjectHandler.class).values())
                .as("审计填充器必须恰好一个，且是 ai.neargo.shop.common 那份")
                .singleElement()
                .isInstanceOf(ai.neargo.shop.common.AuditMetaObjectHandler.class);
    }

    @Test
    @DisplayName("★★★ 这个进程的每个 HTTP 映射都在 /internal/** 下 —— 按运行时解析后的路径判，不按源码文本")
    void onlyInternalRoutes() {
        /*
         * TDD-支付服务-I0地基 AC5（修 R27）。端点路径全写成 PayInternalPaths 的常量，源码里找不到字面量，
         * 旧闸门按「文件里含 /internal 字样」放行 —— 一个注释就能骗过它。这里读 Spring 实际注册的路径。
         * 面向人与第三方的支付接口归 pay-portal（ADR-044），不在这个进程。
         */
        var mapping = ctx.getBean("requestMappingHandlerMapping",
                org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        java.util.List<String> paths = mapping.getHandlerMethods().entrySet().stream()
                // Spring Boot 自带的 /error（BasicErrorController）不是我们写的端点
                .filter(e -> !e.getValue().getBeanType().getName().startsWith("org.springframework.boot."))
                .flatMap(e -> e.getKey().getPatternValues().stream())
                .toList();
        assertThat(paths).as("一条映射都没读到 —— 那下面的断言恒真").isNotEmpty();
        assertThat(paths)
                .as("pay-svc 出现了 /internal 之外的路径")
                .allMatch(p -> p.equals("/internal") || p.startsWith("/internal/"));
    }

    @Test
    @DisplayName("★★ 内部端点在 —— 它是这个进程唯一对外说话的方式")
    void internalEndpointPresent() {
        assertThat(ctx.getBeanNamesForType(InternalPayEndpoint.class))
                .as("内部端点没装上，这个进程起来了也没人能和它说话")
                .hasSize(1);
    }
    @Test
    @DisplayName("★★★ 只装内部入口的守卫（shop.pay.guard.entries=INTERNAL）：对外入口的鉴权器与开放 API 服务一个都不进这个进程")
    void onlyTheInternalGuardIsWired() {
        /*
         * pay-guard 是 shop-app 与 pay-svc 共用的一个 jar（ADR-045），而这个进程扫描整个 ai.neargo.shop.pay。
         * 不按入口装配的话，开放 API 的鉴权器与它要的接入方服务会被一起扫进来 —— 进程能起来，但多了一套它不该有的入口。
         */
        assertThat(ctx.getBeanNamesForType(ai.neargo.shop.pay.api.guard.internal.InternalCallerFilter.class))
                .as("内部调用过滤器没装上 —— /internal/pay/v1 就没人识别调用方了")
                .hasSize(1);
        assertThat(ctx.getBeansOfType(ai.neargo.shop.pay.api.guard.core.PortalAuthenticator.class))
                .as("对外入口的鉴权器进了支付服务进程")
                .isEmpty();
        assertThat(ctx.getBeanNamesForType(ai.neargo.shop.pay.api.guard.open.service.AppAuthService.class))
                .as("开放 API 的接入方鉴权服务进了支付服务进程")
                .isEmpty();
    }

    @Test void saleCapacityAndReverseSourceHaveExactlyOneStandaloneOwner() {
        assertThat(ctx.getBeansOfType(EntitlementSaleReservationPort.class)).hasSize(1);
        assertThat(ctx.getBeansOfType(EntitlementSaleSourcePort.class)).hasSize(1);
        var source=ctx.getBean(EntitlementSaleSourcePort.class);
        assertThat(source).isInstanceOf(CoreEntitlementGrantSourcePortImpl.class);
        assertThat(source.supports(new SourceRef("CORE","PAID_CARD_ORDER","O1","L1",1,1,0))).isTrue();
        assertThat(ctx.getBeanNamesForType(InternalSaleReservationEndpoint.class)).hasSize(1);
    }
}
