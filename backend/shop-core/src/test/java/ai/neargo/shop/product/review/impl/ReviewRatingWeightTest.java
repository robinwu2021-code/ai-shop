package ai.neargo.shop.product.review.impl;

import ai.neargo.shop.product.review.entity.RvwReview;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评分的**时间加权**（B4，2026-08-14 拍板）。
 *
 * <p>为什么值得单独测：这条规则平时看不出来 —— 分算出来永远是个「像那么回事」的数，
 * 错了也没有任何东西报警。而它错的方向很具体：权重写反（越老权重越大）、
 * 半衰期单位搞错（天写成小时）、或者被下一个人「简化」回算术平均 ——
 * 三种都不影响编译、不影响任何别的用例，只是让分不再代表这家店现在的样子。
 */
class ReviewRatingWeightTest {

    /** 固定「现在」，否则跑测试的时刻会影响结果 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 14, 12, 0);

    private ReviewServiceImpl service() {
        ReviewServiceImpl s = new ReviewServiceImpl(
                null, null, null, null, null, null, null, null, null, null);
        s.clock = () -> NOW;
        return s;
    }

    private static RvwReview review(int rating, int daysAgo) {
        RvwReview r = new RvwReview();
        r.setRating(rating);
        r.setCreatedAt(NOW.minusDays(daysAgo));
        return r;
    }

    @Test
    @DisplayName("★★ 同一天的评价 = 算术平均（加权不该改变没有时间差时的结果）")
    void sameDayEqualsPlainAverage() {
        var s = service();
        assertThat(s.avgX10(List.of(review(5, 0), review(3, 0)), RvwReview::getRating))
                .as("5 与 3 同一天 → 4.0")
                .isEqualTo(40);
    }

    @Test
    @DisplayName("★★★ 半衰期那天的一条，正好顶今天的半条")
    void halfLifeHalvesTheWeight() {
        var s = service();
        /*
         * 今天一条 5 分、180 天前一条 1 分：
         *   (1.0×5 + 0.5×1) / (1.0 + 0.5) = 5.5/1.5 = 3.667 → 37
         * 算术平均会给 3.0（30）。这两个数必须不同，否则加权根本没生效。
         */
        assertThat(s.avgX10(List.of(review(5, 0), review(1, 180)), RvwReview::getRating))
                .isEqualTo(37);
    }

    @Test
    @DisplayName("★★★ 权重方向不能反 —— 新的差评必须比旧的好评更有话语权")
    void recentDominatesOld() {
        var s = service();
        // 两年前十条 5 分，今天两条 1 分。算术平均是 4.33，加权后应当低于 3
        var rows = new java.util.ArrayList<RvwReview>();
        for (int i = 0; i < 10; i++) {
            rows.add(review(5, 730));
        }
        rows.add(review(1, 0));
        rows.add(review(1, 0));

        int weighted = s.avgX10(rows, RvwReview::getRating);
        assertThat(weighted)
                .as("一家店整改后的新评价被两年前的旧账压住，正是加权要解决的事")
                .isLessThan(30);
        assertThat(weighted).isGreaterThan(10);   // 也不该把旧评价当成不存在
    }

    @Test
    @DisplayName("★★ 没填的维度分不参与，不是当 0 分摊进去")
    void nullDimensionIsSkippedNotZero() {
        var s = service();
        RvwReview filled = review(5, 0);
        filled.setScoreGoods(4);
        RvwReview blank = review(5, 0);   // 老评价没有维度分

        assertThat(s.avgX10(List.of(filled, blank), RvwReview::getScoreGoods))
                .as("当 0 分摊会让一家店因为「有人只打了总分」而莫名其妙掉分")
                .isEqualTo(40);
    }

    @Test
    @DisplayName("★★ 一条有效分都没有时返回 0 —— 不是凭空给 5 分")
    void noValuesGivesZero() {
        assertThat(service().avgX10(List.of(review(5, 0)), RvwReview::getScoreService))
                .isZero();
    }

    @Test
    @DisplayName("★★ createdAt 为空按最老算 —— 来历不明的行不该主导今天的评分")
    void missingCreatedAtWeighsAlmostNothing() {
        var s = service();
        RvwReview legacy = new RvwReview();
        legacy.setRating(1);   // 没有 createdAt

        assertThat(s.avgX10(List.of(review(5, 0), legacy), RvwReview::getRating))
                .as("权重 ≈0.001，几乎不影响；按最新算的话这条会把 5 分拉到 3 分")
                .isEqualTo(50);
    }
}
