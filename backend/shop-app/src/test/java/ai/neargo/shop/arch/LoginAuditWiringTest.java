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
 * 登录审计的装配守卫。
 *
 * <p><b>盯的是一种很具体的回归</b>：加一个登录入口时，失败分支记了、成功分支忘了。
 * 那种代码看上去很正常 —— catch 块里明明白白写着审计 —— 而结果是
 * 「谁在什么时候登录了」永远查不到，只有失败记录。
 *
 * <p>2026-08-28 之前整个仓库就是这个状态：4 个调用点全都只有 {@code failed}，
 * 成功那一侧原本指望 {@code DbTokenStore} 在签发处自动落，
 * 而生产走的是 ehcache，那条路根本不存在。<b>三张登录日志表因此全是空的。</b>
 *
 * <p>为什么是扫源码而不是 ArchUnit：要判定的是「同一个类里两个方法调用同时存在」，
 * 而 ArchUnit 看的是字节码上的依赖关系，表达不了「缺了哪一半」。
 */
class LoginAuditWiringTest {

    private static final Path BACKEND = Path.of("..");

    @Test
    @DisplayName("★★★ 记了登录失败的地方，必须也记登录成功 —— 只记失败等于只有半部日志")
    void everyFailureAuditHasAMatchingSuccessAudit() throws IOException {
        List<String> onlyFailure = new ArrayList<>();
        for (Path f : javaSources()) {
            String text = Files.readString(f);
            if (!text.contains(".failed(") || !text.contains("LoginAuditor")) {
                continue;
            }
            if (text.contains("interface LoginAuditor") || text.contains("LoginAuditor NONE")) {
                continue;   // 接口自己与它的空实现
            }
            if (!text.contains(".succeeded(")) {
                onlyFailure.add(f.getFileName().toString());
            }
        }
        assertThat(onlyFailure)
                .as("""
                        这些地方只记了登录失败，没记成功：%s

                        成功那一侧不会有任何人替你落 —— DbTokenStore 的签发处已经不写 LOGIN 了
                        （它只在 db 形态下存在，而生产走 ehcache）。
                        在成功分支上调 auditor.succeeded(realm, userNo)。""".formatted(onlyFailure))
                .isEmpty();
    }

    @Test
    @DisplayName("★ 签发处不许再写 LOGIN —— 切 db 之后那会变成重复记录")
    void tokenStoreMustNotWriteLoginEvent() throws IOException {
        Path store = BACKEND.resolve(
                "shop-base/src/main/java/ai/neargo/shop/auth/store/DbTokenStore.java");
        String text = Files.readString(store);
        assertThat(text)
                .as("LOGIN 由 LoginAuditor 在业务层记；这里再记一次，切 db 之后每次登录会有两行")
                .doesNotContain("LoginEvent.LOGIN,");
    }

    private static List<Path> javaSources() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(BACKEND)) {
            s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .forEach(out::add);
        }
        return out;
    }
}
