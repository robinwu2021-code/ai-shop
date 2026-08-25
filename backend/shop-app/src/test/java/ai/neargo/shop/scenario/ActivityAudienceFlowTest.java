package ai.neargo.shop.scenario;

import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.member.service.MemberTagService;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityVO;
import ai.neargo.shop.promotion.dto.ActivityVOs.AudienceItem;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtActivityAudience;
import ai.neargo.shop.promotion.service.ActivityPricingService;
import ai.neargo.shop.promotion.service.ActivityService;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.user.service.PersonService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 活动受众与限量（P5 的算价那一半）。
 *
 * <p><b>最要紧的一条是「空受众 = 对所有人生效」</b>：老活动没有受众概念，
 * 迁过来之后行为必须逐分不变。反过来的设计（空 = 谁都不给）会让存量活动
 * 在上线那一刻集体失效，而症状是「活动还在、就是不减钱」——
 * 商家会先怀疑算价，查上一整天。
 */
@SpringBootTest
@ActiveProfiles("test")
class ActivityAudienceFlowTest {

    @Autowired
    private ActivityService activityService;

    @Autowired
    private ActivityPricingService pricing;

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberTagService tagService;

    @Autowired
    private PersonService personService;

    private static int seq = 7300;

    private ActivityVO cut(String entityNo, List<AudienceItem> audiences, Integer quota) {
        long now = System.currentTimeMillis();
        return activityService.save(entityNo, new ActivityDraft(null, "满 50 减 5 · " + (++seq),
                "BASKET", null, PmtActivity.TRIGGER_AMOUNT, 5_000L, null,
                PmtActivity.BENEFIT_CUT, 500L, null, null,
                PmtActivity.ONE_OFF, now - 1000, now + 86400_000L, null,
                quota, null, audiences, List.of()), "OP");
    }

    /** 造一个已注册、已入会的买家，返回 userNo */
    private String buyer(String entityNo) {
        String phone = "1350000" + (++seq);
        String userNo = "U-AUD-" + seq;
        String personNo = personService.resolveOrCreateByPhone(phone).getPersonNo();
        personService.bindOnLogin(userNo, phone);
        memberService.onOrderPaid("SUB-AUD-" + seq, userNo, personNo, entityNo, "ST-1",
                5_000, System.currentTimeMillis());
        return userNo;
    }

    private static List<CampaignPort.MerchantAmount> basket(String entityNo, long amount) {
        return List.of(new CampaignPort.MerchantAmount(entityNo, amount, null));
    }

    @Test
    @DisplayName("★★★ 空受众 = 对所有人生效：连没注册的人也减")
    void emptyAudienceMeansEveryone() {
        String e = "M-AUD-" + (++seq);
        cut(e, List.of(), null);

        assertThat(pricing.autoDiscount("U-NOBODY", basket(e, 9_000)).total())
                .as("老活动迁过来没有受众行，行为必须逐分不变").isEqualTo(500);
    }

    @Test
    @DisplayName("★★★ 会员专享：会员减、非会员不减")
    void memberOnlyActivity() {
        String e = "M-AUD-" + (++seq);
        cut(e, List.of(new AudienceItem(PmtActivityAudience.LEVEL, "NEW")), null);

        String member = buyer(e);
        assertThat(pricing.autoDiscount(member, basket(e, 9_000)).total())
                .as("他是新客，命中 LEVEL=NEW").isEqualTo(500);
        assertThat(pricing.autoDiscount("U-STRANGER", basket(e, 9_000)).total())
                .as("不是会员就不该减").isZero();
    }

    @Test
    @DisplayName("★★★ 拉新活动（NON_MEMBER）刚好相反：会员不减、生人才减")
    void nonMemberActivity() {
        String e = "M-AUD-" + (++seq);
        cut(e, List.of(new AudienceItem(PmtActivityAudience.NON_MEMBER, "*")), null);

        String member = buyer(e);
        assertThat(pricing.autoDiscount("U-STRANGER", basket(e, 9_000)).total())
                .as("拉新要的正是「还不是我的会员的人」").isEqualTo(500);
        assertThat(pricing.autoDiscount(member, basket(e, 9_000)).total())
                .as("已经是会员了，拉新券不该再给他").isZero();
    }

    @Test
    @DisplayName("★★ 多行受众之间是「或」—— 受众是圈人，不是筛人")
    void multipleAudiencesAreOr() {
        String e = "M-AUD-" + (++seq);
        String member = buyer(e);
        String tagNo = tagService.create(e, "爱囤货" + seq, "OP").tagNo();
        String memberNo = memberService.find(e,
                personService.resolveOrCreateByPhone("1350000" + seq).getPersonNo())
                .map(m -> m.getMemberNo()).orElseThrow();
        tagService.tag(e, List.of(memberNo), List.of(tagNo), List.of(), "OP");

        // 一条命中不了（LOYAL），另一条命中（标签）—— 或的关系，应当生效
        cut(e, List.of(new AudienceItem(PmtActivityAudience.LEVEL, "LOYAL"),
                new AudienceItem(PmtActivityAudience.TAG, tagNo)), null);

        assertThat(pricing.autoDiscount(member, basket(e, 9_000)).total()).isEqualTo(500);
    }

    @Test
    @DisplayName("★★★ 限量：扣到 0 自动结束，并说得出为什么停")
    void quotaRunsOutAndSaysWhy() {
        String e = "M-AUD-" + (++seq);
        ActivityVO a = cut(e, List.of(), 2);

        for (int i = 1; i <= 2; i++) {
            CampaignPort.Discount d = pricing.autoDiscount("U-ANY", basket(e, 9_000));
            assertThat(d.total()).as("第 %d 单", i).isEqualTo(500);
            pricing.commit("O-" + seq + "-" + i, d);
        }

        ActivityVO after = activityService.detail(e, a.activityNo());
        assertThat(after.status()).isEqualTo(PmtActivity.ENDED);
        assertThat(after.endedReason()).as("商家问「怎么停了」要有答案")
                .isEqualTo(PmtActivity.ENDED_QUOTA);
        assertThat(after.quotaUsed()).isEqualTo(2);

        // 到量之后不再减
        assertThat(pricing.autoDiscount("U-ANY", basket(e, 9_000)).total()).isZero();
    }

    @Test
    @DisplayName("★★ 门槛不够不减；同类多个活动取最优（商家多建一个不该让顾客少减）")
    void thresholdAndBestOf() {
        String e = "M-AUD-" + (++seq);
        cut(e, List.of(), null);
        assertThat(pricing.autoDiscount("U-ANY", basket(e, 4_999)).total())
                .as("差一分钱都不算满").isZero();

        // 再建一个减更多的
        long now = System.currentTimeMillis();
        activityService.save(e, new ActivityDraft(null, "满 50 减 8", null, null,
                PmtActivity.TRIGGER_AMOUNT, 5_000L, null,
                PmtActivity.BENEFIT_CUT, 800L, null, null,
                PmtActivity.ONE_OFF, now - 1000, now + 86400_000L, null,
                null, null, List.of(), List.of()), "OP");

        assertThat(pricing.autoDiscount("U-ANY", basket(e, 9_000)).total())
                .as("取最优，不是相加").isEqualTo(800);
    }
}
