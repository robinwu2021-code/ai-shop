package ai.neargo.shop.beauty.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 一个事务只写、只锁一个库（TDD-美业域迁移新库 B-5d，AC2 的事务版）：交易事务 {@code db.tx(...)} 里不能写或锁行业库的表，
 * 行业事务 {@code db.industryTx(...)} 里不能写或锁行业库以外的表；跨库只能读。分库后跨库的写不在这个事务里、锁也锁不住。
 *
 * <p>{@link #PENDING} 是还在等方案的已知例外（只许减少）：操作日志的去处与写法见 TDD §2.6 第 12 项。
 */
class TransactionHandleBoundaryTest {
    private static final Path SOURCES = Path.of("src/main/java/ai/neargo/shop/beauty");
    private static final Pattern MAPPER = Pattern.compile("public interface (\\w+)\\s+extends (\\w+)<");
    private static final Pattern READ = Pattern.compile("select(?!.*ForUpdate)\\w*|count\\w*|exists\\w*|find\\w*");
    private static final Set<String> PENDING = Set.of(
            "ServiceSubmissionServiceImpl industryTx db.actions.insert");

    @Test
    void aTransactionWritesAndLocksOnlyItsOwnDatabase() throws IOException {
        Map<String, Boolean> industry = new HashMap<>();
        var mappers = MAPPER.matcher(Files.readString(SOURCES.resolve("mapper/BeautyMappers.java")));
        while (mappers.find()) industry.put(mappers.group(1), "IndustryTableMapper".equals(mappers.group(2)));
        var persistence = Files.readString(SOURCES.resolve("service/impl/AppointmentPersistence.java"));
        Map<String, Boolean> dbFields = fields(persistence, industry);

        Set<String> violations = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String name = file.getFileName().toString().replace(".java", "");
                Map<String, Boolean> own = fields(source, industry);
                var tx = Pattern.compile("db\\.(tx|industryTx)\\(").matcher(source);
                while (tx.find()) {
                    boolean industryTx = tx.group(1).equals("industryTx");
                    String body = block(source, tx.end() - 1);
                    var calls = Pattern.compile("\\b((?:db\\.)?\\w+)\\.(\\w+)\\(").matcher(body);
                    while (calls.find()) {
                        String ref = calls.group(1);
                        Boolean onIndustry = ref.startsWith("db.") ? dbFields.get(ref.substring(3)) : own.get(ref);
                        if (onIndustry == null || onIndustry == industryTx) continue;
                        if (READ.matcher(calls.group(2)).matches()) continue;
                        violations.add(name + " " + tx.group(1) + " " + ref + "." + calls.group(2));
                    }
                }
            }
        }
        // 恰好等于已知例外：多了是新写出的跨库写；少了说明例外已修好，要从 PENDING 里删掉（也证明扫描本身有效）
        assertThat(violations).as("跨库只能读：事务里写或锁了另一个库的表").isEqualTo(new TreeSet<>(PENDING));
    }

    /** 这个源文件里声明的 Mapper 字段（或参数）名 → 是否行业库。 */
    private static Map<String, Boolean> fields(String source, Map<String, Boolean> industry) {
        Map<String, Boolean> result = new HashMap<>();
        var declared = Pattern.compile("BeautyMappers\\.(\\w+)\\s+(\\w+)\\s*[;,)]").matcher(source);
        while (declared.find()) {
            Boolean onIndustry = industry.get(declared.group(1));
            if (onIndustry != null) result.put(declared.group(2), onIndustry);
        }
        return result;
    }

    private static String block(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return source.substring(open, i);
        }
        return source.substring(open);
    }
}
