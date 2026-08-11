package ai.neargo.shop.arch;

import ai.neargo.shop.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码的唯一性。
 *
 * <p><b>这条守卫是四对重号换来的</b>：70004 / 70005 / 70006 / 70007 各被两个枚举占着
 * （开票与秒杀、开票与活动、开票与角色、资质过期与经营范围）。
 *
 * <p>重号不会让任何测试变红，也不会让任何请求失败 —— 它只是让**端上分不清**：
 * 拿到 70006，B 端不知道该说「你的角色打不开这一页」还是「发票抬头对不上」，
 * 于是两种情况都会显示同一句话，而其中一句必然是错的。
 * 更糟的是它连查都难查：两处的语义都对，只有码撞了。
 *
 * <p>message key 同样不许重复：两个错误共用一条文案，改文案时必然只顾到一处。
 */
class ErrorCodeUniqueTest {

    @Test
    @DisplayName("★★ 错误码不许重号 —— 撞了不会报错，只会让端上把两种错误说成同一句话")
    void codesAreUnique() {
        Map<Integer, List<String>> byCode = new LinkedHashMap<>();
        for (ErrorCode e : ErrorCode.values()) {
            byCode.computeIfAbsent(e.code(), k -> new ArrayList<>()).add(e.name());
        }
        List<String> dup = byCode.entrySet().stream()
                .filter(en -> en.getValue().size() > 1)
                .map(en -> en.getKey() + " 被 " + String.join(" / ", en.getValue()) + " 同时占着")
                .toList();

        assertThat(dup)
                .as("这些码重了。端上按码分支，撞号意味着两种错误在端上无法区分：\n  "
                        + String.join("\n  ", dup)
                        + "\n→ 保留端上已经在用的那个语义，另一个改到没人用的号段")
                .isEmpty();
    }

    @Test
    @DisplayName("★ 同一条 message key 不许被两个码共用 —— 改文案时必然只顾到一处")
    void messageKeysAreUnique() {
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        for (ErrorCode e : ErrorCode.values()) {
            byKey.computeIfAbsent(e.msgKey(), k -> new ArrayList<>()).add(e.name());
        }
        List<String> dup = byKey.entrySet().stream()
                .filter(en -> en.getValue().size() > 1)
                .map(en -> en.getKey() + " 被 " + String.join(" / ", en.getValue()) + " 共用")
                .toList();

        assertThat(dup).as("这些 message key 重了：\n  " + String.join("\n  ", dup)).isEmpty();
    }
}
