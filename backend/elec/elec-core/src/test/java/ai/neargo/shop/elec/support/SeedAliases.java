package ai.neargo.shop.elec.support;

import ai.neargo.shop.elec.support.Columns.Field;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 测试用：从 V3 迁移里读出表头别名的种子。<b>测的就是真正会上线的那份</b>，而不是另抄一份常量 ——
 * 抄一份的话，种子改了测试照绿。
 */
public final class SeedAliases {

    public static final String MIGRATION = "db/elec/V3__elec_upload_v2.sql";

    private static final Pattern ROW = Pattern.compile("\\('', '([^']*)', '([^']*)', '(\\w+)', 'SEED'\\)");

    private SeedAliases() {
    }

    /** 规范化写法 → 字段 */
    public static Map<String, Field> map() {
        Map<String, Field> m = new HashMap<>();
        Matcher x = ROW.matcher(sql());
        while (x.find()) {
            Field f = Field.valueOf(x.group(3));
            if (m.put(x.group(1), f) != null) {
                throw new IllegalStateException("种子里写法重复：" + x.group(1));
            }
        }
        return m;
    }

    /** 原文 → 字段（校验「原文规范化之后等于 alias_norm」用） */
    public static Map<String, String> raw() {
        Map<String, String> m = new HashMap<>();
        Matcher x = ROW.matcher(sql());
        while (x.find()) {
            m.put(x.group(2), x.group(1));
        }
        return m;
    }

    private static String sql() {
        try (InputStream in = SeedAliases.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (in == null) {
                throw new IllegalStateException("找不到 " + MIGRATION);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
