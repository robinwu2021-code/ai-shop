package ai.neargo.shop.scenario;

import ai.neargo.shop.member.dto.MemberVOs.MemberQuery;
import ai.neargo.shop.member.entity.MbrReachLog;
import ai.neargo.shop.member.mapper.MemberMappers.ReachLogMapper;
import ai.neargo.shop.member.service.MemberReachService;
import ai.neargo.shop.member.service.MemberSegmentService;
import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.user.service.PersonService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 给会员发消息（P7）。
 *
 * <p><b>这是整条线上唯一会打扰真实用户的功能</b>，所以这组用例守的全是
 * 「宁可少发」的那一侧：频次闸拦住第二条、退订之后不再发、
 * <b>线索一律不发</b>（商家录进来的号，本人从没同意过接收任何东西）。
 *
 * <p>还有一条同样重要：**跳过要报数，不能静默**。商家选了 30 个人实发 8 个，
 * 只说「发送成功」的话，他会以为 30 个人都收到了。
 */
@SpringBootTest
@ActiveProfiles("test")
class MemberReachFlowTest {

    @Autowired
    private MemberReachService reachService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberSegmentService segmentService;

    @Autowired
    private PersonService personService;

    @Autowired
    private ReachLogMapper reachMapper;

    private static int seq = 9100;

    /** 已注册、已入会（可触达） */
    private String registeredMember(String entityNo) {
        String phone = "1340000" + (++seq);
        String userNo = "U-RCH-" + seq;
        String personNo = personService.resolveOrCreateByPhone(phone).getPersonNo();
        personService.bindOnLogin(userNo, phone);
        memberService.onOrderPaid("SUB-RCH-" + seq, userNo, personNo, entityNo, "ST-1",
                5_000, System.currentTimeMillis());
        return memberService.find(entityNo, personNo).orElseThrow().getMemberNo();
    }

    private String allSegment(String entityNo) {
        return segmentService.save(entityNo, null, "全部" + (++seq), null,
                new MemberQuery(null, null, null, null, null, List.of(),
                        null, null, null, null, 1, 0)).segmentNo();
    }

    @Test
    @DisplayName("★★★ 频次闸：同一场景发过一次之后，第二条当场被拦")
    void secondMessageWithinWindowIsBlocked() {
        String e = "M-RCH-" + (++seq);
        registeredMember(e);
        String seg = allSegment(e);

        var first = reachService.send(e, seg, MbrReachLog.SCENE_WAKEUP, "回来看看", "上新了", "OP");
        assertThat(first.sent()).isEqualTo(1);

        var plan = reachService.plan(e, seg, MbrReachLog.SCENE_WAKEUP);
        assertThat(plan.reachable()).as("14 天内不该再唤回同一个人").isZero();
        assertThat(plan.skips()).extracting(x -> x.reason()).containsExactly("TOO_SOON");
    }

    @Test
    @DisplayName("★★ 频次闸按场景分档：唤回发过了，公告仍然发得出去")
    void gateIsPerScene() {
        String e = "M-RCH-" + (++seq);
        registeredMember(e);
        String seg = allSegment(e);

        reachService.send(e, seg, MbrReachLog.SCENE_WAKEUP, "回来看看", "上新了", "OP");

        // 一周三条公告让人烦，一周唤回三次让人拉黑 —— 两者不是一回事，不能共用一档
        assertThat(reachService.plan(e, seg, MbrReachLog.SCENE_NOTICE).reachable()).isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 线索一律不发 —— 商家录进来的号，本人从没同意过接收任何东西")
    void leadsAreNeverReached() {
        String e = "M-RCH-" + (++seq);
        String phone = "1340001" + (++seq);
        memberService.enroll(e, phone, "老熟人", List.of(), "ST-1", "OP");
        String seg = allSegment(e);

        var plan = reachService.plan(e, seg, MbrReachLog.SCENE_NOTICE);
        assertThat(plan.matched()).as("他在人群里").isEqualTo(1);
        assertThat(plan.reachable()).as("但一条也发不出去").isZero();
        assertThat(plan.skips()).extracting(x -> x.reason()).containsExactly("LEAD");
    }

    @Test
    @DisplayName("★★★ 退订之后不再发；跳过原因要能说出人话")
    void optOutIsRespected() {
        String e = "M-RCH-" + (++seq);
        String memberNo = registeredMember(e);
        String seg = allSegment(e);
        assertThat(reachService.plan(e, seg, MbrReachLog.SCENE_NOTICE).reachable()).isEqualTo(1);

        memberService.setReachOptOut(e, memberNo, true);

        var plan = reachService.plan(e, seg, MbrReachLog.SCENE_NOTICE);
        assertThat(plan.reachable()).isZero();
        assertThat(plan.skips()).extracting(x -> x.reason()).containsExactly("OPT_OUT");
    }

    @Test
    @DisplayName("★★★ 跳过要报数不静默：三个人里两个发不出去，界面上要看得见为什么")
    void skipsAreCountedByReason() {
        String e = "M-RCH-" + (++seq);
        registeredMember(e);                                   // 能发
        String optOut = registeredMember(e);
        memberService.setReachOptOut(e, optOut, true);          // 退订
        memberService.enroll(e, "1340002" + (++seq), null, List.of(), "ST-1", "OP");  // 线索
        String seg = allSegment(e);

        var plan = reachService.plan(e, seg, MbrReachLog.SCENE_NOTICE);
        assertThat(plan.matched()).isEqualTo(3);
        assertThat(plan.reachable()).isEqualTo(1);
        assertThat(plan.skips()).extracting(x -> x.reason())
                .containsExactlyInAnyOrder("OPT_OUT", "LEAD");
    }

    @Test
    @DisplayName("★★ 发出去的每一条都留痕：频次闸靠它，效果也靠它")
    void everySendLeavesATrail() {
        String e = "M-RCH-" + (++seq);
        String memberNo = registeredMember(e);
        String seg = allSegment(e);

        var r = reachService.send(e, seg, MbrReachLog.SCENE_COUPON, "给你一张券", "满 30 减 5", "OP");

        List<MbrReachLog> rows = reachMapper.selectList(Wrappers.<MbrReachLog>lambdaQuery()
                .eq(MbrReachLog::getTaskNo, r.taskNo()));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getMemberNo()).isEqualTo(memberNo);
        assertThat(rows.get(0).getScene()).isEqualTo(MbrReachLog.SCENE_COUPON);
        assertThat(rows.get(0).getOrderedAt())
                .as("效果只认「收到后 7 天内下过单」，发的时候当然是空的").isNull();
    }
}
