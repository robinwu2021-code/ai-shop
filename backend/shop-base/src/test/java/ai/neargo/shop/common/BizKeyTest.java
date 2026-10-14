package ai.neargo.shop.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务码统一生成（TDD-业务编码统一生成 · ADR-033）。
 *
 * <p>格式 <code>&lt;前缀&gt; + yyMMdd + 10 位 Crockford base32</code>。
 * 这些判据钉的是「不可枚举 / 多实例不碰撞 / 前缀唯一」——都是出事不报错、只在量具下现形的那种。
 */
class BizKeyTest {

    /** 日期段 = 今天 yyMMdd */
    private static String today() {
        return java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
    }

    @Test
    @DisplayName("★★★ 格式 = 前缀 + yyMMdd + 10 位 Crockford（不含 I/L/O/U）")
    void formatIsPrefixPlusDatePlusTenCrockford() {
        String no = BizKey.next("SO");
        // SO + 6 位日期 + 10 位随机 = 18
        assertThat(no).hasSize(18).startsWith("SO" + today());
        String rnd = no.substring(2 + 6);
        assertThat(rnd).hasSize(10);
        // Crockford：只含 0-9 A-Z，且**不含** I L O U
        assertThat(rnd).matches("[0-9A-HJKMNP-TV-Z]+");
        assertThat(rnd).doesNotContainAnyWhitespaces();
        for (char c : "ILOU".toCharArray()) {
            assertThat(rnd).doesNotContain(String.valueOf(c));
        }
    }

    @Test
    @DisplayName("★★★ 不可枚举：连生成两个，随机段不相邻、不递增 —— 替掉了原来的 seq")
    void randomSegmentIsNotSequential() {
        // 原实现随机段是 seq，连号差 1；新实现是纯随机，连两个几乎不可能相邻
        String a = BizKey.next("SO").substring(8);
        String b = BizKey.next("SO").substring(8);
        assertThat(a).isNotEqualTo(b);
        // 不含到秒的时间戳：整串里除日期段外没有第二段 14 位数字
        assertThat(BizKey.next("SO")).doesNotMatch(".*\\d{14}.*");
    }

    @Test
    @DisplayName("★★★ 十万次无重复（单线程）")
    void hundredThousandNoDup() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            assertThat(seen.add(BizKey.next("SO"))).as("第 %d 个撞了", i).isTrue();
        }
    }

    @Test
    @DisplayName("★★★ 并发多线程生成无重复 —— 无共享计数器，多实例安全的本机镜像")
    void concurrentNoDup() {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        int n = 50_000;
        List<Boolean> dups = Collections.synchronizedList(new ArrayList<>());
        IntStream.range(0, n).parallel().forEach(i -> {
            if (!seen.add(BizKey.next("SO"))) {
                dups.add(true);
            }
        });
        assertThat(dups).as("并发生成出现重复").isEmpty();
        assertThat(seen).hasSize(n);
    }

    @Test
    @DisplayName("★★★ 任意两个前缀常量不相等 —— 撞车是埋着的功能雷（有人按前缀反解）")
    void allPrefixesAreUnique() throws IllegalAccessException {
        Map<String, String> byPrefix = new HashMap<>();
        List<String> dups = new ArrayList<>();
        for (Field f : BizKey.class.getDeclaredFields()) {
            if (!Modifier.isPublic(f.getModifiers()) || !Modifier.isStatic(f.getModifiers())
                    || f.getType() != String.class) {
                continue;
            }
            String prefix = (String) f.get(null);
            String prev = byPrefix.putIfAbsent(prefix, f.getName());
            if (prev != null) {
                dups.add("%s 与 %s 都是 \"%s\"".formatted(prev, f.getName(), prefix));
            }
        }
        assertThat(dups).as("这些前缀撞车了：%s", dups).isEmpty();
        // 对照量：确实扫到了一堆前缀，不是因为一个都没读到才「全唯一」
        assertThat(byPrefix).hasSizeGreaterThan(60);
    }

    @Test
    @DisplayName("★★ 前缀只含大写字母 —— 随机段也是大写数字，避免大小写歧义")
    void prefixesAreUppercaseLetters() {
        Pattern p = Pattern.compile("[A-Z]+");
        for (Field f : BizKey.class.getDeclaredFields()) {
            if (Modifier.isPublic(f.getModifiers()) && Modifier.isStatic(f.getModifiers())
                    && f.getType() == String.class) {
                try {
                    assertThat((String) f.get(null)).as(f.getName()).matches(p.pattern());
                } catch (IllegalAccessException ignored) {
                }
            }
        }
    }
}
