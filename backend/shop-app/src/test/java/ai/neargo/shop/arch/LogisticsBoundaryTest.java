package ai.neargo.shop.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 物流模块的边界（ADR-032 / TDD-物流模块 AC10）。
 *
 * <p>「物流不依赖别的业务域」由 {@link ArchitectureTest} 的域间规则管（{@code logistics} 已登记进 DOMAINS）。
 * 这里补它管不到的两条：
 * <ol>
 *   <li><b>不碰别的域的表</b>：ArchUnit 只看 import，看不见 SQL 字符串里的 {@code ord_sub_order}。
 *       物流里写一句跨域 SQL，编译得过、测试全绿，阶段 2 切库那天才炸。</li>
 *   <li><b>反向 Port 预算 = 1</b>：物流向交易域要数据的 Port（实现在物流之外的那种）只许一条 ——
 *       支付域拆分就卡在 11 条反向 Port 上。</li>
 * </ol>
 */
class LogisticsBoundaryTest {

    private static final Path BACKEND = Paths.get(System.getProperty("user.dir")).getParent();
    private static final Path LOGISTICS = BACKEND.resolve("logistics");

    /** 别的域的表前缀。物流自己的是 lgs_ */
    private static final Pattern FOREIGN_TABLE =
            Pattern.compile("\\b(ord|trd|pmt|usr|mch|prd|stl|inv|pts|mbr|cmt)_[a-z_]+\\b");

    /** 反向 Port 至多几条 —— 改这个数之前先读类注释 */
    private static final int REVERSE_PORT_BUDGET = 1;

    private static JavaClasses classes;

    @BeforeAll
    static void load() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ai.neargo.shop");
    }

    @Test
    @DisplayName("★★★ 物流源码里不出现别的域的表名 —— 跨域 SQL 编译得过、测试全绿，切库那天才炸")
    void noForeignTablesInLogisticsSources() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(LOGISTICS)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/")).toList()) {
                String src = stripComments(Files.readString(f, StandardCharsets.UTF_8));
                Matcher m = FOREIGN_TABLE.matcher(src);
                while (m.find()) {
                    hits.add(LOGISTICS.relativize(f) + " → " + m.group());
                }
            }
        }
        assertThat(hits)
                .as("物流模块直接引用了别的域的表。要的数据经 shop-base/spi 的 Port 拿，"
                        + "或在登记那一刻经 ShipmentSourcePort 取快照（ADR-032）：\n  %s", String.join("\n  ", hits))
                .isEmpty();
    }

    @Test
    @DisplayName("★★★ 扫描面不为空 —— 路径写错时上一条会因为什么都没扫到而恒绿")
    void scanSurfaceIsNotEmpty() throws IOException {
        try (Stream<Path> files = Files.walk(LOGISTICS)) {
            assertThat(files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/")).count())
                    .as("在 %s 下一个物流源文件都没扫到", LOGISTICS)
                    .isGreaterThan(5);
        }
    }

    @Test
    @DisplayName("★★★ 反向 Port 至多 1 条：shop-base/spi/logistics 里实现在物流模块之外的 Port")
    void reversePortBudget() {
        List<String> reverse = new ArrayList<>();
        for (JavaClass port : classes) {
            if (!port.isInterface() || !port.getPackageName().equals("ai.neargo.shop.spi.logistics")
                    || !port.getSimpleName().endsWith("Port")) {
                continue;
            }
            // 用 isAssignableTo 找实现类：getAllSubclasses 对接口不返回实现类，这条守卫因此假绿过
            // （2026-10-09 消融：预算调成 0 照样绿）
            List<JavaClass> impls = classes.stream()
                    .filter(c -> !c.isInterface() && !c.equals(port) && c.isAssignableTo(port.getName()))
                    .toList();
            boolean implementedOutside = impls.stream().anyMatch(impl -> !inLogistics(impl));
            boolean implementedInside = impls.stream().anyMatch(LogisticsBoundaryTest::inLogistics);
            if (implementedOutside && !implementedInside) {
                reverse.add(port.getSimpleName());
            }
        }
        assertThat(reverse)
                .as("物流向别的域要数据的 Port 超了预算（%d）：%s —— 每多一条，拆服务那天就多一个远程依赖",
                        REVERSE_PORT_BUDGET, reverse)
                .hasSizeLessThanOrEqualTo(REVERSE_PORT_BUDGET);
        assertThat(reverse).as("扫描面：ShipmentSourcePort 实现在 logisticsbridge，必须被认成反向 Port —— "
                + "认不出说明找实现类的办法失效了，上面那条会恒绿").contains("ShipmentSourcePort");
    }

    /**
     * 是不是物流模块里的类。<b>前缀必须带点</b>：{@code ai.neargo.shop.logisticsbridge} 也以
     * {@code ai.neargo.shop.logistics} 开头 —— 不带点时桥接层被当成物流内部，反向 Port 一条都认不出（2026-10-09）。
     */
    private static boolean inLogistics(JavaClass c) {
        String p = c.getPackageName();
        return p.equals("ai.neargo.shop.logistics") || p.startsWith("ai.neargo.shop.logistics.");
    }

    /** 去掉注释：注释里写「不读 ord_sub_order」不该算违规 */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }
}
