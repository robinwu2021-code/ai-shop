package ai.neargo.shop.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每个控制器的 {@code @Profile} 必须与它的路径前缀一致。
 *
 * <p><b>S8 部署隔离：一个 jar 三种起法</b> —— {@code /ops/**} 的控制器标 {@code ops}，
 * {@code /mp,/biz} 的标 {@code api}，两者互斥，一个实例只注册其中一种。
 *
 * <p><b>标错不会有任何编译期或测试期信号</b>：测试上下文两个 profile 都在，
 * 单测照常绿。症状只在真实实例上出现 —— 那几条端点**根本不注册**，
 * 请求返回 404，而调用方看到的是「这个接口不存在」，不是「配错了」。
 *
 * <p>这条守卫是 2026-08-21 补的：{@code OpsSpuStdController} 从 Biz 控制器抄了
 * {@code @Profile("api")}，后端全绿、契约齐全、运营端页面打开是空的 ——
 * 只有真的把运营端实例起起来点进去才看得见。
 */
class ControllerProfileTest {

    private static final Path SRC = Path.of("..");

    /**
     * <b>刻意不标 @Profile</b> 的控制器。每一条都要写清为什么 ——
     * 「忘了标」与「故意不标」在代码里长得一模一样，而前者是个 404。
     */
    private static final java.util.Set<String> NO_PROFILE_OK = java.util.Set.of(
            // C 端与运营端可能部署在不同 profile 上，风控埋点两边都要收（见类注释）
            "MpRiskController.java");

    @Test
    @DisplayName("★★ 控制器的 @Profile 必须与路径前缀一致 —— 标错的端点在真实实例上根本不注册")
    void profileMatchesPathPrefix() throws IOException {
        List<String> wrong = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith("Controller.java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .toList()) {
                String src = Files.readString(f);
                String name = f.getFileName().toString();
                boolean ops = src.contains("(\"/ops/");
                boolean api = src.contains("(\"/biz/") || src.contains("(\"/mp/");
                if (ops == api) {
                    // 两种都有或两种都没有：前者是真问题（一个类跨了两个部署单元），
                    // 后者多半是回调/健康检查这类无前缀端点，都不在本守卫的判据里
                    continue;
                }
                /*
                 * 判据是「**这个 profile 在不在注解里**」，不是「注解逐字等于什么」——
                 * `@Profile({"api","ops"})` 是合法的（上传接口两个实例都要有）。
                 */
                if (NO_PROFILE_OK.contains(name)) {
                    continue;
                }
                int at = src.indexOf("@Profile(");
                String want = ops ? "ops" : "api";
                String ann = at < 0 ? "" : src.substring(at, Math.min(src.length(), at + 60));
                if (!ann.contains("\"" + want + "\"")) {
                    wrong.add(name + " 里的端点是 " + (ops ? "/ops/**" : "/biz,/mp/**")
                            + "，但注解里没有 \"" + want + "\"（当前：" + (at < 0 ? "没有 @Profile" : ann.split("\n")[0]) + "）");
                }
            }
        }
        assertThat(wrong)
                .as("这些控制器的 @Profile 与路径前缀对不上。\n"
                        + "  S8 部署隔离：/ops/** 标 ops，/mp,/biz 标 api，一个实例只注册其中一种。\n"
                        + "  **标错不会有任何编译期或测试期信号** —— 测试上下文两个 profile 都在，\n"
                        + "  症状只在真实实例上出现：那几条端点根本不注册，请求 404。")
                .isEmpty();
    }
}
