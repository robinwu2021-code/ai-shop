package ai.neargo.shop.spi.reach;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消费者画像（ADR-034 AC2 / AC4）：区划码按国标嵌套截出祖先集；坐标取各级 S2 token；缺什么就空什么。
 */
class ConsumerProfileTest {

    @Test
    @DisplayName("★★★ 祖先码按 2/4/6/9/12 位截取，只取不超过自身长度的 —— 消费者码比范围项粗就不会命中")
    void ancestors() {
        assertThat(ConsumerProfile.ancestorsOf("440304001003"))
                .containsExactly("44", "4403", "440304", "440304001", "440304001003");
        assertThat(ConsumerProfile.ancestorsOf("440304001"))
                .containsExactly("44", "4403", "440304", "440304001");
        assertThat(ConsumerProfile.ancestorsOf("440304")).containsExactly("44", "4403", "440304");
        assertThat(ConsumerProfile.ancestorsOf("44")).containsExactly("44");
        assertThat(ConsumerProfile.ancestorsOf("44030")).as("非标长度：只取能截的整级").containsExactly("44", "4403");
        assertThat(ConsumerProfile.ancestorsOf(null)).isEmpty();
        assertThat(ConsumerProfile.ancestorsOf("  ")).isEmpty();
    }

    @Test
    @DisplayName("★★ 有坐标则给出 [min,max] 各级 token；没坐标为空，hasCoords 为假")
    void cellTokensFollowCoords() {
        ConsumerProfile withCoords = ConsumerProfile.of("440304", "CM001", null, 22500000, 114000000, 12, 16);
        assertThat(withCoords.cellTokens()).hasSize(5);
        assertThat(withCoords.hasCoords()).isTrue();
        assertThat(withCoords.hasRegion()).isTrue();
        assertThat(withCoords.hasCommunity()).isTrue();

        ConsumerProfile noCoords = ConsumerProfile.of("440304", null, null, null, null, 12, 16);
        assertThat(noCoords.cellTokens()).isEmpty();
        assertThat(noCoords.hasCoords()).isFalse();
        assertThat(noCoords.hasCommunity()).isFalse();
        assertThat(noCoords.hasRegion()).isTrue();
    }

    @Test
    @DisplayName("★★ 只有一半坐标 / 越界坐标 视为没坐标 —— 不臆造")
    void halfOrOutOfRangeCoordsAreIgnored() {
        assertThat(ConsumerProfile.of(null, null, null, 22500000, null, 12, 16).hasCoords()).isFalse();
        assertThat(ConsumerProfile.of(null, null, null, 95_000_000, 114000000, 12, 16).hasCoords())
                .as("纬度 95°").isFalse();
        assertThat(ConsumerProfile.of(null, null, null, 0, 0, 12, 16).hasCoords())
                .as("(0,0) 是端上没拿到定位的典型值，不当真坐标").isFalse();
    }

    @Test
    @DisplayName("★ 什么都没有的画像：三个 has 全假，祖先与 token 都空")
    void emptyProfile() {
        ConsumerProfile p = ConsumerProfile.of(null, null, null, null, null, 12, 16);
        assertThat(p.hasRegion()).isFalse();
        assertThat(p.hasCommunity()).isFalse();
        assertThat(p.hasCoords()).isFalse();
        assertThat(p.ancestors()).isEmpty();
        assertThat(p.cellTokens()).isEmpty();
    }
}
