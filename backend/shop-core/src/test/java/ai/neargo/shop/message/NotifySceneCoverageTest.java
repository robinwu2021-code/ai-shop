package ai.neargo.shop.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NotifyScene#ALL} 里的每个场景，{@link NotificationConsumer} 都必须真的有一个分支。
 *
 * <p><b>为什么共用常量还不够。</b>把三处字面量收编成一组常量之后，
 * 拼写不一致没法再发生 —— 但**遗漏**可以：往 {@code ALL} 里加一个成员、
 * 忘了加 {@code case}，{@code supports()} 照样返回 true，事件进到消费者、
 * 落进 {@code default}，什么都不做。零报错。
 *
 * <p>而另外两条守卫都拦不住这一种：
 * {@code SceneChannelSeedTest} 的两向比的是「常量 ↔ 种子行」，
 * 加了成员又补了种子行的话，那两条**都是绿的**，只有分支是缺的。
 *
 * <p>判据用源码而不是反射：分支是 {@code switch} 的语法结构，运行时看不见。
 * 这也是本仓库既有的做法（`ArchitectureTest` 同样读 {@code src/main/java}）。
 */
class NotifySceneCoverageTest {

    private static final Path CONSUMER =
            Path.of("src/main/java/ai/neargo/shop/message/NotificationConsumer.java");

    @Test
    @DisplayName("★★ ALL 里的每个场景都要有一个 case —— 漏一个：事件静默落进 default，零报错")
    void everySceneHasABranch() throws IOException {
        assertThat(CONSUMER)
                .as("找不到消费者源码，路径变了？判据是源码，读不到就等于没查")
                .exists();
        String src = Files.readString(CONSUMER, StandardCharsets.UTF_8);

        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Field f : NotifyScene.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) {
                continue;
            }
            String value;
            try {
                value = (String) f.get(null);
            } catch (IllegalAccessException e) {
                throw new AssertionError("读不到 " + f.getName(), e);
            }
            if (!NotifyScene.ALL.contains(value)) {
                // 常量存在但不在 ALL 里 = 申报为「还没接」，不该要求有分支
                continue;
            }
            checked++;
            /*
             * 判据带词边界，不能用 contains：一个成员是另一个的前缀时
             * （比如同时有 ORDER 与 ORDER_PAID），`case NotifyScene.ORDER_PAID ->` 这一行
             * 里含着 `case NotifyScene.ORDER` 这个子串，短的那个缺分支也会被判成有。
             * 今天七个成员两两互不为前缀，所以这是潜伏项 —— 潜伏项也要修，
             * 因为它发作的那天没有任何迹象。
             */
            Pattern branch = Pattern.compile("case\\s+NotifyScene\\." + Pattern.quote(f.getName()) + "\\b");
            if (!branch.matcher(src).find()) {
                missing.add(f.getName() + "（" + value + "）");
            }
        }

        /*
         * 扫描面的下界。**没有这一条，这个测试可以在什么都没查的情况下全绿** ——
         * 上面的循环靠 `f.getType() == String.class` 认成员，
         * 而这组常量一旦被改写成 Java enum（本仓库有 13 个 enum，不是没人会这么想），
         * `getDeclaredFields()` 一个 String 字段都不会返回：missing 为空、断言通过，
         * 而 ALL 还在，另外两条守卫照旧绿。实测过：枚举化之后 String 字段数 = 0。
         *
         * 顺带还钉住反向的一种：ALL 里放了一个没有对应常量的裸字符串时，
         * checked 会小于 ALL.size()，同样红。
         *
         * 这就是本仓库反复付过代价的那个形状 —— 绿不等于查过，
         * 所以每个「找出违规」型的判据都要有一条「我确实看了这么多个」。
         */
        assertThat(checked)
                .as("只检查了 %d 个成员，而 NotifyScene.ALL 有 %d 个 —— "
                        + "判据没认出成员（常量改成 enum 了？），这个测试正在空转",
                        checked, NotifyScene.ALL.size())
                .isEqualTo(NotifyScene.ALL.size());

        assertThat(missing)
                .as("这些场景在 NotifyScene.ALL 里，但 NotificationConsumer 没有对应的 case —— "
                        + "supports() 会放行，事件进来之后落进 default，什么都不做且不报错：\n  %s",
                        String.join("\n  ", missing))
                .isEmpty();
    }
}
