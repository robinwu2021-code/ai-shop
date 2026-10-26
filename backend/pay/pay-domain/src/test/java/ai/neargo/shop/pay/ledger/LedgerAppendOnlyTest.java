package ai.neargo.shop.pay.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 流水只增不改（TDD-支付服务-I3支付单退款流水 AC5）：凭证与分录的 mapper 继承了 {@code BaseMapper} 的 update / delete，
 * 只能靠扫描拦。更正只能追加冲正凭证。
 */
class LedgerAppendOnlyTest {

    private static final Path SRC = Path.of("src", "main", "java");
    /** 声明为流水 mapper 的字段 / 参数名 */
    private static final Pattern DECLARED = Pattern.compile("\\b(?:LedgerVoucherMapper|LedgerEntryMapper)\\s+(\\w+)");

    static List<String> offenders(String code) {
        List<String> names = new ArrayList<>();
        Matcher d = DECLARED.matcher(code);
        while (d.find()) {
            names.add(d.group(1));
        }
        List<String> hits = new ArrayList<>();
        for (String n : names) {
            Matcher m = Pattern.compile("\\b" + n + "\\s*\\.\\s*(update\\w*|delete\\w*)\\s*\\(").matcher(code);
            while (m.find()) {
                hits.add(n + "." + m.group(1));
            }
        }
        return hits;
    }

    @Test
    @DisplayName("★★★ 支付域里没有对流水 mapper 调 update / delete")
    void noUpdateOrDeleteOnLedger() throws IOException {
        List<String> found = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                String code = Files.readString(f, StandardCharsets.UTF_8)
                        .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
                offenders(code).forEach(h -> found.add(SRC.relativize(f) + ": " + h));
            }
        }
        assertThat(scanned).isGreaterThan(100);
        assertThat(found).as("流水只增不改，更正追加冲正凭证（LedgerService#reverse）：%s", found).isEmpty();
    }

    @Test
    @DisplayName("★ 判据自身能量出东西")
    void detectorFires() {
        assertThat(offenders("private final LedgerEntryMapper entries; void x(){ entries.updateById(e); }"))
                .containsExactly("entries.updateById");
        assertThat(offenders("LedgerVoucherMapper v; v.delete(w);")).containsExactly("v.delete");
        assertThat(offenders("LedgerVoucherMapper v; v.insert(row); v.selectOne(w);")).isEmpty();
    }
}
