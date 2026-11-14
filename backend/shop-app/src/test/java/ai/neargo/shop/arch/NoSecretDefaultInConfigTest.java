package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 密钥不得有默认值（支付服务-安全规范 §3.2，G6）：生产配置（各模块 {@code src/main/resources} 的 yml / properties）里，
 * 名字像密钥的键只能写成 {@code ${ENV}}，**不带默认值、不写字面量**。
 * <p>
 * 带默认值的后果：漏配环境变量时应用照常启动、静默用上一个人人知道的口令（2026-10-26 前 {@code SHOP_DB_PASS} 默认 {@code shop}）。
 * 不带默认值时缺配置启动即失败 —— 那正是想要的。测试 / 本地用各自 profile（{@code src/test}）覆盖，不受本条约束。
 */
class NoSecretDefaultInConfigTest {

    /** 键的最后一段含这些词即视为密钥 */
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(password|passwd|secret|token|api-?key|private-?key|credential|pepper|signing-?key|hmac|cipher-?key|master-?key)",
            Pattern.CASE_INSENSITIVE);
    /** 名字像但其实是开关、路径、存储类型、头名、长度 … */
    private static final Pattern NOT_A_SECRET = Pattern.compile(
            "(-path|-file|-store|-header|-ttl|-enabled|-delivery|-length|-name|-type|-mode|-url|-prefix|-expire[a-z-]*|-days|-minutes|-seconds)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern YML_LINE = Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*:\\s*(.+?)\\s*(#.*)?$");
    private static final Pattern PROP_LINE = Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*[=:]\\s*(.+?)\\s*$");
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[A-Za-z0-9_.-]+(:(.*))?\\}$");

    @Test
    @DisplayName("★★★ 生产配置里的密钥类键只能是不带默认值的 ${ENV}")
    void secretKeysHaveNoDefaults() throws IOException {
        Path backend = Path.of("..").toAbsolutePath().normalize();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(backend)) {
            for (Path p : files.filter(NoSecretDefaultInConfigTest::isProdConfig).toList()) {
                boolean yml = !p.toString().endsWith(".properties");
                List<String> lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = (yml ? YML_LINE : PROP_LINE).matcher(lines.get(i));
                    if (!m.matches()) {
                        continue;
                    }
                    String key = m.group(1);
                    String last = key.substring(key.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                    if (!SECRET_KEY.matcher(last).find() || NOT_A_SECRET.matcher(last).find()) {
                        continue;
                    }
                    String value = stripQuotes(m.group(2));
                    if (value.isEmpty() || value.equals("|") || value.equals(">")) {
                        continue;   // 只是父节点或块标记
                    }
                    Matcher ph = PLACEHOLDER.matcher(value);
                    boolean bad = ph.matches() ? ph.group(2) != null && !ph.group(2).isEmpty() : true;
                    if (bad) {
                        offenders.add(backend.relativize(p) + ":" + (i + 1) + "  " + key + ": " + value);
                    }
                }
            }
        }
        assertThat(offenders).as("密钥类配置去掉默认值 / 字面量，改由部署注入（systemd EnvironmentFile）").isEmpty();
    }

    private static boolean isProdConfig(Path p) {
        String s = p.toString();
        return s.contains("/src/main/resources/") && !s.contains("/target/") && !s.contains("/node_modules/")
                && (s.endsWith(".yml") || s.endsWith(".yaml") || s.endsWith(".properties"))
                && !s.contains("/i18n/") && !s.contains("/db/");
    }

    private static String stripQuotes(String v) {
        String t = v.trim();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'"))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}
