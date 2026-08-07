package ai.neargo.shop.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块边界与分层规则（TDD-backend §4.2 / §3.1）。**违反即构建失败**。
 *
 * <p>这些规则不是风格偏好：powerbank 的经验是，模块边界一旦靠自觉维护，
 * 三个月后就会出现「拆不动的单体」——每个 svc 都直接 import 了别的 svc 的实体。
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ai.neargo.shop");
    }

    @Test
    @DisplayName("svc 模块之间不得互相依赖，跨域只走 shop-spi 的 Port/Event")
    void svcModulesMustNotDependOnEachOther() {
        String[] domains = {"user", "product", "trade", "fulfillment", "marketing", "settle", "message", "platform"};
        for (String from : domains) {
            for (String to : domains) {
                if (from.equals(to)) {
                    continue;
                }
                ArchRule rule = noClasses().that().resideInAPackage("ai.neargo.shop." + from + "..")
                        .should().dependOnClassesThat().resideInAPackage("ai.neargo.shop." + to + "..")
                        .allowEmptyShould(true)
                        .because("svc-" + from + " 依赖 svc-" + to + " 会让模块无法独立拆分；改用 shop-spi 的 Port 或 Event");
                rule.check(classes);
            }
        }
    }

    /**
     * 禁止用全限定名书写注解与跨域类型引用。
     *
     * <p>这条不是风格洁癖，是**三次真实事故**换来的：
     * <ol>
     *   <li>{@code PurchaseHistoryPort} 被依赖扫描误判成「无人调用」——
     *       调用方写的是 {@code ai.neargo.shop.spi.trade.PurchaseHistoryPort xxx} 而不是 import</li>
     *   <li>模块改名时批量替换漏掉 {@code DevSeeder} 里的全限定引用，编译才报错</li>
     *   <li>{@code /mp/merchant/apply} 明明实现了，却因为写成
     *       {@code @org.springframework...PostMapping} 而被契约守卫报成「未实现」，
     *       覆盖率数字因此长期偏低</li>
     * </ol>
     *
     * <p>共同点：**任何按文本扫描代码的工具都会漏掉全限定写法**，而这个项目严重依赖
     * 这类扫描（契约比对、依赖分析、文档生成）。让编译期挡住比每次事后追查便宜。
     */
    @Test
    @DisplayName("注解与跨域类型不得用全限定名书写（会让静态扫描漏判）")
    void noFullyQualifiedReferences() {
        Path src = Path.of("src/main/java").toAbsolutePath();
        List<String> offenders = new ArrayList<>();
        try (var files = Files.walk(src)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                for (String line : text.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("import ") || t.startsWith("*") || t.startsWith("//")) {
                        continue;
                    }
                    if (t.contains("@org.springframework") || t.contains("ai.neargo.shop.spi.")) {
                        offenders.add(f.getFileName() + ": " + t);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        assertThat(offenders)
                .as("改成 import。全限定写法会让契约比对与依赖扫描漏判 —— 已因此出过三次问题")
                .isEmpty();
    }

    @Test
    @DisplayName("common 不得依赖任何业务域")
    void commonMustNotDependOnDomains() {
        noClasses().that().resideInAPackage("ai.neargo.shop.common..")
                .or().resideInAPackage("ai.neargo.shop.auth..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "ai.neargo.shop.user..", "ai.neargo.shop.product..", "ai.neargo.shop.trade..",
                        "ai.neargo.shop.fulfillment..", "ai.neargo.shop.marketing..", "ai.neargo.shop.settle..",
                        "ai.neargo.shop.message..", "ai.neargo.shop.platform..", "ai.neargo.shop.portal..")
                .because("common 是横切基础设施；依赖业务域会让每个 svc 被迫依赖 app")
                .check(classes);
    }

    @Test
    @DisplayName("Controller 只能住在 shop-app/portal 下")
    void controllersOnlyInPortal() {
        classes().that().haveSimpleNameEndingWith("Controller")
                .should().resideInAPackage("ai.neargo.shop.portal..")
                .allowEmptyShould(true)
                .because("Controller 散进 svc 会让 svc 绑死 web 层，微服务拆分时无法只搬领域逻辑")
                .check(classes);
    }

    @Test
    @DisplayName("领域 Service 必须是接口，实现类叫 *ServiceImpl")
    void serviceMustBeInterface() {
        // 只约束业务域：common 里的横切服务（IdempotencyService 等）没有多实现的可能，
        // 强行拆接口只会多一层无意义的间接
        classes().that().resideInAnyPackage(
                        "ai.neargo.shop.user..", "ai.neargo.shop.product..", "ai.neargo.shop.trade..",
                        "ai.neargo.shop.fulfillment..", "ai.neargo.shop.marketing..", "ai.neargo.shop.settle..",
                        "ai.neargo.shop.message..", "ai.neargo.shop.platform..")
                .and().haveSimpleNameEndingWith("Service").and().areNotInterfaces()
                .should().haveSimpleNameEndingWith("ServiceImpl")
                .allowEmptyShould(true)
                .because("Service 统一 interface + impl（powerbank 早期具体类后来都要回填规整）")
                .check(classes);
    }

    @Test
    @DisplayName("实现类与接口分包：Service 实现进 .impl，Port 实现进 .port")
    void implsMustLiveInDedicatedPackage() {
        /*
         * 接口与实现分包（2026-08-07 定，见 TDD-backend §3.4）。只靠约定的话，
         * 下一个人在 service 包里顺手建一个 XxxServiceImpl 不会有任何提示，
         * 44 个文件的一致性会从那一次开始烂掉。
         *
         * **两个落点不是不一致，是两种东西**：
         *   .impl —— Service 的实现。接口就在隔壁包，靠子包把两者分开
         *   .port —— Port 的实现。接口在 shop-spi **另一个 Maven 模块**里，
         *            分离度本就高于 .impl；这里的包名标的是「这是给别的域用的出口」，
         *            而不是「这是某个本地接口的实现」。
         * 混在一起（Port 实现塞进 service/impl）会让人以为它是本域 Service 的一部分，
         * 而它恰恰要绕过 Service 直连 Mapper —— 10 个 Port 实现里 9 个如此。
         */
        classes().that().haveSimpleNameEndingWith("ServiceImpl")
                .should().resideInAPackage("..impl..")
                .allowEmptyShould(true)
                .because("Service 的接口与实现分包（TDD-backend §3.4）")
                .check(classes);

        /*
         * 按**实现了什么接口**查，不是按类名 —— 上一版只查 `*PortImpl`，
         * 于是 5 个由 Service 兼任实现的 Port（SettleServiceImpl / MerchantServiceImpl /
         * CommunityServiceImpl / UserServiceImpl）**整整齐齐地绕过了这条规则**。
         * 类名是可以随便起的，实现关系不是。
         */
        classes().that().areAssignableTo(
                        com.tngtech.archunit.base.DescribedPredicate.describe("任一 Port 接口",
                                (com.tngtech.archunit.core.domain.JavaClass c) ->
                                        c.getPackageName().startsWith("ai.neargo.shop.spi")
                                                && c.getSimpleName().endsWith("Port")))
                .and().areNotInterfaces()
                .should().resideInAPackage("..port..")
                .allowEmptyShould(true)
                .because("Port 实现集中在各域的 .port 包 —— Service 兼任会让本域逻辑的改动"
                        + "不知不觉改掉跨域契约的行为，且两拨受众看到的能力范围不同")
                .check(classes);
    }

    @Test
    @DisplayName("Controller 不得直接依赖 Mapper（必须经 Service）")
    void controllersMustNotTouchMappers() {
        noClasses().that().resideInAPackage("ai.neargo.shop.portal..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Mapper")
                .allowEmptyShould(true)
                .because("Controller 直连 Mapper 等于把业务写进 web 层，数据域与状态机都会被绕过")
                .check(classes);
    }
}
