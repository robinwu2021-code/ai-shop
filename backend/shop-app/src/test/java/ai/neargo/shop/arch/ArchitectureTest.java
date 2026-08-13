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
 * 三个月后就会出现「拆不动的单体」——每个域都直接 import 了别的域的实体。
 *
 * <p><b>2026-08 模块合并之后，这些规则判的是「包」不是「Maven 模块」</b>：
 * 13 个模块并成了 6 个（{@code shop-base/core/merchant/settle/channel/app}），
 * 但包名一个没动 —— 于是「域之间不得互相依赖」照样成立，一条规则都不用改。
 * 这也正是当初按包写规则、而不是按模块写的原因。
 */
class ArchitectureTest {

    /**
     * 业务域清单 —— 这份名单是下面多条规则的**共同依据**，必须只有一份。
     *
     * <p>此前它被抄写了三遍（域间依赖、common 反向依赖、Service 接口化各一份），
     * 三份已经开始漂：加一个域要记得改三处，漏掉哪处，哪条规则就对新域失效，
     * 而且**不会有任何报错**——规则只是悄悄地少管一个域。
     *
     * <p>{@code merchant} 与 {@code community} 现在还嵌在 {@code shop.user} 下（见
     * 模块优化实施步骤 S3/S4），此刻匹配不到任何类。**提前登记是有意的**：
     * 等它们迁出来的那一刻，边界规则立即生效，不需要谁记得回来补名单。
     */
    private static final String[] DOMAINS = {
            "user", "merchant", "community", "product", "trade",
            "fulfillment", "marketing", "settle", "message", "platform",
            // content：内容与素材（帖子/问答/榜单/素材库）。有自己的表（cnt_*），
            // 所以是业务域而不是基础设施 —— 登记进来它才受域间依赖规则约束
            "content"};

    /** {@link #DOMAINS} 的 ArchUnit 包表达式形式（{@code ai.neargo.shop.x..}）。 */
    private static String[] domainPackages() {
        return java.util.Arrays.stream(DOMAINS).map(d -> "ai.neargo.shop." + d + "..").toArray(String[]::new);
    }

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ai.neargo.shop");
    }

    @Test
    @DisplayName("业务域之间不得互相依赖，跨域只走 spi 包的 Port/Event")
    void svcModulesMustNotDependOnEachOther() {
        String[] domains = DOMAINS;
        for (String from : domains) {
            for (String to : domains) {
                if (from.equals(to)) {
                    continue;
                }
                ArchRule rule = noClasses().that().resideInAPackage("ai.neargo.shop." + from + "..")
                        .should().dependOnClassesThat().resideInAPackage("ai.neargo.shop." + to + "..")
                        .allowEmptyShould(true)
                        .because(from + " 域依赖 " + to + " 域会让它们无法独立拆分；改用 spi 包的 Port 或 Event");
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
        String[] forbidden = java.util.stream.Stream
                .concat(java.util.Arrays.stream(domainPackages()), java.util.stream.Stream.of("ai.neargo.shop.portal.."))
                .toArray(String[]::new);
        noClasses().that().resideInAPackage("ai.neargo.shop.common..")
                .or().resideInAPackage("ai.neargo.shop.auth..")
                .should().dependOnClassesThat().resideInAnyPackage(forbidden)
                .because("common 是横切基础设施；依赖业务域会让每个域被迫依赖 app")
                .check(classes);
    }

    /**
     * Controller 的两个合法落点（S7 垂直切片后）。
     *
     * <p>原规则是「只能住在 shop-app/portal」，理由写的是「Controller 散进业务域会让域
     * 绑死 web 层，拆分时无法只搬领域逻辑」。这条理由恰恰是反的：把某个域的 API 面
     * 留在 app 里，拆微服务时才要**同时**搬两个工程，而且得先从 23 个 Controller 里
     * 认出哪几个属于这个域。域绑 web 层的真正风险是**领域逻辑读 request**，
     * 那由 {@link #domainsMustNotTouchWebRuntime()} 挡着，与 Controller 放哪无关。
     *
     * <p>两个落点各有明确职责：
     * <ul>
     *   <li>{@code ..<域>.api..} —— 只用到<b>本域</b>服务的 API 面。跟着域走，一起搬</li>
     *   <li>{@code shop.portal..} —— <b>跨域组合</b>的 API 面（BFF）。它按定义就属于
     *       装配层：一个接口要同时用商家和商品，这个组合关系不属于其中任何一个域</li>
     * </ul>
     *
     * <p>「shop-app 里 Controller 数量为 0」曾被写进 S7 的验收标准，那条是错的：
     * 23 个里有 5 个真正跨域（{@code MpCatalogController} 触及 4 个域），
     * 把它们塞进任一个域都会立刻违反域间依赖规则。跨域组合必须有地方待，
     * app 层就是那个地方。
     */
    @Test
    @DisplayName("Controller 只能住在域的 api 包或 app 的 portal 包")
    void controllersInDomainApiOrPortal() {
        classes().that().haveSimpleNameEndingWith("Controller")
                .should().resideInAnyPackage("ai.neargo.shop.portal..", "..api..")
                .allowEmptyShould(true)
                .because("单域 API 面跟着域走（拆微服务时一起搬）；跨域组合留在 app 层")
                .check(classes);
    }

    @Test
    @DisplayName("领域 Service 必须是接口，实现类叫 *ServiceImpl")
    void serviceMustBeInterface() {
        // 只约束业务域：common 里的横切服务（IdempotencyService 等）没有多实现的可能，
        // 强行拆接口只会多一层无意义的间接
        classes().that().resideInAnyPackage(domainPackages())
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
         *   .port —— Port 的实现。接口在 spi 包里（合并后与 common 同在 shop-base），
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

    // ───────────────────────────────────────────────────────────────────────
    // 以下四条为模块合并前补齐（模块优化实施步骤 S1），**现在是唯一的边界**。
    //
    // 合并之前，域之间不能互相依赖是 **Maven 强制**的：依赖不在 pom 里，编译期就过不去。
    // 2026-08 七个域合并进 shop-core 之后，**那道屏障已经消失** ——
    // 现在 core 内部任意两个域之间 import 一下就能编过，拦住它的只剩这几条规则。
    //
    // 当时坚持「先补规则、再动结构」，就是为了不留下一段边界无人看管的空窗期。
    // ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("领域层不得依赖 app 层（portal / config）")
    void domainsMustNotDependOnAppLayer() {
        noClasses().that().resideInAnyPackage(domainPackages())
                .should().dependOnClassesThat().resideInAnyPackage(
                        "ai.neargo.shop.portal..", "ai.neargo.shop.config..")
                .allowEmptyShould(true)
                .because("依赖方向必须单向朝下：app 装配领域，领域不认识 app。"
                        + "反向依赖会让领域代码搬不走——微服务拆分时它会把整个启动模块一起拖过去")
                .check(classes);
    }

    @Test
    @DisplayName("领域层不得出现 Web 运行时类型（HttpServletRequest 等）")
    void domainsMustNotTouchWebRuntime() {
        noClasses().that().resideInAnyPackage(domainPackages())
                .should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.servlet..", "org.springframework.web.context..",
                        "org.springframework.web.servlet..")
                .allowEmptyShould(true)
                .because("领域逻辑一旦读 request，就只能在 HTTP 线程里跑——"
                        + "定时任务（worker profile）和事件消费都调不动它。"
                        + "S7 把 Controller 搬进业务工程后，这条是防止 web 语义渗进领域的唯一屏障")
                .check(classes);
    }

    @Test
    @DisplayName("Port 接口只能定义在 spi 包")
    void portInterfacesOnlyInSpi() {
        classes().that().haveSimpleNameEndingWith("Port").and().areInterfaces()
                .should().resideInAPackage("ai.neargo.shop.spi..")
                .allowEmptyShould(true)
                .because("Port 是跨域契约。定义在某个域里，等于让调用方 import 那个域——"
                        + "本来要解耦，结果反而建立了依赖")
                .check(classes);
    }

    @Test
    @DisplayName("领域层不得直接依赖通道实现（只认 spi 包的网关接口）")
    void domainsMustNotTouchChannel() {
        noClasses().that().resideInAnyPackage(domainPackages())
                .should().dependOnClassesThat().resideInAPackage("ai.neargo.shop.channel..")
                .allowEmptyShould(true)
                .because("微信/支付宝的报文格式与「这笔钱怎么分」无关。领域层一旦 import 具体网关，"
                        + "换通道就要改业务代码，而 ops 部署（不含支付通道）会连编译都过不去")
                .check(classes);
    }

    /**
     * 顶层包白名单 —— 堵的是 {@link #DOMAINS} 名单本身的漏洞。
     *
     * <p>域间依赖规则是**按名单**两两检查的，这意味着：不在名单里的顶层包
     * **不受任何约束**。有人建一个 {@code ai.neargo.shop.coupon}，它可以随意
     * import trade 的实体、被 product 反向依赖，而所有规则**全绿**。
     *
     * <p>合并成 shop-core 之后这个漏洞会变得容易触发——新建一个包不再需要
     * 建 Maven 模块、不需要改根 pom，就是新建一个目录而已，没有任何一步会
     * 提醒作者「你在开一个新域」。这条规则就是那个提醒。
     */
    @Test
    @DisplayName("★★ 一期占位哈希只准出现在 PasswordHasher 里 —— 它不能再被用来存新密码")
    void legacyPasswordHashIsContained() throws Exception {
        /*
         * 那个哈希是 `Integer.toHexString(("shop$" + raw).hashCode())`：
         * 32 位、无盐、零计算成本 —— 基本等价于明文。
         * 它保留下来的唯一理由是**验证存量**，让老账号还能登录并就地升级。
         *
         * 守卫盯的是「别处又照着写一遍」：新功能最容易的做法就是抄旁边那行。
         * 测试自己要造存量数据，所以测试目录不算。
         */
        List<String> offenders = new ArrayList<>();
        Path root = Path.of("..").toAbsolutePath().normalize();
        try (var paths = Files.walk(root)) {
            for (Path f : paths.filter(x -> x.toString().endsWith(".java"))
                    .filter(x -> x.toString().contains("/src/main/")).toList()) {
                if (f.getFileName().toString().equals("PasswordHasher.java")) {
                    continue;
                }
                if (Files.readString(f).contains("\"shop$\"")) {
                    offenders.add(root.relativize(f).toString());
                }
            }
        }
        assertThat(offenders)
                .as("这些文件里出现了一期占位哈希的盐串。它只准留在 PasswordHasher 内部做存量验证 —— "
                        + "拿它存新密码等于把密码明文放进库")
                .isEmpty();
    }

    @Test
    @DisplayName("顶层包必须登记：新开一个域，要同时登记进 DOMAINS 名单")
    void topLevelPackagesMustBeRegistered() {
        // 非业务域的顶层包：横切基础设施与装配层，各有专门规则管，不进 DOMAINS
        List<String> infra = List.of("common", "spi", "auth", "event", "idem", "portal", "config", "arch",
                // channel：外部通道适配（支付/进件/登录凭证/推送）。不是业务域——
                // 它没有自己的表，只把外部协议翻译成 spi 的接口。见 domainsMustNotTouchChannel。
                "channel",
                // archive：运营端归档（软删除）。**同样不是业务域** ——
                // 它没有自己的表，只往别人的表上盖一个 archived_at，
                // 表名由调用方的枚举给。四个域的这段逻辑逐字相同，
                // 各写一遍必然漂移，而漂移的表现是「某个域归档了但列表还显示」。
                "archive");
        List<String> known = new ArrayList<>(infra);
        known.addAll(List.of(DOMAINS));

        List<String> unregistered = classes.stream()
                .map(c -> c.getPackageName())
                .filter(p -> p.startsWith("ai.neargo.shop."))
                .map(p -> p.substring("ai.neargo.shop.".length()).split("\\.")[0])
                .distinct()
                .filter(top -> !known.contains(top))
                .sorted()
                .toList();

        assertThat(unregistered)
                .as("这些顶层包没在 DOMAINS 或基础设施名单里，因此**不受域间依赖规则约束**。"
                        + "若是新业务域，加进 DOMAINS；若是基础设施，加进本测试的 infra 名单")
                .isEmpty();
    }
}
