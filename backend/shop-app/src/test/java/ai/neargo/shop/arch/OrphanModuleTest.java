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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不在构建里的 Java 源码目录 = <b>没人会发现它已经过时的代码</b>。
 *
 * <p>这条守卫来自仓库根目录那个 `shop-svc-fulfillment/`：6 个 Java 文件、
 * 没有 {@code pom.xml}、不在父 pom 的 {@code <modules>} 里，<b>从来没参与过构建</b>。
 * 它在那儿放了足够久，久到 {@code backend/shop-core/fulfillment/} 里的真身
 * 已经在 4 个文件上走到了前面，而那份副本一行都没动过。
 *
 * <p><b>危害不是占地方，是它看起来像代码。</b>
 * 搜索会命中它、IDE 会打开它、读的人会把它当成现状 ——
 * 而它既不会编译失败，也不会有任何测试变红。
 * 交接文档为它专门留了一节「一处需要你确认的遗留」，
 * 因为上一轮无法证明没人引用它。
 *
 * <p>顺带记下删它时的一个教训：那份副本在 {@code PickupOrderVO} 上有两行
 * <b>真身已经丢掉</b>的字段注释（为什么只给昵称、为什么只给手机后四位）。
 * 「是重复所以删掉」这个判断当时是错的 —— <b>先比对再删</b>。
 */
@DisplayName("孤儿模块")
class OrphanModuleTest {

    /** 仓库根（本测试的工作目录是 {@code backend/shop-app}） */
    private static final Path REPO = Paths.get("").toAbsolutePath().getParent().getParent();

    /** 不参与 Maven 构建、但**允许**存在 Java 文件的地方 */
    private static final List<String> ALLOWED = List.of(
            "node_modules", "target", ".git", "android-shell", "dist");

    @Test
    @DisplayName("★★ Maven 之外不得有 Java 源码目录 —— 不参与构建的代码不会有任何东西提醒你它已经过时")
    void noJavaOutsideTheMavenBuild() throws IOException {
        List<String> orphans = new ArrayList<>();
        for (Path top : Files.list(REPO).filter(Files::isDirectory).toList()) {
            String name = top.getFileName().toString();
            if (name.equals("backend") || name.startsWith(".") || ALLOWED.contains(name)) {
                continue;
            }
            try (var files = Files.walk(top)) {
                files.filter(f -> f.toString().endsWith(".java"))
                        .filter(f -> ALLOWED.stream().noneMatch(a -> f.toString().contains("/" + a + "/")))
                        .findFirst()
                        .ifPresent(f -> orphans.add(REPO.relativize(f).toString()));
            }
        }
        assertThat(orphans)
                .as("""
                        这些 Java 文件在 backend/ 之外，不参与 Maven 构建：%s

                          不参与构建的代码**不会编译失败、不会有测试变红**，
                          于是它会静静地跟真身分叉 —— 而搜索会命中它、读的人会把它当成现状。

                          要么把它挪进 backend/ 的某个模块（并登记进父 pom 的 <modules>），
                          要么删掉。**删之前先与真身逐个比对**：
                          上一个孤儿副本里有两行真身已经丢掉的注释。""", orphans)
                .isEmpty();
    }

    @Test
    @DisplayName("★★ backend/ 下每个有 Java 源码的目录都要在父 pom 的 <modules> 里")
    void everyBackendModuleIsInTheAggregator() throws IOException {
        String pom = Files.readString(REPO.resolve("backend/pom.xml"), StandardCharsets.UTF_8);
        List<String> unregistered = new ArrayList<>();
        for (Path dir : Files.list(REPO.resolve("backend")).filter(Files::isDirectory).toList()) {
            String name = dir.getFileName().toString();
            if (name.startsWith(".") || ALLOWED.contains(name)) {
                continue;
            }
            if (!Files.isDirectory(dir.resolve("src/main/java"))) {
                continue;
            }
            if (!pom.contains("<module>" + name + "</module>")) {
                unregistered.add(name);
            }
        }
        assertThat(unregistered)
                .as("这些目录有 Java 源码但不在 backend/pom.xml 的 <modules> 里：%s\n"
                        + "  同样是「看着像代码、实际不参与构建」—— 只是位置更隐蔽，\n"
                        + "  因为它就在 backend/ 下，和真模块并排。", unregistered)
                .isEmpty();
    }
}
