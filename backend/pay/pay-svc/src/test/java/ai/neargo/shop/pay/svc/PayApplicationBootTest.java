package ai.neargo.shop.pay.svc;

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
    @DisplayName("★★ 内部端点在 —— 它是这个进程唯一对外说话的方式")
    void internalEndpointPresent() {
        assertThat(ctx.getBeanNamesForType(InternalPayEndpoint.class))
                .as("内部端点没装上，这个进程起来了也没人能和它说话")
                .hasSize(1);
    }
}
