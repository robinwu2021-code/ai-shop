package ai.neargo.shop.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 接入守卫的边界（ADR-045）：
 * <ul>
 *   <li>依赖方向只许 {@code pay-portal} / {@code pay-svc} → {@code pay-guard} → {@code pay-domain}；</li>
 *   <li>controller 不读任何鉴权头 —— 「谁在调」只经守卫认出、经参数给到；</li>
 *   <li>运营端的守卫不判权限码：权限归平台账号体系，判在端点的 {@code @perm.can}（G10）。</li>
 * </ul>
 */
class GuardDependencyTest {

    private static final Path BACKEND = Paths.get(System.getProperty("user.dir")).getParent();
    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ai.neargo.shop");
    }

    @Test
    @DisplayName("★★★ 守卫不依赖 controller 那一层（pay-portal 的入口子包与公共包）")
    void guardDoesNotDependOnPortal() {
        noClasses().that().resideInAPackage("ai.neargo.shop.pay.api.guard..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "ai.neargo.shop.pay.api.open..", "ai.neargo.shop.pay.api.ops..", "ai.neargo.shop.pay.api.biz..",
                        "ai.neargo.shop.pay.api.checkout..", "ai.neargo.shop.pay.api.terminal..",
                        "ai.neargo.shop.pay.api.common..", "ai.neargo.shop.pay.svc..")
                .because("依赖方向只许 pay-portal / pay-svc → pay-guard（ADR-045）")
                .check(classes);
    }

    @Test
    @DisplayName("★★★ 支付领域不反过来依赖守卫 —— 否则 pay-domain → pay-guard 成环")
    void domainDoesNotDependOnGuard() {
        noClasses().that().resideInAPackage("ai.neargo.shop.pay..")
                .and().resideOutsideOfPackage("ai.neargo.shop.pay.api..")
                .should().dependOnClassesThat().resideInAPackage("ai.neargo.shop.pay.api.guard..")
                .because("pay-guard 依赖 pay-domain，反向即成环（ADR-045）")
                .check(classes);
    }

    @Test
    @DisplayName("★★ 运营端守卫不引用平台权限码：判权在端点 @perm.can，守卫只认会话（G10）")
    void opsGuardDoesNotJudgePermissions() {
        noClasses().that().resideInAPackage("ai.neargo.shop.pay.api.guard.ops..")
                .should().dependOnClassesThat().haveFullyQualifiedName("ai.neargo.shop.auth.Perms")
                .because("运营端权限统一归平台账号体系（ADR-045 §2-5）")
                .check(classes);
    }

    @Test
    @DisplayName("★★★ controller 不读鉴权头：X-App-Id / X-Signature / X-Terminal-Sn / X-Internal-* 只在守卫里读")
    void controllersDoNotReadAuthHeaders() throws IOException {
        Pattern authHeader = Pattern.compile(
                "\"(X-App-Id|X-Timestamp|X-Nonce|X-Signature|X-Key-Serial|X-Encrypt-Serial|X-Terminal-Sn|X-Internal-Caller"
                        + "|X-Internal-Credential|X-Call-Source)\"|PaymentInternalPaths\\.(CALLER|CREDENTIAL)");
        List<Path> roots = List.of(
                BACKEND.resolve("pay/pay-portal/src/main/java"),
                BACKEND.resolve("pay/pay-svc/src/main/java"));
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    scanned++;
                    if (authHeader.matcher(Files.readString(f, StandardCharsets.UTF_8)).find()) {
                        offenders.add(BACKEND.relativize(f).toString());
                    }
                }
            }
        }
        assertThat(scanned).as("没扫到 controller 源码 —— 断言恒真").isGreaterThan(20);
        assertThat(offenders).as("controller 层读了鉴权头").isEmpty();
    }
}
