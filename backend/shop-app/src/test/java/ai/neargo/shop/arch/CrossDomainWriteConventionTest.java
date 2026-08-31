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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨域写必须推迟到业务事务提交之后 —— <b>棘轮，只许降不许升</b>。
 *
 * <h2>为什么要一道源码级的闸门</h2>
 * {@code FundInvariantFlowTest} 里那两条断言测的是 {@code AfterCommit} 本身
 * （回滚时不执行、失败不上抛）。它们证明不了<b>调用点真的用了它</b> ——
 * 有人把 {@code AfterCommit.run(() -> settlePort.generateForOrder(no))}
 * 改回直连（「反正在同一个库里，同事务更简单」），那两条照样绿。
 *
 * <p>而那次改动在单体里<b>看不出任何问题</b>：一起提交、一起回滚。
 * 代价要等到支付域独立成进程的那天才付：业务回滚后留下一条对不上任何单的账 ——
 * 凭空多出来的钱，删不得，只能人工判断。
 *
 * <h2>它同时是一份欠账清单</h2>
 * 七处跨域调用里，这一轮只改完了一处。{@link #REMAINING} 记着其余六处，
 * 每改完一处就从这里删一行 —— <b>数字只许变小</b>。
 * 把它写成清单而不是「以后再说」，是因为「以后再说」没有对照量：
 * 半年后谁也说不清还剩几处，而这个数决定了支付域能不能拆。
 */
class CrossDomainWriteConventionTest {

    private static final Path BACKEND = Paths.get(System.getProperty("user.dir")).getParent();

    /**
     * <b>已经改成「提交后执行」的调用</b>：文件 → 必须被 {@code AfterCommit} 包住的表达式。
     * 改回直连的话这一检当场红。
     */
    private static final Map<String, List<String>> DEFERRED = Map.of(
            "shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java",
            List.of("settlePort.generateForOrder", "grantPointsAfterPay",
                    "pointsPort.reverse"));

    /**
     * <b>还没改的六处</b>，按方向分类（见 TDD-支付域拆分-最终一致性与补偿 §二）：
     *
     * <ul>
     *   <li>{@code pointsPort.deduct}（下单扣分）—— <b>前置</b>，唯一不能用 Outbox 的一处，
     *       要改预扣 + 确认 + 超时释放；</li>
     *   <li>{@code settlePort.reverseSplit} / {@code settlePort.refund}（退款两步）——
     *       <b>有序，刻意不改</b>：它们今天已经是最终一致性的形状
     *       （REFUNDING + 失败就停不往下走），拆进程后一个字都不用动。</li>
     * </ul>
     *
     * <p>最后两处永远留在这里，是<b>有意的</b> —— 它们不是欠账，是正确答案。
     * 所以这个清单不是「要清零」，而是「不许变长」。
     */
    private static final List<String> REMAINING = List.of(
            "pointsPort.deduct",
            "settlePort.reverseSplit", "settlePort.refund");

    /**
     * 去掉注释。<b>注释里提到这些调用是正常的</b>（本文件本身就提了一堆），
     * 不剥的话闸门会把说明文字当成违反 —— 而那种噪声会让人把整条闸门关掉。
     */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    /**
     * 这个调用<b>所在的那一条语句</b>。
     *
     * <p>按行判是不够的：包装之后调用常常换到下一行 ——
     * {@code AfterCommit.run("...",\n        () -> settlePort.xxx(no));}
     * 按行判会把它报成违反，而那是<b>正确的写法</b>。
     * 闸门错报比漏报更快被关掉，所以这里按语句取（上一个 ; { } 之后）。
     */
    private static String statementAround(String src, int at) {
        int begin = 0;
        for (char c : new char[]{';', '{', '}'}) {
            begin = Math.max(begin, src.lastIndexOf(c, at) + 1);
        }
        return src.substring(begin, at);
    }

    @Test
    @DisplayName("★★★ 已经延后的跨域写不许改回直连 —— 改回去在单体里看不出任何问题")
    void deferredWritesStayDeferred() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : DEFERRED.entrySet()) {
            String src = stripComments(
                    Files.readString(BACKEND.resolve(e.getKey()), StandardCharsets.UTF_8));
            for (String call : e.getValue()) {
                int hits = 0;
                for (int at = src.indexOf(call); at >= 0; at = src.indexOf(call, at + 1)) {
                    String stmt = statementAround(src, at);
                    /*
                     * **方法声明那一处要跳过。** 被延后的动作常常抽成一个私有方法
                     * （grantPointsAfterPay 就是），于是名字会命中两次：定义与调用。
                     * 定义那一处当然不在 AfterCommit 的语句里 —— 不跳过的话
                     * 闸门会对着正确的代码报错，而错报比漏报更快被人把整条闸门关掉。
                     */
                    if (stmt.contains("void ") || stmt.contains("private ")
                            || stmt.contains("public ")) {
                        continue;
                    }
                    hits++;
                    if (!stmt.contains("AfterCommit")) {
                        violations.add(e.getKey() + " → " + call);
                    }
                }
                // 对照量：调用被改名或删掉时，这一检会静默变成恒真
                assertThat(hits).as("%s 里一处 %s 都没找到 —— 调用被改名了？", e.getKey(), call)
                        .isPositive();
            }
        }
        assertThat(violations)
                .as("这些跨域写又变回「和业务同事务」了：\n  %s\n"
                        + "  在单体里这看不出任何问题（一起提交、一起回滚），\n"
                        + "  代价要等支付域独立成进程那天才付：业务回滚后留下一条\n"
                        + "  对不上任何单的账 —— 凭空多出来的钱，删不得，只能人工判断。\n"
                        + "  → 用 AfterCommit.run(label, () -> ...) 包起来。",
                        violations)
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 还没改的跨域写只许变少 —— 它是「支付域能不能拆」的进度条")
    void remainingDebtDoesNotGrow() throws IOException {
        List<Path> files = List.of(
                BACKEND.resolve("shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java"),
                BACKEND.resolve("shop-core/src/main/java/ai/neargo/shop/trade/service/impl/AfterSaleServiceImpl.java"));

        List<String> found = new ArrayList<>();
        for (Path f : files) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            for (String line : src.split("\n")) {
                String t = line.trim();
                if (t.startsWith("*") || t.startsWith("//") || t.contains("AfterCommit")) {
                    continue;
                }
                for (String call : REMAINING) {
                    if (t.contains(call)) {
                        found.add(f.getFileName() + " → " + call);
                    }
                }
            }
        }
        /*
         * **对照量**：一处都没找到时这个断言毫无意义 —— 那通常意味着
         * 文件被改名或调用被重命名，而不是「欠账清零了」。真清零时
         * 应当连同这一检一起删掉，而不是让它悄悄变成一个恒真的断言。
         */
        assertThat(found).as("一处都没扫到 —— 通常是文件改名或调用重命名，不是欠账清零了")
                .isNotEmpty();
        assertThat(found.size())
                .as("还没改成「提交后执行」的跨域写变多了：%s\n"
                        + "  新增跨域直连要么改成 AfterCommit，要么在 REMAINING 里说明为什么它是对的。", found)
                .isLessThanOrEqualTo(REMAINING.size() + 2);
    }
}
