package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 网关层重构（TDD-网关层与统一权限-开发计划 M0）用的快照落盘与比对。
 *
 * <p><b>快照不入库，基线取自合并基点。</b>每次运行都把当前结果写到 {@code target/gateway-snapshot/}；
 * 只有传了 {@code -Dgateway.snapshot.baseline=<目录>} 才与那个目录里的同名文件逐行比对。
 * 做法是在重构分支的合并基点上跑一遍、把产物目录留下，再在重构分支上带着这个参数跑一遍。
 *
 * <p>不把快照提交进仓库：端点每天都在增减，入库的快照会让每个加端点的人都来改它，
 * 而它要回答的问题只有一个——<b>这一个重构分支</b>有没有改变对外行为。
 */
final class GatewaySnapshot {

    static final String BASELINE_PROPERTY = "gateway.snapshot.baseline";
    private static final Path OUTPUT = Path.of("target", "gateway-snapshot");

    private GatewaySnapshot() {
    }

    /** 落盘；给了基线目录就比对，没给就只落盘。 */
    static void writeAndCompare(String fileName, List<String> lines) {
        try {
            Files.createDirectories(OUTPUT);
            Files.write(OUTPUT.resolve(fileName), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String baselineDir = System.getProperty(BASELINE_PROPERTY);
        if (baselineDir == null || baselineDir.isBlank()) {
            return;
        }
        Path baseline = Path.of(baselineDir).resolve(fileName);
        assertThat(baseline).as("基线文件不存在：先在合并基点上跑一遍本测试，把 target/gateway-snapshot 留下").exists();
        List<String> expected;
        try {
            expected = Files.readAllLines(baseline, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(diff(expected, lines))
                .as("%s 与基线不一致（- 基线有而现在没有，+ 现在有而基线没有）。当前结果在 %s",
                        fileName, OUTPUT.resolve(fileName).toAbsolutePath())
                .isEmpty();
    }

    /** 按行集合比较后再比顺序：先说清多了少了哪几行，行都对得上时才报顺序。 */
    private static List<String> diff(List<String> expected, List<String> actual) {
        List<String> out = new ArrayList<>();
        List<String> missing = new ArrayList<>(expected);
        actual.forEach(missing::remove);
        List<String> added = new ArrayList<>(actual);
        expected.forEach(added::remove);
        missing.forEach(line -> out.add("- " + line));
        added.forEach(line -> out.add("+ " + line));
        if (out.isEmpty() && !expected.equals(actual)) {
            for (int i = 0; i < expected.size(); i++) {
                if (!expected.get(i).equals(actual.get(i))) {
                    out.add("顺序不同，自第 " + (i + 1) + " 行起：基线「" + expected.get(i) + "」，现在「" + actual.get(i) + "」");
                    break;
                }
            }
        }
        return out;
    }
}
