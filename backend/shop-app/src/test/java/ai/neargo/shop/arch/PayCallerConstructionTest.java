package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code PayCaller} 只能在两处构造（ADR-045 §2-1）：
 * <ol>
 *   <li>接入守卫 {@code ai.neargo.shop.pay.api.guard..} —— 外部 API、终端、收银台等入口认出调用方的地方；</li>
 *   <li>{@code InternalCallers} —— 进程内入口（内嵌形态的业务系统、商家操作员、运营、收银台访客）的工厂。
 *       它留在支付领域，因为 {@code PaymentPortImpl} 等领域代码要用它，挪进守卫会成环。</li>
 * </ol>
 * 其余任何地方 {@code new PayCaller(...)} 都等于自造一个身份 —— 能力矩阵按身份放行，自造身份就绕过了它。
 *
 * <p>按源码扫，不按类路径：{@code pay-svc} 不在本模块的类路径上，源码扫描覆盖全部后端模块。
 * {@code PayCaller} 的类注释原先写着由「{@code PayCallerFactoryTest}」守着，那条测试并不存在 —— 本条补上。
 */
class PayCallerConstructionTest {

    private static final Path BACKEND = Paths.get(System.getProperty("user.dir")).getParent();
    private static final Pattern NEW_CALLER = Pattern.compile("new\\s+PayCaller\\s*\\(");
    private static final String GUARD = "/pay/pay-guard/src/main/java/ai/neargo/shop/pay/api/guard/";
    private static final String FACTORY = "/pay/pay-domain/src/main/java/ai/neargo/shop/pay/acquiring/port/InternalCallers.java";

    @Test
    @DisplayName("★★★ new PayCaller 只出现在接入守卫与 InternalCallers —— 别处自造身份就绕过了能力矩阵")
    void payCallerIsBuiltOnlyByGuardsAndTheInternalFactory() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(BACKEND)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .toList()) {
                scanned++;
                String path = f.toString().replace('\\', '/');
                if (path.contains(GUARD) || path.endsWith(FACTORY)) {
                    continue;
                }
                if (NEW_CALLER.matcher(Files.readString(f, StandardCharsets.UTF_8)).find()) {
                    offenders.add(BACKEND.relativize(f).toString());
                }
            }
        }
        assertThat(scanned).as("一个源文件都没扫到 —— 下面的断言恒真").isGreaterThan(1000);
        assertThat(offenders).as("在守卫与 InternalCallers 之外构造了 PayCaller").isEmpty();
    }
}
