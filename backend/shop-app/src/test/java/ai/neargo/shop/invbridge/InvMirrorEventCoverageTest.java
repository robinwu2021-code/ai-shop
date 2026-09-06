package ai.neargo.shop.invbridge;

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
 * {@link InvMirrorEvent#ALL} 里的每个事件，{@link InventoryMirrorConsumer} 都必须真的有一个分支。
 *
 * <h2>为什么共用常量还不够</h2>
 *
 * <p>把三处字面量收编成一组常量之后，<b>拼写</b>不一致没法再发生（编译期常量，
 * 打错一个字母就编译不过）。消不掉的是<b>遗漏</b>：往 {@code ALL} 里加一个成员、
 * 忘了加 {@code case}，而消费方的 {@code supports()} 是<b>按前缀开放认领</b>的 ——
 * 事件照样被认领，落进 {@code default}，只留一条 WARN。
 *
 * <p>后果不是「少发一条通知」那一档：平台侧已经扣了预留，进销存侧没有，
 * <b>两本账当场分叉</b>，而双写期的对账会显示干净 —— 干净的原因是漏的那笔根本没记。
 *
 * <p>判据用源码而不是反射：分支是 {@code switch} 的语法结构，运行时看不见。
 * 与 {@code NotifySceneCoverageTest} 同一手法，那条守的是通知场景码。
 */
class InvMirrorEventCoverageTest {

    private static final Path CONSUMER =
            Path.of("src/main/java/ai/neargo/shop/invbridge/InventoryMirrorConsumer.java");

    @Test
    @DisplayName("★★★ ALL 里每个镜像事件都要有一个 case —— 漏一个：预留扣了没跟记，两本账分叉且零报错")
    void everyMirrorEventHasABranch() throws IOException {
        assertThat(CONSUMER)
                .as("找不到消费方源码，路径变了？判据是源码，读不到就等于没查")
                .exists();
        String src = Files.readString(CONSUMER, StandardCharsets.UTF_8);

        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Field f : InvMirrorEvent.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) {
                continue;
            }
            String value;
            try {
                value = (String) f.get(null);
            } catch (IllegalAccessException e) {
                throw new AssertionError("读不到 " + f.getName(), e);
            }
            // PREFIX 不是事件，只有进了 ALL 的才要求有分支
            if (!InvMirrorEvent.ALL.contains(value)) {
                continue;
            }
            checked++;
            /*
             * 判据带词边界，不能用 contains：一个成员是另一个的前缀时
             * （假如同时有 RESTORE 与 RESTORE_PARTIAL），长的那条分支的文本里
             * 含着短的那条的判据串，短的缺分支也会判成有。
             */
            Pattern branch = Pattern.compile("case\\s+InvMirrorEvent\\." + Pattern.quote(f.getName()) + "\\b");
            if (!branch.matcher(src).find()) {
                missing.add(f.getName() + "（" + value + "）");
            }
        }

        assertThat(missing)
                .as("这些事件在 InvMirrorEvent.ALL 里，但 InventoryMirrorConsumer 没有对应的 case —— "
                        + "supports() 按前缀放行，事件进来之后落进 default，什么都不做且不报错：\n  %s",
                        String.join("\n  ", missing))
                .isEmpty();

        /*
         * **少扫等于全绿。** 这条判据自己也是「找出违规」型：反射认不出成员时
         * missing 为空、上面那句照样通过，而一个成员都没检查过。
         * 常量哪天被改写成 enum，getDeclaredFields() 就一个 String 字段都不返回。
         * 反向也钉住：ALL 里放了没有对应常量的裸串时 checked 会偏小。
         */
        assertThat(checked)
                .as("只检查了 %d 个成员，而 ALL 有 %d 个 —— 这个测试正在空转",
                        checked, InvMirrorEvent.ALL.size())
                .isEqualTo(InvMirrorEvent.ALL.size());
    }
}
