package ai.neargo.shop.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公共依赖包里那半套认证栈**不许被装进来**。
 *
 * <h2>要挡的是什么</h2>
 * <p>{@code neargo-common-security} 的 {@code SecurityAutoConfiguration} 会注册一个
 * {@code TenantContextFilter}。它做的事是（反编译确认）：
 *
 * <pre>
 * getHeader("X-Region-Id") / ("X-User-Id") / ("X-Merchant-Id") / ("X-Store-Scope")
 *     → TenantContext.set(...)
 * </pre>
 *
 * <p><b>直接把客户端请求头当身份</b>，没有任何认证 —— 那套包的部署形态是
 * 「上游网关已鉴权并注入可信头」，而本项目不是：nginx 只反代，
 * 既不注入也不剥离这些头。同一套包里的 {@code NeargoTenantLineHandler}
 * 读的正是 {@code TenantContext.getMerchantId()}，作为 SQL 上的租户条件。
 *
 * <h2>为什么值得一道闸</h2>
 * <p>现在挡住它的是 {@code application.yml} 里三行 {@code spring.autoconfigure.exclude}。
 * <b>YAML 列表是覆盖不是合并</b> —— 任何人为别的目的重写这一段，或者加一个
 * profile 专属的同名键，这个过滤器就静默回来。<b>回来之后没有任何症状</b>，
 * 一直到有人用了 {@code TenantContext.getUserId()}（那个方法名看起来完全合理）。
 *
 * <p>断言的是**上下文里没有这个 bean**，不是「yml 里有那三行」——
 * 后者钉的是当前写法，换个等价写法（比如换成 `@SpringBootApplication(exclude=…)`）
 * 就会误报，而真正要保证的事没变。
 */
@SpringBootTest
@ActiveProfiles("test")
class CommonPackageIsolationTest {

    @Autowired
    private ApplicationContext ctx;

    /** 公共包里不该被装配的类。全限定名写死 —— 它们不在编译期依赖里出现。 */
    private static final List<String> FORBIDDEN = List.of(
            "ai.neargo.common.security.TenantContextFilter",
            "ai.neargo.common.security.session.SessionStore",
            "ai.neargo.common.data.NeargoTenantLineHandler");

    @Test
    @DisplayName("★★★ 公共包那半套认证栈不许进上下文 —— 尤其那个读请求头当身份的过滤器")
    void commonSecurityStackIsNotWired() {
        /*
         * **对照量。** 下面那个循环对 ClassNotFound 是 continue —— 三个类要是都不在
         * classpath 上，循环一次断言都不做，测试空绿。而「空绿」与「真的没装进来」
         * 在结果上一模一样。所以先证明这些类确实在，断言才有意义。
         */
        long loadable = FORBIDDEN.stream().filter(fqcn -> {
            try {
                Class.forName(fqcn);
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        }).count();
        assertThat(loadable)
                .as("三个类一个都不在 classpath 上 —— 那下面的断言什么都没证明。"
                        + "多半是 neargo-common-* 的依赖没了或换了坐标")
                .isPositive();

        for (String fqcn : FORBIDDEN) {
            Class<?> type;
            try {
                type = Class.forName(fqcn);
            } catch (ClassNotFoundException e) {
                continue;   // 依赖换了版本、类没了 —— 那就更不可能被装进来
            }
            assertThat(ctx.getBeanNamesForType(type))
                    .as("%s 被装进了 Spring 上下文。\n"
                            + "TenantContextFilter 会把 X-User-Id / X-Merchant-Id / X-Store-Scope "
                            + "这些**客户端请求头**当成身份写进 TenantContext，"
                            + "而 nginx 不剥离它们。\n"
                            + "多半是 application.yml 的 spring.autoconfigure.exclude "
                            + "被改动或被某个 profile 覆盖了（YAML 列表是覆盖不是合并）。", fqcn)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("★★ /internal 端点必须都在 portal.internal 包里 —— 否则会被全局信封裹住且不报错")
    void internalEndpointsStayInTheirPackage() throws IOException {
        Path root = Path.of("..").toRealPath();
        try (Stream<Path> files = Files.walk(root)) {
            List<String> strays = files
                    .filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.toString().contains("/test/") && !f.toString().contains("/target/"))
                    .filter(f -> !f.toString().contains("/portal/internal/"))
                    .filter(f -> {
                        try {
                            return Files.readString(f).contains("Mapping(\"/internal");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(f -> root.relativize(f).toString())
                    .toList();

            assertThat(strays)
                    .as("这些文件在 ai.neargo.shop.portal.internal 之外声明了 /internal 端点。\n"
                            + "ApiResponseWrapper 按**包**排除信封（supports() 拿不到请求，只拿得到方法），"
                            + "所以它们的返回值会被套上 {code,msg,data} —— "
                            + "调用方按自己的契约解析，**不报错，只是每个字段都是 null**。"
                            + "2026-08-27 真撞过一次：调度器起来了、端点 200、"
                            + "任务声明变成一条条空记录。")
                    .isEmpty();
        }
    }
}
