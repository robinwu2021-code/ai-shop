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
     * <p>C2 还没改包名（`ai.neargo.shop.settle` → `ai.neargo.shop.pay`），
     * 所以这里先盯 `shop-settle`。**改名时这一行要跟着改** ——
     * 不改的话它会变成一个恒真的断言（扫一个不存在的目录），
     * 而恒真的闸门比没有闸门更糟：它让人以为有防线。
     * 下面那条「目录必须存在」的断言就是防这个的。
     */
    private static final String PAY_MODULE = "shop-settle";

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
