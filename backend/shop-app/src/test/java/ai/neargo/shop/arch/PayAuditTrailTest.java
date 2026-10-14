package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>拿到了操作人身份的资金动作，必须留痕。</b>
 *
 * <h2>它防住的是什么</h2>
 * 漏写一行 {@code auditLogPort.record(...)} 不会报错、不影响返回值、
 * 界面上一切正常。代价要等到有人来问「这笔钱是谁登记付掉的」时才付 ——
 * 那时候只剩一行 {@code paid_at}，<b>不是查得慢，是查不到</b>。
 *
 * <p>而「谁做的」这件事，代码里有一个诚实的标志：
 * <b>方法特地去取了操作人</b>（{@code SecurityUtils.currentUserNo()}，
 * 或者把 {@code operatorNo} 当参数收进来）。
 * 一个方法不需要知道是谁在调，就不会去取它；取了，就说明这个动作是要记名的。
 * 判据用的就是这个 —— 不用维护一张「哪些方法算资金动作」的清单，
 * <b>那种清单漏登记一条同样不报错</b>，和它要防的问题是同一类。
 *
 * <h2>为什么扫 app service 层而不是 controller</h2>
 * 这些动作本来写在 controller 里，留痕跟着 HTTP 层走 ——
 * 于是它的存在与否取决于写 controller 的人记不记得。
 * 搬到 {@code payclient} 之后它们在同一层里，闸门才扫得到；
 * 散在 controller 里时，「漏了留痕」和「这个方法恰好不需要留痕」长得一模一样。
 */
class PayAuditTrailTest {

    private static final Path BACKEND = Paths.get(System.getProperty("user.dir")).getParent();
    private static final Path IMPL = BACKEND.resolve(
            "shop-app/src/main/java/ai/neargo/shop/payclient/impl");

    /** {@code public 返回类型 方法名(...) {} —— 用它切出每个方法的起点 */
    private static final Pattern METHOD = Pattern.compile(
            "(?m)^    public\\s+[\\w<>\\[\\], .?]+\\s+(\\w+)\\s*\\(");

    @Test
    @DisplayName("★★★ 拿到操作人的资金动作必须留痕 —— 漏一行不报错，事后就查不到是谁做的")
    void moneyActionsWithAnOperatorMustLeaveAnAuditTrail() throws IOException {
        assertThat(Files.isDirectory(IMPL))
                .as("payclient/impl 不在了 —— 目录搬了？否则这条闸门从此恒真")
                .isTrue();

        List<String> offenders = new ArrayList<>();
        int needsTrail = 0;
        int scannedMethods = 0;

        try (Stream<Path> files = Files.walk(IMPL)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                for (String[] m : methodsOf(text)) {
                    scannedMethods++;
                    String name = m[0];
                    String body = m[1];
                    boolean hasOperator = body.contains("currentUserNo()")
                            || body.contains("operatorNo") || body.contains("operator");
                    if (!hasOperator) {
                        continue;
                    }
                    needsTrail++;
                    if (!body.contains("auditLogPort.record")) {
                        offenders.add(f.getFileName() + "#" + name);
                    }
                }
            }
        }

        /*
         * **两个对照量。** 这条闸门是「找出违规」型的 —— 扫不到东西时违规集是空的，
         * 与「没有违规」长得一模一样。所以先断言尺子量到了东西：
         * 方法切得出来，且其中确实有需要留痕的。
         */
        assertThat(scannedMethods)
                .as("一个方法都没切出来 —— 方法签名的写法变了？那这条闸门量的是空气")
                .isPositive();
        assertThat(needsTrail)
                .as("没有任何方法被判为「需要留痕」—— 判据（拿没拿操作人）多半已经失效")
                .isPositive();

        assertThat(offenders)
                .as("这些方法取了操作人却没有留痕：\n  %s\n"
                        + "  取操作人说明这个动作是要记名的，而漏掉 auditLogPort.record\n"
                        + "  不会报错、不影响返回值、界面上一切正常 ——\n"
                        + "  代价是有人来问「这笔钱是谁登记付掉的」时答不上来。\n"
                        + "  真的不需要留痕，就别把操作人取进来。", offenders)
                .isEmpty();
    }

    /** 切出 {@code (方法名, 方法体)}。方法体 = 本方法起点到下一个 public 方法起点 */
    private static List<String[]> methodsOf(String text) {
        List<String[]> out = new ArrayList<>();
        Matcher m = METHOD.matcher(text);
        List<int[]> spans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[]{m.start(), m.end()});
            names.add(m.group(1));
        }
        for (int i = 0; i < spans.size(); i++) {
            int from = spans.get(i)[0];
            int to = i + 1 < spans.size() ? spans.get(i + 1)[0] : text.length();
            out.add(new String[]{names.get(i), text.substring(from, to)});
        }
        return out;
    }
}
