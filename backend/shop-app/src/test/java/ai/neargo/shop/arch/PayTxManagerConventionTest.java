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
 * 支付域的每一个 {@code @Transactional} 都必须点名 {@code payTxManager}。
 *
 * <h2>为什么漏一个就等于没隔离</h2>
 * {@code PayDataSourceConfig} 给支付域配了独立的数据源与事务管理器，
 * 目的是让「下单事务里顺手写结算单」<b>在物理上写不出来</b>。
 * 但 Spring 的 {@code @Transactional} 不点名时用的是<b>默认那一个</b> ——
 * 也就是平台的。于是漏点名的那个方法照旧跑在业务事务里，隔离对它不生效。
 *
 * <p><b>而这件事今天不会有任何症状</b>：库是同一个，查询照常能跑，
 * 结果也一模一样。它要等到拆库那天才炸，而那时症状是
 * 「某几个接口报连不上表」，离真因隔着一次装配改动和几个月。
 *
 * <p>{@code FundInvariantFlowTest} 里那条负面对照（业务回滚而结算单留下）
 * 证明的是<b>装配对了</b>，证明不了<b>每个方法都用上了</b> ——
 * 它是直接用 mapper 插的，不经过任何 Service 方法。两条缺一不可。
 *
 * <h2>为什么是源码扫描而不是运行时断言</h2>
 * 运行时要断言「这个方法用的是哪个事务管理器」得把每个方法都调一遍，
 * 而其中不少要造完整的资金链路数据。源码扫描零成本、覆盖 100%，
 * 且失败信息直接指到行 —— 对「不许漏」这类规则，它比运行时断言合适。
 */
class PayTxManagerConventionTest {

    private static final Path BACKEND = Paths.get(System.getProperty("user.dir")).getParent();
    private static final Path SETTLE = BACKEND.resolve("pay/pay-domain/src/main/java");

    /** 支付域自己的事务管理器 bean 名。改名的话这里和装配要一起改 */
    private static final String TX = "payTxManager";

    @Test
    @DisplayName("★★★ 支付域的 @Transactional 必须点名 payTxManager —— 漏一个就等于没隔离")
    void everySettleTransactionNamesItsManager() throws IOException {
        List<String> bare = new ArrayList<>();
        int total = 0;

        try (var files = Files.walk(SETTLE)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                String[] lines = src.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String t = lines[i].trim();
                    if (!t.startsWith("@Transactional")) {
                        continue;   // 注释里提到它是正常的
                    }
                    total++;
                    if (!t.contains(TX)) {
                        bare.add(SETTLE.relativize(f) + ":" + (i + 1) + "  " + t);
                    }
                }
            }
        }

        /*
         * **对照量。** 一个 @Transactional 都没扫到时，下面那句「没有漏网的」
         * 毫无意义 —— 那通常意味着目录改名或注解被换掉，而不是「全都点名了」。
         * 这一检存在的前提就是支付域里确实有事务。
         */
        assertThat(total)
                .as("支付域里一个 @Transactional 都没扫到 —— 目录改名了？那这一检已经失效")
                .isPositive();

        assertThat(bare)
                .as("这些 @Transactional 没点名 %s，它们仍然跑在**平台的**事务管理器上：\n  %s\n"
                        + "  今天不会有任何症状（库是同一个，结果一模一样），\n"
                        + "  要等拆库那天才炸，而那时症状是「某几个接口报连不上表」，\n"
                        + "  离真因隔着一次装配改动和几个月。\n"
                        + "  → 写成 @Transactional(\"%s\")。",
                        TX, bare, TX)
                .isEmpty();
    }
}
