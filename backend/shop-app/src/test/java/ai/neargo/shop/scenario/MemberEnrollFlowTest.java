package ai.neargo.shop.scenario;

import ai.neargo.shop.member.entity.MbrMember;
import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.user.service.PersonService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入会与分层（P1）。
 *
 * <p><b>这组用例守的是三条规则</b>：支付回调重发不能重复计数、没绑手机号的人不入会
 * 但交易照常、分层先判沉睡再判活跃。前两条都是「不报错的错」——
 * 少了它们，商家看到的数字会慢慢偏，而没有任何东西会响。
 */
@SpringBootTest
@ActiveProfiles("test")
class MemberEnrollFlowTest {

    @Autowired
    private MemberService memberService;

    @Autowired
    private PersonService personService;

    @Autowired
    private ai.neargo.shop.marketing.attribution.AttributionService attributionService;

    private static int seq = 5000;

    private String person() {
        return personService.resolveOrCreateByPhone("1370000" + (++seq)).getPersonNo();
    }

    private static String entity() {
        return "M-MBR-" + seq;
    }

    @Test
    @DisplayName("★★ 支付成功即入会：首单时间、来源、门店都记下来")
    void paidOrderCreatesMember() {
        String p = person();
        String e = entity();
        long now = System.currentTimeMillis();

        memberService.onOrderPaid("SUB-A" + seq, "U" + seq, p, e, "ST-1", 6800, now);

        MbrMember m = memberService.find(e, p).orElseThrow();
        assertThat(m.getSource()).isEqualTo(MbrMember.SOURCE_ORDER);
        assertThat(m.getFirstStoreNo()).isEqualTo("ST-1");
        assertThat(m.getOrderCount()).isEqualTo(1);
        assertThat(m.getTotalSpentMinor()).isEqualTo(6800);
        assertThat(m.getFirstOrderAt()).isEqualTo(now);
        assertThat(m.getLevel()).isEqualTo(MbrMember.LEVEL_NEW);
    }

    /**
     * <b>扫码进来的人，来源要记 SCAN 而不是 ORDER。</b>
     *
     * <p>{@code SOURCE_SCAN} 这个常量声明了却<b>从没有人写过</b>：所有下单入会的人
     * 一律记 ORDER，于是「扫了店门口那张贴纸才来的」在会员档案里查不到 ——
     * 那批物料的效果永远是 0，而商家据此判断还要不要继续印。
     *
     * <p>可证伪：把 {@code byScan} 那一段去掉，来源退回 ORDER，第一个断言立刻变红。
     */
    @Test
    @DisplayName("★ 扫码归因过的人下单入会 → 来源是 SCAN；下单那行仍在明细里")
    void scannedMemberIsSourcedAsScanNotOrder() {
        String p = person();
        String e = entity();
        String u = "U-SCAN-" + seq;
        long now = System.currentTimeMillis();

        // 先扫码：归因落到这家店（来源 STORE_CODE）
        attributionService.report(u,
                new ai.neargo.shop.marketing.attribution.AttributionService.Clue(e, null, null));

        memberService.onOrderPaid("SUB-SCAN" + seq, u, p, e, "ST-1", 3300, now);

        MbrMember m = memberService.find(e, p).orElseThrow();
        assertThat(m.getSource())
                .as("扫码进来的人记成「下单来的」—— 贴纸的效果永远算不出来")
                .isEqualTo(MbrMember.SOURCE_SCAN);
        // 下单这件事没有丢：明细是事件流水，两件事各一行
        assertThat(m.getOrderCount()).isEqualTo(1);
        assertThat(m.getTotalSpentMinor()).isEqualTo(3300);
    }

    /** 没扫过码的人照旧记 ORDER —— 别把所有人都标成扫码来的。 */
    @Test
    @DisplayName("★ 没归因过的人下单入会仍是 ORDER —— 判据要能分开两种人")
    void unattributedMemberStaysOrderSourced() {
        String p = person();
        String e = entity();
        long now = System.currentTimeMillis();

        memberService.onOrderPaid("SUB-NOSCAN" + seq, "U-NOSCAN-" + seq, p, e, "ST-1", 2200, now);

        assertThat(memberService.find(e, p).orElseThrow().getSource())
                .as("没扫过码的也标成 SCAN —— 那这个字段就不再有区分力了")
                .isEqualTo(MbrMember.SOURCE_ORDER);
    }

    @Test
    @DisplayName("★★ 支付回调重发不能重复计数 —— 这是「不报错的错」，只会让数字慢慢偏")
    void payCallbackIsIdempotent() {
        String p = person();
        String e = entity();
        long now = System.currentTimeMillis();
        String sub = "SUB-B" + seq;

        memberService.onOrderPaid(sub, "U" + seq, p, e, "ST-1", 5000, now);
        memberService.onOrderPaid(sub, "U" + seq, p, e, "ST-1", 5000, now);

        MbrMember m = memberService.find(e, p).orElseThrow();
        assertThat(m.getOrderCount()).as("同一张子订单只算一次").isEqualTo(1);
        assertThat(m.getTotalSpentMinor()).isEqualTo(5000);
    }

    @Test
    @DisplayName("★★ 没绑手机号的人不入会，但**交易照常** —— 会员是准入规则，不是校验")
    void buyerWithoutPhoneIsNotEnrolled() {
        String e = entity();
        // personNo 为空 = 微信登录没授权手机号。这里不抛、不建关系，静静跳过
        memberService.onOrderPaid("SUB-C" + seq, "U-noPhone", null, e, "ST-1", 9900,
                System.currentTimeMillis());

        assertThat(memberService.stats(e, null).newCount()).isZero();
    }

    @Test
    @DisplayName("★ 同一个人在两家店买 —— 一条会员关系，两条门店往来")
    void twoStoresOneMember() {
        String p = person();
        String e = entity();
        long now = System.currentTimeMillis();

        memberService.onOrderPaid("SUB-D1" + seq, "U" + seq, p, e, "ST-1", 3000, now);
        memberService.onOrderPaid("SUB-D2" + seq, "U" + seq, p, e, "ST-2", 4000, now);

        MbrMember m = memberService.find(e, p).orElseThrow();
        assertThat(m.getOrderCount()).isEqualTo(2);
        assertThat(m.getTotalSpentMinor()).isEqualTo(7000);

        var detail = memberService.detail(e, m.getMemberNo()).orElseThrow();
        assertThat(detail.stores()).hasSize(2);
        assertThat(detail.stores()).anySatisfy(s -> {
            if (s.storeNo().equals("ST-1")) {
                assertThat(s.isFirstStore()).as("他是从 ST-1 进来的").isTrue();
            }
        });
    }

    @Test
    @DisplayName("★★ 分层**先判沉睡**：曾经的熟客三个月没来，商家该看到「沉睡」而不是「熟客」")
    void sleepingBeatsLoyal() {
        String p = person();
        String e = entity();
        long longAgo = System.currentTimeMillis() - 100L * 86_400_000L;

        for (int i = 0; i < 8; i++) {
            memberService.onOrderPaid("SUB-E" + seq + "-" + i, "U" + seq, p, e, "ST-1",
                    2000, longAgo);
        }

        MbrMember m = memberService.find(e, p).orElseThrow();
        assertThat(m.getOrderCount()).isEqualTo(8);
        assertThat(m.getD90OrderCount()).as("100 天前的单不算进近 90 天").isZero();
        assertThat(m.getLevel()).isEqualTo(MbrMember.LEVEL_SLEEPING);
    }

    @Test
    @DisplayName("★ 主动加入：没有人档时抛 MEMBER_PHONE_REQUIRED —— 端上据此弹授权，不是报错")
    void joinRequiresPhone() {
        String e = entity();
        assertThatThrownBy(() -> memberService.join(e, null, "ST-1"))
                .isInstanceOf(ai.neargo.shop.common.BizException.class)
                .satisfies(ex -> assertThat(((ai.neargo.shop.common.BizException) ex).errorCode())
                        .isEqualTo(ai.neargo.shop.common.ErrorCode.MEMBER_PHONE_REQUIRED));

        String p = person();
        MbrMember m = memberService.join(e, p, "ST-1");
        assertThat(m.getSource()).isEqualTo(MbrMember.SOURCE_SEARCH);
        // 再点一次「加入」是常态，不该报错
        assertThat(memberService.join(e, p, "ST-1").getMemberNo()).isEqualTo(m.getMemberNo());
    }

    @Test
    @DisplayName("★★ 按手机号查会员**必须给完整号** —— 前缀模糊会把会员库变成通讯录")
    void phoneLookupNeedsFullNumber() {
        String phone = "1370000" + (++seq);
        String p = personService.resolveOrCreateByPhone(phone).getPersonNo();
        String e = entity();
        memberService.onOrderPaid("SUB-F" + seq, "U" + seq, p, e, "ST-1", 1000,
                System.currentTimeMillis());

        var hit = memberService.list(e, new ai.neargo.shop.member.dto.MemberVOs.MemberQuery(
                null, null, null, null, phone, java.util.List.of(), null, null, null, null, 1, 20));
        assertThat(hit.records()).hasSize(1);

        var prefix = memberService.list(e, new ai.neargo.shop.member.dto.MemberVOs.MemberQuery(
                null, null, null, null, phone.substring(0, 6), java.util.List.of(),
                null, null, null, null, 1, 20));
        assertThat(prefix.records()).as("前缀查不到人").isEmpty();
    }
}
