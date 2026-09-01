package ai.neargo.shop.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>支付域里没有 controller。</b>
 *
 * <h2>这条规矩换来了什么</h2>
 * 支付域不对任何前端暴露 HTTP —— 不给 C 端、不给 B 端，运营端也不给。
 * 三端 controller 全部在主应用，鉴权、判权、数据域也在主应用做完，
 * 传给支付域的是<b>已经收窄过的查询条件</b>。
 *
 * <p>它一次解决四件事（[TDD-支付域 · 双形态部署与装配 §L1]）：
 * <ol>
 *   <li>支付域不鉴权 → 不依赖 {@code shop-base-auth} → <b>不带 MyBatis → AOT 成立</b>。
 *       早先让它自带鉴权链时，依赖树里实测有 11 处 mybatis，
 *       而 ADR-021 §3.5 说「一旦用 MyBatis-Plus，这个模块就永远进不了 AOT」；</li>
 *   <li>不读会话 → 「token-store 切 db」不再是拆分的前置条件；</li>
 *   <li>判权只在主应用 → 不需要跨库读 {@code sys_role_point}，也不需要远程回查；</li>
 *   <li>只有一个进程有 controller → nginx 不必按路径分流，
 *       也就没有「少配一条就静默走到连不上支付库的那份」。</li>
 * </ol>
 *
 * <h2>为什么要一道闸门而不是靠约定</h2>
 * 在支付域里加一个 {@code @RestController} <b>今天不会有任何症状</b> ——
 * 单体里它照常工作，端点照常可达。代价要等到独立成进程那天才付：
 * 那个 controller 要么跟着进支付服务（于是把整条鉴权链拖进去，
 * 上面四条一起失效），要么留在主应用（于是它调不到自己的 service）。
 *
 * <p>而那时它已经不是「加一个 controller」，是「把一条设计原则悄悄改掉了」。
 */
class PayHasNoControllerTest {

    private static final Path BACKEND = Paths.get(System.getProperty("user.dir")).getParent();

    /**
     * 支付域今天的位置。
     *
     * <p><b>2026-08-31 · C2b 改名之后跟着改了这一行。</b>
     * 上一版写着「改名时这一行要跟着改 —— 不改的话它会变成一个恒真的断言
     * （扫一个不存在的目录），而恒真的闸门比没有闸门更糟」，
     * 并配了下面那条「目录必须存在」的对照量。
     *
     * <p><b>那条对照量当场生效了</b>：改名后第一次跑，它报的就是
     * 「shop-settle 的源码目录不在了 —— 支付域改名或搬家了？」
     * 而不是安静地扫一个空目录然后通过。
     */
    private static final String PAY_MODULE = "pay/pay-domain";

    /** 支付域对外唯一允许的 HTTP 表面。回调也在其中（2026-08-31 定：回调直接进 pay） */
    private static final List<String> ALLOWED = List.of("/internal", "/callback");

    @Test
    @DisplayName("★★★ 支付域里不许有 @RestController —— 加一个今天不会有任何症状，拆分那天才炸")
    void payDomainHasNoController() throws IOException {
        Path src = BACKEND.resolve(PAY_MODULE + "/src/main/java");

        /*
         * **对照量。** 目录不存在时下面的扫描是空集，而空集会让断言恒真 ——
         * 那正是模块改名或搬家之后这条闸门会悄悄失效的方式。
         */
        assertThat(Files.isDirectory(src))
                .as("%s 的源码目录不在了 —— 支付域改名或搬家了？"
                        + "把 PAY_MODULE 跟着改，否则这条闸门从此恒真", PAY_MODULE)
                .isTrue();

        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                String text = Files.readString(f, StandardCharsets.UTF_8);
                if (!text.contains("@RestController") && !text.contains("@Controller")) {
                    continue;
                }
                /*
                 * `/internal` 与 `/callback` 是允许的：前者是域间内部口
                 * （共享密钥、不认用户身份），后者是通道回调（验签，不是用户请求）。
                 * 两者都不是「给前端的 controller」。
                 */
                if (ALLOWED.stream().anyMatch(text::contains)) {
                    continue;
                }
                offenders.add(src.relativize(f).toString());
            }
        }

        assertThat(scanned).as("一个 java 文件都没扫到 —— 路径写错了？").isPositive();

        assertThat(offenders)
                .as("支付域里出现了 controller：\n  %s\n"
                        + "  三端 controller 要放在主应用（shop-app/portal/{ops,biz,mp}/pay/），\n"
                        + "  由主应用做完鉴权、判权、数据域解析，再把**收窄后的条件**传给支付域。\n"
                        + "  支付域对外只允许 %s。\n"
                        + "  → 见 TDD-支付域-双形态部署与装配 §L1。",
                        offenders, ALLOWED)
                .isEmpty();
    }

    /**
     * 支付域<b>接口层</b>（service 接口与 dto）不许出现持久化类型。
     *
     * <p>与上一条是同一件事的另一半：controller 决定「谁能调进来」，
     * 接口签名决定「调进来要带上什么」。签名里出现一个带 {@code @TableName}
     * 的 entity，主应用就必须为了调这个方法而依赖 MyBatis ——
     * 独立形态下那份客户端也一样，于是 AOT 又不成立了。
     *
     * <p><b>2026-08-31 抓到一处</b>：{@code FeeRuleService.rules()} 返回
     * {@code List<StlFeeRule>}，而这个 entity 一路发到了运营端。
     * 顺带暴露的是第二件事：它继承 {@code BaseEntity}，于是
     * {@code tenantNo}、{@code deleted}、{@code version} 一直随响应发出去 ——
     * 前端从没声明过它们，也没人知道它们在那儿。
     * 改成 {@code FeeRuleVO} 之后前端要的 9 个字段一个不少，多出来的三个没了。
     */
    @Test
    @DisplayName("★★ 支付域的服务接口与 DTO 里不许出现 entity / MyBatis —— 泄漏一处，调用方就被迫带上持久化框架")
    void payApiSurfaceHasNoPersistenceTypes() throws IOException {
        Path api = BACKEND.resolve(PAY_MODULE + "/src/main/java/ai/neargo/shop/pay");
        assertThat(Files.isDirectory(api))
                .as("%s 的接口层目录不在了 —— 支付域搬家了？否则这条闸门从此恒真", PAY_MODULE)
                .isTrue();

        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(api)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = api.relativize(f).toString();
                /*
                 * 只看接口层：service/ 下的接口本身（impl 是子包，不算）与 dto/。
                 * impl、entity、mapper 当然会用 MyBatis —— 它们是持久层，
                 * 拆 pay-store 时才轮到它们。
                 */
                boolean isApi = (rel.startsWith("service/") && !rel.startsWith("service/impl/")
                        && !rel.startsWith("service/recon/"))
                        || rel.startsWith("dto/");
                if (!isApi) {
                    continue;
                }
                scanned++;
                String text = Files.readString(f, StandardCharsets.UTF_8);
                if (text.contains("ai.neargo.shop.pay.entity.") || text.contains("com.baomidou.")) {
                    offenders.add(rel);
                }
            }
        }

        assertThat(scanned)
                .as("接口层一个文件都没扫到 —— 目录结构变了？少扫在这条闸门上"
                        + "表现为「没有违规」，与全绿一模一样")
                .isPositive();

        /*
         * **正对照：尺子本身要能量出东西。** 同一把判据在 impl/ 下必须命中 ——
         * 不然「接口层零泄漏」也可能只是因为这个判据什么都匹配不到
         * （比如哪天 import 写法变了、或换成了全限定名）。
         */
        Path impl = api.resolve("impl");
        int implHits = 0;
        try (Stream<Path> files = Files.walk(impl)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                if (text.contains("ai.neargo.shop.pay.entity.") || text.contains("com.baomidou.")) {
                    implHits++;
                }
            }
        }
        assertThat(implHits)
                .as("判据在 impl/ 下一处都没命中 —— 那「接口层干净」多半是判据失效，不是真干净")
                .isPositive();

        assertThat(offenders)
                .as("这些支付域接口/DTO 里出现了 entity 或 MyBatis 类型：\n  %s\n"
                        + "  后果不是编译错，是**调用方被迫依赖 MyBatis** ——\n"
                        + "  主应用今天本来就有，所以今天没有任何症状；\n"
                        + "  独立形态下那份客户端也要带上，AOT 随之失效。\n"
                        + "  改法：加一个 dto 下的 record，impl 里做映射（照 FeeRuleVO）。\n"
                        + "  → 见 TDD-支付域-双形态部署与装配 §L1、ADR-021 §3.5。", offenders)
                .isEmpty();
    }

    /**
     * 支付域里不许出现形态开关。
     *
     * <p>只要有一处业务代码知道「我现在是内嵌还是独立」，那就不是两种形态，
     * <b>而是两套代码 —— 而两套代码里一定有一套没人测</b>。
     * 形态差异只允许出现在两个地方：Maven 打包（产物里有什么）
     * 与主应用侧的一个开关（注入 Local 还是 Remote）。
     *
     * <p>这条闸门今天扫不到任何东西 —— 开关本身还没落地（C4 才引入）。
     * 那正是立它的时机：<b>等某个人第一次在支付域里写下这行代码时，
     * 他会当场知道这条路是封死的</b>，而不是等到切形态那天才发现。
     * 对照量放在下面：文件数必须为正，否则「没找到违规」就只是「没在找」。
     *
     * <p>配置键叫 {@code shop.pay.deployment} 而不是 {@code shop.pay.mode}
     * （2026-09-01 定名）：代码里已经有 68 处 {@code payMode}，
     * 那是订单的支付方式（ONLINE / OFFLINE），跟部署形态毫无关系。
     * 两者同名的话，将来 grep 这个开关会命中一大片无关代码 ——
     * 而这种噪声最终的效果是没人再去 grep 它。
     */
    @Test
    @DisplayName("★★ 支付域里不许读形态开关 —— 业务代码一旦知道自己是哪种形态，那就是两套代码")
    void payDomainDoesNotKnowItsOwnDeploymentShape() throws IOException {
        Path src = BACKEND.resolve(PAY_MODULE + "/src/main/java");
        assertThat(Files.isDirectory(src))
                .as("%s 的源码目录不在了 —— 支付域搬家了？否则这条闸门从此恒真", PAY_MODULE)
                .isTrue();

        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                String text = Files.readString(f, StandardCharsets.UTF_8);
                if (text.contains("shop.pay.deployment") || text.contains("PayDeployment")) {
                    offenders.add(src.relativize(f).toString());
                }
            }
        }

        assertThat(scanned).as("一个 java 文件都没扫到 —— 路径写错了？").isPositive();

        assertThat(offenders)
                .as("支付域里读到了形态开关：\n  %s\n"
                        + "  支付域必须<b>不知道</b>自己跑在哪种形态里。\n"
                        + "  需要按形态分叉的东西，分叉点在主应用侧（注入 Local 还是 Remote）\n"
                        + "  或者在 Maven 打包（产物里放不放这个模块），不在业务代码里。\n"
                        + "  → 见 TDD-支付域-双形态部署与装配。", offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 支付相关的 controller 确实在主应用侧 —— 否则上一条会变成恒真")
    void payControllersLiveInTheMainApp() throws IOException {
        Path portal = BACKEND.resolve("shop-app/src/main/java/ai/neargo/shop/portal");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(portal)) {
            for (Path f : files.filter(p -> p.toString().endsWith("Controller.java")).toList()) {
                if (f.toString().contains("/pay/")) {
                    found.add(f.getFileName().toString());
                }
            }
        }
        /*
         * **这一条是上一条的对照量。** 只有「支付域里没有 controller」的话，
         * 把它们全删掉也能通过 —— 而那显然不是我们想要的。
         * 两条合起来才是「它们搬到了该在的地方」。
         */
        assertThat(found)
                .as("主应用侧一个支付 controller 都没有 —— 它们被删了，还是搬去了别处？")
                .isNotEmpty();
    }
}
