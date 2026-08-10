package ai.neargo.shop.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 后端 i18n 三语一致性。
 *
 * <p>端上早有这条守卫（{@code packages/shared/tests/i18n-parity.test.ts}），
 * 漏的正是后端这一侧 —— 实测阿语请求收到的是<b>中文报错</b>：
 * 界面全套阿语、报错突然变中文，比英文兜底还糟，用户不知道那是不是出错了。
 *
 * <p>还守一条更隐蔽的：<b>同一个 key 不能出现两次</b>。
 * properties 后者覆盖前者，于是文件里躺着一条永远不会生效的文案 ——
 * 改错了那条，改的人以为改好了，而线上一个字都没变。
 * {@code err.settle.receiver_not_ready} 就这么在中英两份里各重复了一次。
 */
class BackendI18nParityTest {

    private static final String BASE = "i18n/messages.properties";
    private static final List<String> LOCALES = List.of("en", "ar");

    @Test
    @DisplayName("★ 三语键集一致 —— 缺一条，那个语言的用户就会看到别的语言")
    void allLocalesHaveTheSameKeys() throws IOException {
        Set<String> base = keysOf(BASE);
        assertThat(base).as("基准文件读不到，路径变了？").isNotEmpty();

        for (String locale : LOCALES) {
            String path = "i18n/messages_" + locale + ".properties";
            Set<String> keys = keysOf(path);

            Set<String> missing = new TreeSet<>(base);
            missing.removeAll(keys);
            assertThat(missing)
                    .as("%s 缺这些词条 —— 这些错误码会回落到中文，而界面是 %s 的", path, locale)
                    .isEmpty();

            Set<String> extra = new TreeSet<>(keys);
            extra.removeAll(base);
            assertThat(extra)
                    .as("%s 多出这些词条 —— 要么基准漏了，要么这几条是删剩的死文案", path)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("★★ 同一个 key 不能出现两次 —— 后者覆盖前者，改错那条不会有任何反应")
    void noDuplicateKeys() throws IOException {
        List<String> files = new ArrayList<>();
        files.add(BASE);
        for (String locale : LOCALES) {
            files.add("i18n/messages_" + locale + ".properties");
        }
        for (String path : files) {
            List<String> all = allKeysOf(path);
            Set<String> seen = new LinkedHashSet<>();
            Set<String> dup = new TreeSet<>();
            for (String k : all) {
                if (!seen.add(k)) {
                    dup.add(k);
                }
            }
            assertThat(dup)
                    .as("%s 里这些 key 重复了：生效的是最后一条，前面那条是死的", path)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("★ 词条不能是空值 —— 空串会渲染成一个什么都不说的提示框")
    void noEmptyValues() throws IOException {
        List<String> files = new ArrayList<>();
        files.add(BASE);
        for (String locale : LOCALES) {
            files.add("i18n/messages_" + locale + ".properties");
        }
        for (String path : files) {
            List<String> empties = new ArrayList<>();
            for (String line : linesOf(path)) {
                int i = line.indexOf('=');
                if (i > 0 && line.substring(i + 1).isBlank()) {
                    empties.add(line.substring(0, i));
                }
            }
            assertThat(empties).as("%s 里这些词条是空的", path).isEmpty();
        }
    }

    private static Set<String> keysOf(String path) throws IOException {
        return new LinkedHashSet<>(allKeysOf(path));
    }

    /** 保留重复项 —— 去重会让 {@link #noDuplicateKeys} 永远绿 */
    private static List<String> allKeysOf(String path) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : linesOf(path)) {
            int i = line.indexOf('=');
            if (i > 0) {
                out.add(line.substring(0, i).trim());
            }
        }
        return out;
    }

    private static List<String> linesOf(String path) throws IOException {
        try (InputStream in = BackendI18nParityTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("找不到 " + path);
            }
            List<String> out = new ArrayList<>();
            try (var reader = new java.io.BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String t = line.trim();
                    if (!t.isEmpty() && !t.startsWith("#") && !t.startsWith("!")) {
                        out.add(t);
                    }
                }
            }
            return out;
        }
    }
}
