package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>每一个把订单推成「已支付」的地方，都要先在支付域记一笔账。</b>
 *
 * <h2>它是被一次真实的漏改逼出来的</h2>
 * 2026-09-01 补 {@code stl_payment} 的写入时，回调入口<b>有两个</b>：
 * {@code /pay/callback/{ch}}（多通道）与 {@code /pay/callback/stub}（今天在用的）。
 * 只改了前者，测试当场红在「一笔成功支付都没扫到」——
 * 而如果那条测试没有对照量，它会因为自己造数据而绿，
 * <b>然后线上跑的那个入口一行流水都不写</b>。
 *
 * <p>这类「同一件事要在 N 处同时做」的地方，漏一处的表现永远是静默的：
 * 订单状态照常推进，用户看不出区别，只有对账和资金追溯少了数据 ——
 * 而那两件事没人天天看。
 *
 * <h2>判据</h2>
 * 调了 {@code markPaid} 的文件，必须也调 {@code settlePayment}。
 * <b>不看顺序</b>（源码扫描判不了执行顺序），顺序由
 * {@code SettlePort.settlePayment} 的注释与那两处的注释约束。
 * 这里守的是「有没有做」，那是漏改时唯一会缺的东西。
 *
 * <p>巡检类不在此列：I8 的补偿本来就是「支付域已经有账、订单没跟上」，
 * 它要做的恰恰只有 markPaid 那一半。
 */
class PaymentLedgerCoverageTest {

    private static final Path BACKEND = Paths.get(System.getProperty("user.dir")).getParent();

    /** 扫这些模块的 main 源码 */
    private static final List<String> MODULES = List.of("shop-core", "shop-app");

    /**
     * 判据故意用 {@code orderService.markPaid(} 而不是光秃秃的 {@code .markPaid(}。
     *
     * <p><b>因为 markPaid 这个名字在这个仓库里有两个意思：</b>
     * 订单的「转已支付」，和应付账款的「登记已付给供应商」
     * （{@code settleService.markPaid(settleNo, paymentRef)}）——
     * 后者动的是平台付给供应商的钱，与用户收款毫无关系。
     *
     * <p>第一版判据没区分，闸门当场报了 4 处，其中 2 处是应付账款的同名方法。
     * <b>宽判据的代价不是漏报而是噪声</b>，而噪声最后的效果和漏报一样：没人再看它。
     */
    private static final String ORDER_MARK_PAID = "orderService.markPaid(";

    /**
     * 豁免：这些地方推进订单支付状态而不记通道流水，且理由成立。
     *
     * <p>每加一条都要写清楚为什么 —— <b>一条没有理由的豁免，
     * 下一个人只会照着再加一条</b>，而清单一长就没人逐条读了。
     */
    private static final List<String> WAIVED = List.of(
            // I8 的补偿：支付域已经有 SUCCESS 流水了，缺的正是订单那一半
            "OrderPaidReconciler.java",
            // 纯转发适配器（OrderRepairPort → OrderService），真正的入口是调 Port 的人
            "OrderRepairPortImpl.java",
            /*
             * 商家在 B 端确认**线下收款**（payChannel = OFFLINE）。
             * 钱是用户当面给商家的，没有经过平台、也没有通道回执 ——
             * 记一行 stl_payment 等于说「平台收到了这笔钱」，那不是事实。
             */
            "MerchantOrderServiceImpl.java");

    @Test
    @DisplayName("★★★ 每个把订单推成已支付的入口，都要先在支付域记一笔账 —— 漏一处是静默的")
    void everyMarkPaidCallerAlsoRecordsThePayment() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scannedFiles = 0;
        int callers = 0;

        for (String module : MODULES) {
            Path src = BACKEND.resolve(module + "/src/main/java");
            assertThat(Files.isDirectory(src))
                    .as("%s 的源码目录不在了 —— 模块搬家了？否则这条闸门从此恒真", module)
                    .isTrue();
            try (Stream<Path> files = Files.walk(src)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    scannedFiles++;
                    String name = f.getFileName().toString();
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    if (!text.contains(ORDER_MARK_PAID)) {
                        continue;
                    }
                    callers++;
                    if (WAIVED.contains(name)) {
                        continue;
                    }
                    if (!text.contains("settlePayment(")) {
                        offenders.add(module + "/" + name);
                    }
                }
            }
        }

        assertThat(scannedFiles)
                .as("一个 java 文件都没扫到 —— 路径写错了？少扫在这条闸门上"
                        + "表现为「没有违规」，与全绿一模一样")
                .isPositive();
        assertThat(callers)
                .as("没有任何地方调 orderService.markPaid —— 判据多半已经失效")
                .isPositive();

        assertThat(offenders)
                .as("这些地方把订单推成了已支付，却没有在支付域记账：\n  %s\n"
                        + "  后果不是报错，是**对账和资金追溯少了这笔数据** ——\n"
                        + "  订单状态照常推进，用户看不出区别，而收款对账轴查不到它。\n"
                        + "  改法：先调 settlePort.settlePayment(...)，再调 markPaid。\n"
                        + "  顺序不能反：权威在支付域，订单状态是下游投影。\n"
                        + "  确实不该记账的（比如线下收款，钱没走通道），登记进 WAIVED 并写明理由。",
                        offenders)
                .isEmpty();
    }
}
