package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityVO;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.service.ActivityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 团购作为活动的一种玩法（{@code GROUP × PRICE}）。
 *
 * <p>团购的价格与人数此前长在商品上（{@code prd_goods.group_price_minor}），
 * 一件货一辈子只有一个团购价。挪进活动之后它才可能在不同时间参加不同的团 ——
 * 见 {@code TDD-团购从商品挪进活动}。
 *
 * <p>这组用例守三条**放过去就没人能补救**的事：一个人的「团」、
 * 配成满减的「团购价」、同一件货同时挂在两个团上。
 */
@SpringBootTest
@ActiveProfiles("test")
class ActivityGroupBuyTest {

    @Autowired
    private ActivityService activityService;
    @Autowired
    private ai.neargo.shop.promotion.service.ActivityPricingService pricing;

    private static int seq = 7300;

    /** ONE_OFF 要求 startAt > 0 且 endAt > startAt —— 0 与 Long.MAX_VALUE 都不合法 */
    private static final long NOW = System.currentTimeMillis();
    private static final long DAY = 24L * 3600 * 1000;

    /** 一个团购活动草稿：{@code triggerQty} 人成团、成团价 {@code priceMinor}、作用在这些货上 */
    private static ActivityDraft group(Integer minCount, long priceMinor, List<String> goodsNos) {
        return new ActivityDraft(null, "团购 · " + (++seq), "CLEAR", null,
                PmtActivity.TRIGGER_GROUP, null, minCount,
                PmtActivity.BENEFIT_PRICE, priceMinor, null, null,
                PmtActivity.ONE_OFF, NOW, NOW + DAY, null, 100, null,
                List.of(), goodsNos);
    }

    @Test
    @DisplayName("★★★ 一个人的「团」存不进去 —— 那是降价，而界面上写着团购")
    void groupNeedsAtLeastTwo() {
        String e = "M-GB-" + (++seq);
        assertThatThrownBy(() -> activityService.save(e, group(1, 900L, List.of("G-A" + seq)), "OP"))
                .as("1 人成团放过去的话，它就是一个谁买都生效的降价，而商家以为自己在攒人")
                .isInstanceOf(BizException.class);

        ActivityVO ok = activityService.save(e, group(2, 900L, List.of("G-A" + seq)), "OP");
        assertThat(ok.triggerQty()).as("2 人是下限，要存得进").isEqualTo(2);
    }

    @Test
    @DisplayName("★★★ 团购的优惠只能是改单价 —— 配成满减的话团照样成、价照样是原价")
    void groupBenefitMustBePrice() {
        String e = "M-GB-" + (++seq);
        ActivityDraft cut = new ActivityDraft(null, "团购 · 配错了", "CLEAR", null,
                PmtActivity.TRIGGER_GROUP, null, 3,
                PmtActivity.BENEFIT_CUT, 500L, null, null,
                PmtActivity.ONE_OFF, NOW, NOW + DAY, null, 100, null,
                List.of(), List.of("G-B" + seq));
        assertThatThrownBy(() -> activityService.save(e, cut, "OP"))
                .as("算价那一侧不知道拿满减怎么配团，而它不报错 —— 只会让成团价永远不生效")
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★★ 一件货同时挂两个团购 —— 第二个存不进去（买家会看到一个价、付另一个价）")
    void oneGoodsOneGroupAtATime() {
        String e = "M-GB-" + (++seq);
        String goods = "G-C" + seq;
        ActivityVO first = activityService.save(e, group(2, 900L, List.of(goods)), "OP");

        assertThatThrownBy(() -> activityService.save(e, group(3, 800L, List.of(goods)), "OP"))
                .as("★ 拦在服务端，不是靠端上那个 /biz/activity-conflicts —— 绕开它照样存得进去")
                .isInstanceOf(BizException.class);

        /*
         * **改自己不算撞。** 不排除自己的话，编辑一个已有的团购活动第二次保存必失败，
         * 而报错说的是「这件货已经在别的团里」—— 指的正是它自己。
         */
        ActivityDraft edit = new ActivityDraft(first.activityNo(), first.name(), "CLEAR", null,
                PmtActivity.TRIGGER_GROUP, null, 4,
                PmtActivity.BENEFIT_PRICE, 850L, null, null,
                PmtActivity.ONE_OFF, NOW, NOW + DAY, null, 100, null,
                List.of(), List.of(goods));
        assertThat(activityService.save(e, edit, "OP").triggerQty())
                .as("改自己要存得进，否则团购活动建完就再也改不了")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("★★★ 团购的成团价不是限时特价 —— 不判触发的话所有人都按成团价买到")
    void groupPriceIsNotAFlashPrice() {
        String e = "M-GB-" + (++seq);
        String goods = "G-E" + seq;
        activityService.save(e, group(3, 800L, List.of(goods)), "OP");

        assertThat(pricing.flashPrices(null, List.of(goods)))
                .as("★ 团购活动被当成限时特价了 —— 不用凑人数、不用进团，"
                        + "所有人直接按成团价买到，而它一个字都不报，只有对账时才看得出每单少收了钱")
                .doesNotContainKey(goods);

        /*
         * **反向也要验**：同一件货挂一个真的限时特价，必须算得出来 ——
         * 否则「不含团购」这条断言可能只是因为 flashPrices 整个坏了。
         */
        ActivityDraft flash = new ActivityDraft(null, "真特价 " + seq, "CLEAR", null,
                PmtActivity.TRIGGER_GOODS, null, null,
                PmtActivity.BENEFIT_PRICE, 700L, null, null,
                PmtActivity.ONE_OFF, NOW, NOW + DAY, null, 100, null,
                List.of(), List.of(goods));
        activityService.save(e, flash, "OP");
        assertThat(pricing.flashPrices(null, List.of(goods)))
                .as("对照：真的限时特价要算得出来，否则上面那条证明不了是「排掉了团购」")
                .containsEntry(goods, 700L);
    }

    @Test
    @DisplayName("★★ 别的玩法不受这条限制 —— 限时特价与团购撞在一起这一轮不管")
    void nonGroupActivitiesAreNotBlocked() {
        String e = "M-GB-" + (++seq);
        String goods = "G-D" + seq;
        activityService.save(e, group(2, 900L, List.of(goods)), "OP");

        ActivityDraft flash = new ActivityDraft(null, "限时特价 · " + seq, "CLEAR", null,
                PmtActivity.TRIGGER_GOODS, null, null,
                PmtActivity.BENEFIT_PRICE, 880L, null, null,
                PmtActivity.ONE_OFF, NOW, NOW + DAY, null, 100, null,
                List.of(), List.of(goods));
        assertThat(activityService.save(e, flash, "OP").activityNo())
                .as("这一条只管团购之间；限时特价与团购的优先级是另一件事，不该在这儿悄悄拦掉")
                .isNotBlank();
    }
}
