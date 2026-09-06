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
            if (!src.contains("case NotifyScene." + f.getName())) {
                missing.add(f.getName() + "（" + value + "）");
            }
        }

        assertThat(missing)
                .as("这些场景在 NotifyScene.ALL 里，但 NotificationConsumer 没有对应的 case —— "
                        + "supports() 会放行，事件进来之后落进 default，什么都不做且不报错：\n  %s",
                        String.join("\n  ", missing))
                .isEmpty();
    }
}
