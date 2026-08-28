package ai.neargo.auth.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 一个池里两类主体的分发。**判据是「认不出就拒绝」，不是「能找到就行」。**
 *
 * <p>B 端池里同时装着店员的 {@code mch_account_no} 与还没开店的人的 {@code user_no}
 * —— 生产实测 9 个商家账号里 8 个没有 {@code usr_account}，所以两类都得认。
 * 而两类都认之后，<b>唯一危险的实现是「挨个试」</b>：那等于把「这个号该去哪张表查」
 * 退回成猜，猜错就是把会话解析成另一个人 —— 不报错，只是让人看见别人的数据。
 */
class CompositeIdentityLoaderTest {

    private static IdentityLoader<String> loader(SubjectKind k, String id, String result) {
        return new IdentityLoader<>() {
            @Override
            public Optional<String> load(String userNo) {
                return id.equals(userNo) ? Optional.of(result) : Optional.empty();
            }

            @Override
            public SubjectKind kind() {
                return k;
            }
        };
    }

    private CompositeIdentityLoader<String> bizPool() {
        return new CompositeIdentityLoader<>(
                loader(SubjectKind.MCH, "SF-M0001", "店员甲"),
                loader(SubjectKind.USR, "U2026", "还没开店的人"));
    }

    @Test
    @DisplayName("★★ 各按各的 kind 分发")
    void dispatchesByKind() {
        assertEquals(Optional.of("店员甲"), bizPool().load("SF-M0001", SubjectKind.MCH));
        assertEquals(Optional.of("还没开店的人"), bizPool().load("U2026", SubjectKind.USR));
    }

    @Test
    @DisplayName("★★★ kind 对不上就是查不到 —— 绝不回落到另一类去试")
    void doesNotFallBackToTheOtherKind() {
        // 号是真的、kind 是错的。回落实现会把它查出来，那正是要禁止的
        assertTrue(bizPool().load("SF-M0001", SubjectKind.USR).isEmpty(),
                "回落到另一类去试，等于把「去哪张表查」退回成猜");
        assertTrue(bizPool().load("U2026", SubjectKind.MCH).isEmpty());
    }

    @Test
    @DisplayName("★★★ kind 缺失或不认识 → 拒绝，不是随便挑一个加载器")
    void unknownKindIsRejected() {
        assertTrue(bizPool().load("SF-M0001", null).isEmpty(),
                "没有 kind 的会话行必须被拒 —— 它不该存在，subject_kind 在插入时是必传的");
        assertTrue(bizPool().load("SF-M0001", SubjectKind.OPS).isEmpty(),
                "这个池里没有 OPS 主体");
    }

    @Test
    @DisplayName("★ 两个加载器认领同一类 → 启动就炸，不留下「看参数顺序」的行为")
    void duplicateKindIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new CompositeIdentityLoader<>(
                loader(SubjectKind.MCH, "a", "甲"),
                loader(SubjectKind.MCH, "b", "乙")));
    }

    @Test
    @DisplayName("★ 单参 load 直接抛 —— 走到那条路径说明调用方丢了 kind")
    void singleArgLoadIsUnsupported() {
        assertThrows(UnsupportedOperationException.class, () -> bizPool().load("SF-M0001"));
    }
}
