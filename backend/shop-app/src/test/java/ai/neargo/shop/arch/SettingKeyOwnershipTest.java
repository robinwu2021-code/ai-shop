package ai.neargo.shop.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>一个设置键只能归一个设置服务。</b>
 *
 * <h2>这道闸拦的是一个真实发生过、且不报错的缺陷</h2>
 * {@code points.client.policy} 曾经被两边同时用：运营端经 {@code SettingPort}
 * 写 {@code sys_setting}，而支付域经 {@code PaySettingService} 读
 * {@code pay_setting}。后果是运营改了开关、保存成功、页面回显正确，
 * 而<b>支付域完全读不到 —— 积分照发</b>。
 *
 * <p>它躲过了所有既有检查，因为两边<b>各自都是自洽的</b>：
 * 键名一样、类型一样、单测各自绿。差别只在「落到哪张表」，
 * 而那件事没有任何一处代码会说出来。
 *
 * <p>根因是 M2 搬家时只改了一侧。<b>搬家漏一侧这件事会再发生</b>
 * （四个键搬了，将来还会有别的），所以这里不是修一次就完了，
 * 而是要一道常驻的闸。
 *
 * <h2>判据</h2>
 * 扫所有 Java 源码里形如 {@code xxx.get("key"} / {@code xxx.put("key"} 的调用，
 * 按<b>字面量键名</b>归组，看同一个键有没有出现在两个不同的设置服务字段上。
 *
 * <p>只认<b>常量与字面量</b>，不做数据流分析 —— 后者要么误报要么漏报，
 * 而这道闸的价值在于「说出来的一定是真的」。
 */
class SettingKeyOwnershipTest {

    /** 已知的设置服务：字段类型名 → 它落到哪张表 */
    private static final Map<String, String> SETTING_SERVICES = new LinkedHashMap<>() {{
        put("SettingPort", "sys_setting");
        put("SettingService", "sys_setting");
        put("PaySettingService", "pay_setting");
    }};

    @Test
    @DisplayName("★★★ 一个设置键只能归一个设置服务 —— 两边各自自洽，而改了不生效")
    void oneKeyOneOwner() throws IOException {
        Path root = Path.of("..").toRealPath();
        // 键名常量 → 它的字面值
        Map<String, String> consts = new LinkedHashMap<>();
        // 字面键 → 用到它的 (表, 文件)
        Map<String, Set<String>> owners = new LinkedHashMap<>();

        try (Stream<Path> files = Files.walk(root)) {
            List<Path> javas = files
                    .filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.toString().contains("/target/") && !f.toString().contains("/test/"))
                    .toList();
            for (Path f : javas) {
                String src = Files.readString(f);
                consts.clear();
                for (Matcher m = Pattern.compile(
                        "static final String (\\w+) = \"([a-z][a-z0-9.\\-]*\\.[a-z0-9.\\-]+)\"")
                        .matcher(src); m.find(); ) {
                    consts.put(m.group(1), m.group(2));
                }
                // 字段名 → 设置服务类型
                Map<String, String> fields = new LinkedHashMap<>();
                for (Matcher m = Pattern.compile("private (?:final )?([\\w.]+) (\\w+);").matcher(src);
                     m.find(); ) {
                    String type = m.group(1).substring(m.group(1).lastIndexOf('.') + 1);
                    if (SETTING_SERVICES.containsKey(type)) {
                        fields.put(m.group(2), type);
                    }
                }
                if (fields.isEmpty()) {
                    continue;
                }
                for (Matcher m = Pattern.compile("(\\w+)\\.(?:get|put)\\(\\s*(\"[^\"]+\"|\\w+)")
                        .matcher(src); m.find(); ) {
                    String type = fields.get(m.group(1));
                    if (type == null) {
                        continue;
                    }
                    String raw = m.group(2);
                    String key = raw.startsWith("\"") ? raw.substring(1, raw.length() - 1)
                            : consts.get(raw);
                    if (key == null || !key.contains(".")) {
                        continue;
                    }
                    owners.computeIfAbsent(key, k -> new TreeSet<>())
                            .add(SETTING_SERVICES.get(type) + " ← " + f.getFileName());
                }
            }
        }

        /*
         * **扫描面断言。**这道闸是「找出违规」型的：扫不到就报绿。
         * 键一个都没解析出来时（正则失配、目录挪了），它会安静地打勾。
         */
        assertThat(owners)
                .as("一个设置键都没解析出来 —— 多半是正则失配或目录变了，"
                        + "而这道闸扫不到就报绿，少扫比误报危险")
                .isNotEmpty();

        List<String> conflicts = owners.entrySet().stream()
                .filter(e -> e.getValue().stream()
                        .map(v -> v.substring(0, v.indexOf(' ')))
                        .distinct().count() > 1)
                .map(e -> e.getKey() + "\n      " + String.join("\n      ", e.getValue()))
                .toList();

        assertThat(conflicts)
                .as("这些设置键落在**两张不同的表**上。\n"
                        + "两边各自自洽（键名一样、类型一样、单测各自绿），"
                        + "差别只在落到哪张表 —— 而那件事没有任何一处代码会说出来。\n"
                        + "症状是「改了保存成功、页面回显正确、而读的那一侧拿到的还是旧值」，"
                        + "且**全程不报错**。\n"
                        + "2026-09-01 的 points.client.policy 就是这样：运营禁用了某个端的积分发放，积分照发。")
                .isEmpty();
    }
}
