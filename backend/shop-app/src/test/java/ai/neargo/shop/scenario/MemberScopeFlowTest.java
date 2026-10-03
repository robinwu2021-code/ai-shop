package ai.neargo.shop.scenario;

import ai.neargo.shop.member.dto.MemberVOs.MemberQuery;
import ai.neargo.shop.member.dto.MemberVOs.MemberVO;
import ai.neargo.shop.member.entity.MbrMember;
import ai.neargo.shop.member.service.MemberSegmentService;
import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.member.service.MemberTagService;
import ai.neargo.shop.user.service.PersonService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 经营口径与人群（P3）。
 *
 * <p><b>这组用例守的是「同一批订单，两种口径下答案不同」</b>——
 * 那正是多店主体开这个开关的理由：十公里外那家店的会员，对这家店没用。
 * 按主体算他是熟客，按门店算他在这家店是新客，而<b>新客券该发给后者</b>。
 * 少了这条守卫，口径开关会静静地不生效 —— 界面上开着，数字还是主体的。
 */
@SpringBootTest
@ActiveProfiles("test")
class MemberScopeFlowTest {

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberSegmentService segmentService;

    @Autowired
    private MemberTagService tagService;

    @Autowired
    private PersonService personService;

    private static int seq = 7000;

    private String phone() {
        return "1380000" + (++seq);
    }

    private String person(String phone) {
        return personService.resolveOrCreateByPhone(phone).getPersonNo();
    }

    private static MemberQuery rule(String level, List<String> tagNos) {
        return new MemberQuery(null, level, null, null, null, tagNos,
                null, null, null, null, 1, 0);
    }

    @Test
    @DisplayName("★★★ 同一批订单，按门店与按主体算出的「新客」不是同一批人")
    void newCustomerDependsOnScope() {
        String e = "M-SCOPE-" + (++seq);
        String ph = phone();
        String p = person(ph);
        long now = System.currentTimeMillis();

        // 他在南门店买了三次（够 REGULAR），在北门店只买过一次
        memberService.onOrderPaid("S-1-" + seq, "U" + seq, p, e, "ST-SOUTH", 5000, now);
        memberService.onOrderPaid("S-2-" + seq, "U" + seq, p, e, "ST-SOUTH", 5000, now);
        memberService.onOrderPaid("S-3-" + seq, "U" + seq, p, e, "ST-SOUTH", 5000, now);
        memberService.onOrderPaid("N-1-" + seq, "U" + seq, p, e, "ST-NORTH", 5000, now);

        MbrMember m = memberService.find(e, p).orElseThrow();
        assertThat(m.getOrderCount()).as("主体口径：四单").isEqualTo(4);

        // 主体口径：他是回头客，新客活动够不着他
        assertThat(memberService.stats(e, null).newCount()).isZero();

        // 门店口径：在北门店他只买过一次 —— **对这家店他就是新客**
        assertThat(memberService.stats(e, "ST-NORTH").newCount())
                .as("在北门店只买过一次，这家店的新客券应该发得到他").isEqualTo(1);
        assertThat(memberService.stats(e, "ST-SOUTH").newCount())
                .as("在南门店买过三次，不该再当新客").isZero();
    }

    @Test
    @DisplayName("★★ 列表里的数字也要跟着口径走 —— 只有分层跟着变、数字还是主体的，最难发现")
    void listNumbersFollowScope() {
        String e = "M-SCOPE-" + (++seq);
        String ph = phone();
        String p = person(ph);
        long now = System.currentTimeMillis();
        memberService.onOrderPaid("A-1-" + seq, "U" + seq, p, e, "ST-SOUTH", 3000, now);
        memberService.onOrderPaid("A-2-" + seq, "U" + seq, p, e, "ST-SOUTH", 3000, now);
        memberService.onOrderPaid("B-1-" + seq, "U" + seq, p, e, "ST-NORTH", 9900, now);

        MemberVO whole = memberService.list(e, rule(null, List.of())).records().get(0);
        assertThat(whole.orderCount()).isEqualTo(3);
        assertThat(whole.totalSpentMinor()).isEqualTo(15900);

        MemberVO north = memberService.list(e,
                new MemberQuery("ST-NORTH", null, null, null, null, List.of(),
                        null, null, null, null, 1, 20)).records().get(0);
        assertThat(north.orderCount()).as("北门店只有一单").isEqualTo(1);
        assertThat(north.totalSpentMinor()).isEqualTo(9900);
    }

    @Test
    @DisplayName("★★ 切换口径不丢数据：切过去再切回来，两边的数字都还在")
    void switchingScopeKeepsBothSides() {
        String e = "M-SCOPE-" + (++seq);
        String ph = phone();
        String p = person(ph);
        memberService.onOrderPaid("C-1-" + seq, "U" + seq, p, e, "ST-SOUTH", 4200,
                System.currentTimeMillis());

        assertThat(memberService.settings(e).memberScope()).as("默认按主体").isEqualTo("ENTITY");
        memberService.saveSettings(e, "STORE", null);
        assertThat(memberService.settings(e).memberScope()).isEqualTo("STORE");
        memberService.saveSettings(e, "ENTITY", null);

        // 来回切一趟之后，主体与门店两份数字都还在 —— 口径只改展示，不改存储
        assertThat(memberService.find(e, p).orElseThrow().getOrderCount()).isEqualTo(1);
        assertThat(memberService.list(e,
                new MemberQuery("ST-SOUTH", null, null, null, null, List.of(),
                        null, null, null, null, 1, 20)).records().get(0).orderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 人群存的是条件不是名单：他买了一单之后，同一个人群就不再命中他")
    void segmentStoresRuleNotRoster() {
        String e = "M-SCOPE-" + (++seq);
        String ph = phone();
        String p = person(ph);

        // 先手工入会（还没下过单 = NEW）
        memberService.enroll(e, ph, null, List.of(), "ST-SOUTH", "OP-TEST");
        String segNo = segmentService.save(e, null, "新客", null, rule(MbrMember.LEVEL_NEW, List.of()))
                .segmentNo();
        // enroll 出来的是**线索**（本人还没绑账号）—— 不可触达，因此 resolve 拿不到，
        // 但 preview 的 count 要如实报出「条件命中 1 人」
        assertThat(segmentService.preview(e, null, rule(MbrMember.LEVEL_NEW, List.of())).count())
                .isEqualTo(1);
        assertThat(segmentService.resolve(e, segNo))
                .as("线索会员不进受众：录入手机号不等于拿到推送许可").isEmpty();

        // 他连买三单变成回头客 —— 同一个人群此刻就不该再命中他
        long now = System.currentTimeMillis();
        memberService.onOrderPaid("D-1-" + seq, "U" + seq, p, e, "ST-SOUTH", 1000, now);
        memberService.onOrderPaid("D-2-" + seq, "U" + seq, p, e, "ST-SOUTH", 1000, now);
        memberService.onOrderPaid("D-3-" + seq, "U" + seq, p, e, "ST-SOUTH", 1000, now);

        assertThat(segmentService.preview(e, null, rule(MbrMember.LEVEL_NEW, List.of())).count())
                .as("存名单的话这里还会命中他，商家会照着过期名单发新客券").isZero();
    }

    @Test
    @DisplayName("★★ 多标签取交集：点第二个标签是收窄，不能反而变多")
    void multipleTagsIntersect() {
        String e = "M-SCOPE-" + (++seq);
        String pa = phone();
        String pb = phone();
        String a = person(pa);
        String b = person(pb);
        memberService.enroll(e, pa, null, List.of(), "ST-SOUTH", "OP-TEST");
        memberService.enroll(e, pb, null, List.of(), "ST-SOUTH", "OP-TEST");
        String ma = memberService.find(e, a).orElseThrow().getMemberNo();
        String mb = memberService.find(e, b).orElseThrow().getMemberNo();

        String t1 = tagService.create(e, "爱买肉" + seq, "OP-TEST").tagNo();
        String t2 = tagService.create(e, "只在周末来" + seq, "OP-TEST").tagNo();
        tagService.tag(e, List.of(ma, mb), List.of(t1), List.of(), "OP-TEST");
        tagService.tag(e, List.of(ma), List.of(t2), List.of(), "OP-TEST");

        assertThat(segmentService.preview(e, null, rule(null, List.of(t1))).count()).isEqualTo(2);
        assertThat(segmentService.preview(e, null, rule(null, List.of(t1, t2))).count())
                .as("两个标签都要满足，只有一个人").isEqualTo(1);
    }
}
