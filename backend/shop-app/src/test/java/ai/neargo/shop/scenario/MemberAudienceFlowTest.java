package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.dto.MemberVOs.AudiencePreviewVO;
import ai.neargo.shop.member.dto.MemberVOs.BatchTagVO;
import ai.neargo.shop.member.dto.MemberVOs.MemberQuery;
import ai.neargo.shop.member.dto.MemberVOs.MergePreviewVO;
import ai.neargo.shop.member.dto.MemberVOs.TagUsageVO;
import ai.neargo.shop.member.entity.MbrMember;
import ai.neargo.shop.member.service.MemberAudienceService;
import ai.neargo.shop.member.service.MemberSegmentService;
import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.member.service.MemberTagService;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityVO;
import ai.neargo.shop.promotion.dto.ActivityVOs.AudienceItem;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponIssueVO;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtActivityAudience;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.service.ActivityPricingService;
import ai.neargo.shop.promotion.service.ActivityService;
import ai.neargo.shop.promotion.service.CouponService;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.member.MemberQueryPort;
import ai.neargo.shop.user.service.PersonService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会员作为营销目标（PRD-会员标签与定向营销 批 B）：打标签 → 选人 → 活动 / 发券。
 *
 * <p>每条用例都从「商家在 App 上会做的那一步」出发，断言落在算价或发放的结果上，
 * 而不是落在某张表的行数上 —— 行数对了、活动不减钱，是这条线上最常见的一种假绿。
 */
@SpringBootTest
@ActiveProfiles("test")
class MemberAudienceFlowTest {

    @Autowired
    private MemberService memberService;
    @Autowired
    private MemberTagService tagService;
    @Autowired
    private MemberAudienceService audienceService;
    @Autowired
    private MemberSegmentService segmentService;
    @Autowired
    private ActivityService activityService;
    @Autowired
    private ActivityPricingService pricing;
    @Autowired
    private CouponService couponService;
    @Autowired
    private PersonService personService;

    private static int seq = 7400;

    private record Buyer(String userNo, String memberNo) {
    }

    private static String entity() {
        return "M-AUD2-" + (++seq);
    }

    /** 已注册、下过单的会员（ACTIVE，可触达） */
    private Buyer buyer(String e) {
        String phone = "1351100" + (++seq);
        String userNo = "U-AUD2-" + seq;
        String personNo = personService.resolveOrCreateByPhone(phone).getPersonNo();
        personService.bindOnLogin(userNo, phone);
        memberService.onOrderPaid("SUB-AUD2-" + seq, userNo, personNo, e, "ST-1", 5_000,
                System.currentTimeMillis());
        return new Buyer(userNo, memberService.find(e, personNo).orElseThrow().getMemberNo());
    }

    private String tag(String e, String name) {
        return tagService.create(e, name + seq, "OP").tagNo();
    }

    private void tagOne(String e, String memberNo, String tagNo) {
        tagService.tag(e, List.of(memberNo), List.of(tagNo), List.of(), "OP");
    }

    private static MemberQuery byTags(List<String> tagNos) {
        return new MemberQuery(null, null, null, null, null, tagNos, null, null, null, null, 1, 0);
    }

    private ActivityVO cut(String e, List<AudienceItem> audiences) {
        long now = System.currentTimeMillis();
        return activityService.save(e, new ActivityDraft(null, "满 50 减 5 · " + (++seq),
                "BASKET", null, PmtActivity.TRIGGER_AMOUNT, 5_000L, null,
                PmtActivity.BENEFIT_CUT, 500L, null, null,
                PmtActivity.ONE_OFF, now - 1000, now + 86400_000L, null,
                null, null, audiences, List.of()), "OP");
    }

    private long discount(String e, String userNo) {
        return pricing.autoDiscount(userNo,
                List.of(new CampaignPort.MerchantAmount(e, 9_000, 1, null))).total();
    }

    // ------------------------------------------------------------------ 打标签

    @Test
    @DisplayName("★★★ AC-1 打上「爱囤货」按它筛得到；去掉筛不到；再打回去不撞键")
    void tagThenFilterFindsMember() {
        String e = entity();
        Buyer b = buyer(e);
        String t = tag(e, "爱囤货");

        tagOne(e, b.memberNo(), t);
        assertThat(memberService.match(e, byTags(List.of(t)))).containsExactly(b.memberNo());

        tagService.tag(e, List.of(b.memberNo()), List.of(), List.of(t), "OP");
        assertThat(memberService.match(e, byTags(List.of(t)))).isEmpty();

        // 关系表唯一键不含 deleted：逻辑删的话，这一步撞 DuplicateKey
        tagOne(e, b.memberNo(), t);
        assertThat(memberService.match(e, byTags(List.of(t)))).containsExactly(b.memberNo());
    }

    @Test
    @DisplayName("★★★ AC-2 按条件批量打标：试算写明「已有的不重复计」，确认后只改真变了的人")
    void batchTagPreviewCountsOnlyChanges() {
        String e = entity();
        Buyer a = buyer(e);
        Buyer b = buyer(e);
        Buyer c = buyer(e);
        String sleepy = tag(e, "筛出来的");
        String target = tag(e, "中秋唤回");
        for (Buyer x : List.of(a, b, c)) {
            tagOne(e, x.memberNo(), sleepy);
        }
        tagOne(e, a.memberNo(), target);

        BatchTagVO preview = audienceService.batchTag(e, null, byTags(List.of(sleepy)), null,
                target, true, false, "OP");
        assertThat(preview.matched()).isEqualTo(3);
        assertThat(preview.alreadyInState()).isEqualTo(1);
        assertThat(preview.willChange()).isEqualTo(2);
        assertThat(preview.applied()).isFalse();
        assertThat(memberService.match(e, byTags(List.of(target)))).as("试算不落库").hasSize(1);

        BatchTagVO done = audienceService.batchTag(e, null, byTags(List.of(sleepy)), null,
                target, true, true, "OP");
        assertThat(done.willChange()).isEqualTo(2);
        assertThat(memberService.match(e, byTags(List.of(target)))).hasSize(3);
    }

    @Test
    @DisplayName("★★ AC-3 标签满 10 个的人跳过并计数，其余照打 —— 1 个满了不该拦住另外的人")
    void batchTagSkipsMembersAtTenTags() {
        String e = entity();
        Buyer full = buyer(e);
        Buyer ok = buyer(e);
        for (int i = 0; i < 10; i++) {
            tagOne(e, full.memberNo(), tag(e, "占位" + i + "-"));
        }
        String t = tag(e, "第十一个");

        BatchTagVO r = audienceService.batchTag(e, List.of(full.memberNo(), ok.memberNo()), null, null,
                t, true, true, "OP");

        assertThat(r.skippedFull()).isEqualTo(1);
        assertThat(r.willChange()).isEqualTo(1);
        assertThat(memberService.match(e, byTags(List.of(t)))).containsExactly(ok.memberNo());
        assertThatThrownBy(() -> tagOne(e, full.memberNo(), t))
                .as("单个打标仍按上限拒，提示写明上限").isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.MEMBER_TAG_LIMIT.name());
    }

    @Test
    @DisplayName("★ 别家的会员号不许打标 —— 否则本店留下一条指向别人的关系行")
    void foreignMemberRejected() {
        String mine = entity();
        String other = entity();
        Buyer stranger = buyer(other);
        String t = tag(mine, "我的");
        assertThatThrownBy(() -> tagOne(mine, stranger.memberNo(), t)).isInstanceOf(BizException.class);
        BatchTagVO r = audienceService.batchTag(mine, List.of(stranger.memberNo()), null, null,
                t, true, true, "OP");
        assertThat(r.matched()).as("批量里静默剔除").isZero();
    }

    // ------------------------------------------------------------------ 选人

    @Test
    @DisplayName("★★★ AC-4 线索能打标签，但进不了受众：命中 1、收得到 0、原因 LEAD")
    void leadCanBeTaggedButNeverReachable() {
        String e = entity();
        String t = tag(e, "老街坊");
        MbrMember lead = memberService.enroll(e, "1371199" + (++seq), null, List.of(t), "ST-1", "OP");
        assertThat(lead.getStatus()).isEqualTo(MbrMember.LEAD);

        AudiencePreviewVO p = audienceService.preview(e,
                List.of(new MemberQueryPort.AudienceItem(MemberQueryPort.AudienceItem.TAG, t)), "NOTICE", false);

        assertThat(p.matched()).isEqualTo(1);
        assertThat(p.reachable()).isZero();
        assertThat(p.skips()).extracting(MemberQueryPort.Skip::reason).containsExactly("LEAD");
    }

    @Test
    @DisplayName("★★ 选人面板多项取或，重叠只算一次；活动场景只给命中数")
    void previewUnionsItems() {
        String e = entity();
        Buyer a = buyer(e);
        Buyer b = buyer(e);
        buyer(e);
        String x = tag(e, "甲");
        String y = tag(e, "乙");
        tagOne(e, a.memberNo(), x);
        tagOne(e, a.memberNo(), y);
        tagOne(e, b.memberNo(), y);
        var items = List.of(new MemberQueryPort.AudienceItem(MemberQueryPort.AudienceItem.TAG, x),
                new MemberQueryPort.AudienceItem(MemberQueryPort.AudienceItem.TAG, y));

        assertThat(audienceService.preview(e, items, null, false).matched()).isEqualTo(2);
        AudiencePreviewVO act = audienceService.preview(e, items, null, true);
        assertThat(act.matched()).isEqualTo(2);
        assertThat(act.reachable()).as("活动不推送，没有收得到一说").isNull();
    }

    // ------------------------------------------------------------------ 活动

    @Test
    @DisplayName("★★★ AC-8 活动受众选标签：没这个标签的人不减，打上之后减")
    void tagAudienceGatesPricing() {
        String e = entity();
        Buyer tagged = buyer(e);
        Buyer later = buyer(e);
        String t = tag(e, "熟面孔");
        tagOne(e, tagged.memberNo(), t);
        cut(e, List.of(new AudienceItem(PmtActivityAudience.TAG, t)));

        assertThat(discount(e, tagged.userNo())).isEqualTo(500);
        assertThat(discount(e, later.userNo())).isZero();

        tagOne(e, later.memberNo(), t);
        assertThat(discount(e, later.userNo())).isEqualTo(500);
    }

    @Test
    @DisplayName("★★★ AC-9 活动按发布那一刻的人群条件生效：之后改人群，不改活动的受众")
    void segmentAudienceUsesSnapshotAfterRuleChange() {
        String e = entity();
        Buyer x = buyer(e);
        Buyer y = buyer(e);
        String tx = tag(e, "当时的");
        String ty = tag(e, "后来的");
        tagOne(e, x.memberNo(), tx);
        tagOne(e, y.memberNo(), ty);
        String sg = segmentService.save(e, null, "南门店老客" + seq, null, byTags(List.of(tx))).segmentNo();
        ActivityVO a = cut(e, List.of(new AudienceItem(PmtActivityAudience.SEGMENT, sg)));

        segmentService.save(e, sg, "南门店老客" + seq, null, byTags(List.of(ty)));

        assertThat(discount(e, x.userNo())).as("发布时的条件里有他").isEqualTo(500);
        assertThat(discount(e, y.userNo())).as("人群后来才圈进他，进行中的活动不跟着变").isZero();

        // 进行中的活动改结束时间也会整批重写受众行 —— 快照必须原样留下，不能被刷成新条件
        long now = System.currentTimeMillis();
        activityService.save(e, new ActivityDraft(a.activityNo(), a.name(),
                "BASKET", null, PmtActivity.TRIGGER_AMOUNT, 5_000L, null,
                PmtActivity.BENEFIT_CUT, 500L, null, null,
                PmtActivity.ONE_OFF, a.startAt(), now + 2 * 86400_000L, null,
                null, null, List.of(new AudienceItem(PmtActivityAudience.SEGMENT, sg)), List.of()), "OP");
        assertThat(discount(e, y.userNo())).as("改结束时间后快照仍是发布时的").isZero();
        assertThat(discount(e, x.userNo())).isEqualTo(500);
    }

    @Test
    @DisplayName("★★★ AC-10 受众此刻一个人都没有 → 不让发布")
    void publishRejectedWhenAudienceEmpty() {
        String e = entity();
        buyer(e);
        String nobody = tag(e, "还没人");
        assertThatThrownBy(() -> cut(e, List.of(new AudienceItem(PmtActivityAudience.TAG, nobody))))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.MEMBER_AUDIENCE_EMPTY.name());
        assertThat(cut(e, List.of(new AudienceItem(PmtActivityAudience.NON_MEMBER, "*"))))
                .as("非本店会员数不出来，不判").isNotNull();
    }

    @Test
    @DisplayName("★★ 编辑一个带受众的活动、受众不变 → 保存不撞键（受众行此前是逻辑删）")
    void resaveActivityWithSameAudience() {
        String e = entity();
        Buyer b = buyer(e);
        String t = tag(e, "常来");
        tagOne(e, b.memberNo(), t);
        long later = System.currentTimeMillis() + 3_600_000L;
        ActivityVO a = activityService.save(e, new ActivityDraft(null, "未开始的 " + seq,
                "BASKET", null, PmtActivity.TRIGGER_AMOUNT, 5_000L, null,
                PmtActivity.BENEFIT_CUT, 500L, null, null,
                PmtActivity.ONE_OFF, later, later + 86400_000L, null,
                null, null, List.of(new AudienceItem(PmtActivityAudience.TAG, t)), List.of()), "OP");

        ActivityVO again = activityService.save(e, new ActivityDraft(a.activityNo(), "改个名 " + seq,
                "BASKET", null, PmtActivity.TRIGGER_AMOUNT, 5_000L, null,
                PmtActivity.BENEFIT_CUT, 500L, null, null,
                PmtActivity.ONE_OFF, later, later + 86400_000L, null,
                null, null, List.of(new AudienceItem(PmtActivityAudience.TAG, t)), List.of()), "OP");

        assertThat(again.audiences()).hasSize(1);
    }

    // ------------------------------------------------------------------ 引用与合并

    @Test
    @DisplayName("★★★ AC-16 标签用在哪列出引用它的活动与人群；合并后二者都改指目标标签，照样命中")
    void tagUsageAndMergeRetargets() {
        String e = entity();
        Buyer b = buyer(e);
        String from = tag(e, "囤货党");
        String into = tag(e, "爱囤货");
        tagOne(e, b.memberNo(), from);
        String sg = segmentService.save(e, null, "囤货的人" + seq, null, byTags(List.of(from))).segmentNo();
        ActivityVO a = cut(e, List.of(new AudienceItem(PmtActivityAudience.TAG, from)));

        TagUsageVO usage = audienceService.tagUsage(e, from);
        assertThat(usage.activities()).extracting(r -> r.refNo()).containsExactly(a.activityNo());
        assertThat(usage.segments()).extracting(s -> s.segmentNo()).containsExactly(sg);

        MergePreviewVO preview = audienceService.mergeTag(e, from, into, false, "OP");
        assertThat(preview.referencedActivities()).as("确认框要写出会改指几个活动").isEqualTo(1);
        audienceService.mergeTag(e, from, into, true, "OP");

        assertThat(discount(e, b.userNo())).as("活动受众改指到目标标签，合并后照样命中").isEqualTo(500);
        assertThat(segmentService.matchAll(e, sg)).as("人群条件改指，照样圈得到他")
                .containsExactly(b.memberNo());
    }

    // ------------------------------------------------------------------ 发券

    @Test
    @DisplayName("★★★ AC-11 发券直接选标签：发给打了标签的人，跳过线索并写明原因；批次记下受众项")
    void issueByTagSkipsLeads() {
        String e = entity();
        Buyer b = buyer(e);
        String t = tag(e, "只要土鸡蛋");
        tagOne(e, b.memberNo(), t);
        memberService.enroll(e, "1371199" + (++seq), null, List.of(t), "ST-1", "OP");
        String couponNo = couponService.save(e, new CouponSaveCmd(null, "满减券" + seq, PmtCoupon.CASH,
                500L, null, null, 0L, null, PmtCoupon.SCOPE_ALL, List.of(), null,
                PmtCoupon.RELATIVE, null, null, 7,
                PmtCoupon.ISSUE_TARGETED, PmtCoupon.REDEEM_ORDER, 1, 100, 1, null), "OP").couponNo();

        CouponIssueVO r = couponService.issue(e, couponNo, null,
                List.of(new MemberQueryPort.AudienceItem(MemberQueryPort.AudienceItem.TAG, t)), "OP");

        assertThat(r.planned()).isEqualTo(2);
        assertThat(r.issued()).isEqualTo(1);
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(couponService.issues(e, couponNo).get(0).audiences())
                .as("发放记录要说得出发给了谁 —— 只认 segmentNo 的话这一批会显示成「全部会员」")
                .containsExactly(new MemberQueryPort.AudienceItem(MemberQueryPort.AudienceItem.TAG, t));
    }
}
