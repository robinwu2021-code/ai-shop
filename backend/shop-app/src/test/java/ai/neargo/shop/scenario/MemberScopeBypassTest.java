package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.member.entity.MbrMember;
import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.user.service.PersonService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入会发生在<b>买家自己的会话里</b>，而会员表是按商家登记数据域的（P4 收尾）。
 *
 * <p>数据域是 fail-closed：会话维度在表的锚点里找不到列时，handler 拼的是
 * {@code 1=0} 而不是放行。所以「买家付钱 → 入会」这条链路必须显式绕开数据域，
 * 否则 <b>select 查不到、update 影响 0 行</b>，而<b>接口成功、日志干净、订单一切正常</b>。
 *
 * <p>这一类错的可怕之处在于它不吵：商家要到两周后才会发现
 * 「买了这么多人，怎么会员只有几个」，而那时已经没有任何线索指向数据域。
 *
 * <p>所以这条用例**必须带着一个真实的 SELF 会话跑** —— 不带的话
 * {@code DataScopeContext.current()} 是 null，handler 直接放行，
 * 它就退化成一条什么也没守住的用例（而且照样是绿的）。
 */
@SpringBootTest
@ActiveProfiles("test")
class MemberScopeBypassTest {

    @Autowired
    private MemberService memberService;

    @Autowired
    private PersonService personService;

    private static int seq = 8100;

    @AfterEach
    void clearScope() {
        DataScopeContext.clear();
    }

    @Test
    @DisplayName("★★★ 买家自己的会话（SELF）里付钱：会员照样建得出来")
    void enrollWorksInsideBuyerSession() {
        String phone = "1370001" + (++seq);
        String userNo = "U-SCOPE-" + seq;
        String entityNo = "M-SCOPE-" + seq;
        String personNo = personService.resolveOrCreateByPhone(phone).getPersonNo();

        // 真实链路里这一刻的会话就是买家自己：维度 SELF，而 mbr_* 只按 entity_no 登记
        DataScopeContext.set(DataScopeSpec.of("SELF", Set.of(userNo)));
        memberService.onOrderPaid("SUB-SCOPE-" + seq, userNo, personNo, entityNo,
                "ST-1", 6_600, System.currentTimeMillis());
        DataScopeContext.clear();

        MbrMember m = memberService.find(entityNo, personNo).orElse(null);
        assertThat(m).as("入会必须真的发生 —— 静默不发生时订单与日志都正常，没有任何线索").isNotNull();
        assertThat(m.getOrderCount()).isEqualTo(1);
        assertThat(m.getTotalSpentMinor()).isEqualTo(6_600);
    }

    @Test
    @DisplayName("★★★ 同一个买家会话里重复回调：仍然只算一次（幂等判断也要读得到旧行）")
    void idempotencyStillHoldsInsideBuyerSession() {
        String phone = "1370002" + (++seq);
        String userNo = "U-SCOPE-" + seq;
        String entityNo = "M-SCOPE-" + seq;
        String personNo = personService.resolveOrCreateByPhone(phone).getPersonNo();
        String sub = "SUB-DUP-" + seq;

        DataScopeContext.set(DataScopeSpec.of("SELF", Set.of(userNo)));
        memberService.onOrderPaid(sub, userNo, personNo, entityNo, "ST-1", 5_000,
                System.currentTimeMillis());
        memberService.onOrderPaid(sub, userNo, personNo, entityNo, "ST-1", 5_000,
                System.currentTimeMillis());
        DataScopeContext.clear();

        MbrMember m = memberService.find(entityNo, personNo).orElseThrow();
        /*
         * 幂等靠的是「这张子订单记过没有」，那是一次 **select**。
         * 数据域挡住它的话查不到旧行，于是每次回调都当成新的 —— 数字翻倍，
         * 而这正是「不报错的错」最典型的一种。
         */
        assertThat(m.getOrderCount()).as("同一张子订单只算一次").isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 登录后线索转正也在买家会话里发生")
    void claimWorksInsideBuyerSession() {
        String phone = "1370003" + (++seq);
        String userNo = "U-SCOPE-" + seq;
        String entityNo = "M-SCOPE-" + seq;

        // 商家先手工录了这个号（线索）
        memberService.enroll(entityNo, phone, "老熟人", java.util.List.of(), "ST-1", "OP");
        String personNo = personService.resolveOrCreateByPhone(phone).getPersonNo();
        assertThat(memberService.find(entityNo, personNo).orElseThrow().getStatus())
                .isEqualTo(MbrMember.LEAD);

        /*
         * 他自己注册登录：会话是 SELF。转正由 `bindOnLogin` 里的钩子触发
         * （PersonServiceImpl → MemberEventPort.onPersonBound），
         * 所以这里不再手工调 claimByPerson —— 手工调的话，钩子已经转正过，
         * 返回 0 反而看不出链路通没通。**要断言的是结果，不是返回值。**
         */
        DataScopeContext.set(DataScopeSpec.of("SELF", Set.of(userNo)));
        personService.bindOnLogin(userNo, phone);
        DataScopeContext.clear();

        assertThat(memberService.find(entityNo, personNo).orElseThrow().getStatus())
                .as("一次绑定，线索会员就该转正；数据域挡住这次 update 的话它会一直是 LEAD")
                .isEqualTo(MbrMember.ACTIVE);
    }
}
