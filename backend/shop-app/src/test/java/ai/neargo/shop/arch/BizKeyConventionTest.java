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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务码只许走一个出口 {@link ai.neargo.shop.common.BizKey}（ADR-033）—— <b>禁止「自己攒一个号」</b>。
 *
 * <h2>为什么要一道源码级的闸门</h2>
 * 业务码的那几条性质（多实例不碰撞、不可枚举、带日期便于运营）全写在 {@code BizKey.next} 里。
 * 可一旦有人在别处写 {@code setXxxNo("E" + System.currentTimeMillis())}，这几条当场作废 ——
 * 而它<b>在单机测试里看不出任何问题</b>：同一个毫秒撞号要多实例、同时并发才触发，
 * 单量泄露要有人去数才看得见。等上线那天付代价。
 *
 * <p>历史：{@code OpsServiceImpl} 建运营员工时，{@code staffNo} 长期是 {@code "E" + 毫秒取模} ——
 * 全仓唯一一处漏网。2026-10-09 随订单号优化一并收口（见 ADR-033）。这道闸门守的就是「别再长出第二处」。
 *
 * <h2>判据：字面量前缀 + 同语句里的「易变源」</h2>
 * 只有<b>既</b>给 {@code *No} 字段拼了个大写字面量前缀、<b>又</b>在同一条语句里混入
 * 时间戳 / UUID / 自增计数的，才算「自己攒号」。只拼前缀不算 ——
 * {@code setValueNo("SV_" + dim.getCode() + "_M" + BizKey.next(...))} 这类是<b>用别的码组合</b>，
 * 正是对的写法；{@code DevSeeder} 里 {@code "ST-" + merchantNo} 是确定性种子，也不在此列。
 */
class BizKeyConventionTest {

    private static final Path BACKEND = Paths.get(System.getProperty("user.dir")).getParent();

    /** 给 {@code *No} 字段拼字面量前缀：{@code .setXxxNo("ABC" +}。大写字母打头，收口在那个 {@code +} */
    private static final Pattern LITERAL_PREFIX_NO_SETTER =
            Pattern.compile("\\.set\\w*No\\(\\s*\"[A-Z][A-Z0-9_]*\"\\s*\\+");

    /** 「易变源」：进了业务码就意味着「自己攒号」，而不是从别的稳定码组合 */
    private static final Pattern VOLATILE_SOURCE = Pattern.compile(
            "currentTimeMillis|nanoTime|randomUUID|incrementAndGet|getAndIncrement|\\bnew Random\\b");

    /** 去注释：注释里举这些反例是正常的（本文件就举了），不剥会把说明文字当成违反 */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    /** 命中点所在的那一条语句：上一个 {@code ; { }} 之后，到下一个 {@code ;} 为止（拼接常跨行） */
    private static String statementAt(String src, int at) {
        int begin = 0;
        for (char c : new char[]{';', '{', '}'}) {
            begin = Math.max(begin, src.lastIndexOf(c, at) + 1);
        }
        int end = src.indexOf(';', at);
        return src.substring(begin, end < 0 ? src.length() : end);
    }

    private static boolean looksLikeRollYourOwn(String statement) {
        return LITERAL_PREFIX_NO_SETTER.matcher(statement).find()
                && VOLATILE_SOURCE.matcher(statement).find();
    }

    @Test
    @DisplayName("★★★ 判据本身要能报警 —— 否则它会悄悄变成一条恒绿的断言")
    void theDetectorActuallyFires() {
        // 对照量：正则腐烂（改错、字段改名）时这一检先红，而不是让线上的扫描静默放行
        assertThat(looksLikeRollYourOwn("staff.setStaffNo(\"E\" + System.currentTimeMillis() % 100000000L)"))
                .as("合成的「自己攒号」样本没被判出 —— 判据坏了").isTrue();
        assertThat(looksLikeRollYourOwn("row.setValueNo(\"SV_\" + dim.getCode() + \"_M\" + BizKey.next(x))"))
                .as("用别的码组合被误判成违反 —— 判据太宽").isFalse();
        assertThat(looksLikeRollYourOwn("area.setStoreNo(\"ST-\" + merchantNo)"))
                .as("确定性种子被误判成违反 —— 判据太宽").isFalse();
    }

    @Test
    @DisplayName("★★★ 全仓不许有第二处「自己攒业务码」—— 业务码只走 BizKey.next（ADR-033）")
    void noInlineBusinessKeyGeneration() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path main : mainSourceRoots()) {
            try (Stream<Path> files = Files.walk(main)) {
                for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    if (f.getFileName().toString().equals("BizKey.java")) {
                        continue;
                    }
                    String src = stripComments(Files.readString(f, StandardCharsets.UTF_8));
                    Matcher m = LITERAL_PREFIX_NO_SETTER.matcher(src);
                    while (m.find()) {
                        String stmt = statementAt(src, m.start());
                        if (VOLATILE_SOURCE.matcher(stmt).find()) {
                            violations.add(BACKEND.relativize(f) + " → " + stmt.strip());
                        }
                    }
                }
            }
        }
        assertThat(violations)
                .as("这些地方在「自己攒业务码」（字面量前缀 + 时间戳/UUID/自增）：\n  %s\n"
                        + "  多实例同毫秒会撞号、单量会被枚举出来，而单机测试看不出。\n"
                        + "  → 改走 BizKey.next(BizKey.XXX)；新码型先在 BizKey 里加一个前缀常量（ADR-033）。",
                        violations)
                .isEmpty();
    }

    /** backend 下每个模块的 main 源码根。少一个模块也无妨 —— 走得到的就扫 */
    private static List<Path> mainSourceRoots() throws IOException {
        List<Path> roots = new ArrayList<>();
        try (Stream<Path> modules = Files.list(BACKEND)) {
            for (Path mod : (Iterable<Path>) modules.filter(Files::isDirectory)::iterator) {
                Path main = mod.resolve("src/main/java");
                if (Files.isDirectory(main)) {
                    roots.add(main);
                }
            }
        }
        return roots;
    }
}
