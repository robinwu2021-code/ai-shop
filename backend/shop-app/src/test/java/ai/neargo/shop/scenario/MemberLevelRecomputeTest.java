package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.member.entity.MbrMember;
import ai.neargo.shop.member.entity.MbrMemberStore;
import ai.neargo.shop.member.entity.MbrReachLog;
import ai.neargo.shop.member.mapper.MemberMappers.MemberStoreMapper;
import ai.neargo.shop.member.mapper.MemberMappers.ReachLogMapper;
import ai.neargo.shop.member.service.LevelPolicy;
import ai.neargo.shop.member.service.MemberLevelService;
import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.user.service.PersonService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会员分层每日重算（PRD-会员标签与定向营销 AC-5 / AC-6 / AC-7）。
 *
 * <p><b>守的是「时间流逝本身会改变分层」</b>：此前分层只在支付成功时算，
 * 一个不再来的人永远停在最后一次的分层上。这组用例全部用「不下单、只让时间过去」来驱动 ——
 * 重算入参的 {@code now} 往后拨，而不是往库里补订单。
 *
 * <p>可证伪：把 {@code MemberLevelServiceImpl#recomputeEntity} 里的 {@code policy.levelOf(...)}
 * 换回读旧的 {@code m.getLevel()}，第一条立刻变红。
 */
@SpringBootTest
@ActiveProfiles("test")
class MemberLevelRecomputeTest {

    private static final long DAY = 86_400_000L;

    @Autowired
    private MemberService memberService;
    @Autowired
    private MemberLevelService levelService;
    @Autowired
    private PersonService personService;
    @Autowired
    private MemberStoreMapper storeMapper;
    @Autowired
    private ReachLogMapper reachLogMapper;

    private static int seq = 7100;

    /** 口径是全平台共享的一行配置：改过就要还原，否则后面跑的用例按改过的口径算、报错却指向别处 */
    @AfterEach
    void restorePolicy() {
        levelService.savePolicy(LevelPolicy.DEFAULT, "TEST");
    }

    private String person() {
        return personService.resolveOrCreateByPhone("1371100" + (++seq)).getPersonNo();
    }

    /**
     * 59 天前下过两单的常客：往后拨 3 天，不做任何事，他就该是沉睡。
     * 不是 2 天 —— 最后一单在「59 天前再过 1 分钟」，拨 2 天后闲置按整天算是 60，
     * 而口径是「超过」60 天。边界本身由 LevelPolicy 的定义决定，这里不去贴它。
     */
    private record Regular(String entity, String person, long now) {
    }

    private Regular regularLastSeen59DaysAgo() {
        String p = person();
        String e = "M-LVL-" + seq;
        long now = System.currentTimeMillis();
        long t = now - 59 * DAY;
        memberService.onOrderPaid("SUB-LV1-" + seq, "U" + seq, p, e, "ST-LV", 3000, t);
        memberService.onOrderPaid("SUB-LV2-" + seq, "U" + seq, p, e, "ST-LV", 3000, t + 60_000);
        MbrMember m = memberService.find(e, p).orElseThrow();
        assertThat(m.getLevel()).as("前置：两单是常客").isEqualTo(MbrMember.LEVEL_REGULAR);
        return new Regular(e, p, now);
    }

    @Test
    @DisplayName("★★★ 不再来的常客越过 60 天 → 重算后是沉睡（主体级与门店级都是），新变沉睡计数 +1")
    void idleRegularBecomesSleepingNextDay() {
        Regular r = regularLastSeen59DaysAgo();

        MemberLevelService.RecomputeResult res = levelService.recompute(r.now() + 3 * DAY);

        MbrMember m = memberService.find(r.entity(), r.person()).orElseThrow();
        assertThat(m.getLevel()).isEqualTo(MbrMember.LEVEL_SLEEPING);
        assertThat(m.getD90OrderCount()).as("两单都还在 90 天窗口内").isEqualTo(2);
        MbrMemberStore s = DataScopeContext.executeWithoutScope(() -> storeMapper.selectOne(
                Wrappers.<MbrMemberStore>lambdaQuery()
                        .eq(MbrMemberStore::getMemberNo, m.getMemberNo())
                        .eq(MbrMemberStore::getStoreNo, "ST-LV").last("limit 1")));
        assertThat(s.getLevel()).as("按门店经营的商家看的是这一份").isEqualTo(MbrMember.LEVEL_SLEEPING);
        assertThat(res.newlySleeping()).isGreaterThanOrEqualTo(1);
        assertThat(levelService.lastRun()).as("重算结果要留下，会员页据此写重算时间").isNotNull();
    }

    @Test
    @DisplayName("★★ 把沉睡门槛改成 90 天再重算 → 他回到常客（口径真的来自配置，不是写死的 60）")
    void raisingSleepDaysRestoresLevel() {
        Regular r = regularLastSeen59DaysAgo();
        levelService.recompute(r.now() + 3 * DAY);
        assertThat(memberService.find(r.entity(), r.person()).orElseThrow().getLevel())
                .isEqualTo(MbrMember.LEVEL_SLEEPING);

        levelService.savePolicy(new LevelPolicy(90, 6, 2), "TEST");
        levelService.recompute(r.now() + 3 * DAY);

        assertThat(memberService.find(r.entity(), r.person()).orElseThrow().getLevel())
                .isEqualTo(MbrMember.LEVEL_REGULAR);
    }

    @Test
    @DisplayName("★★ 近 90 天单数会降：两单滑出窗口后 d90 归零（此前只增不减）")
    void d90DecaysWhenOrdersLeaveWindow() {
        Regular r = regularLastSeen59DaysAgo();

        levelService.recompute(r.now() + 32 * DAY);

        MbrMember m = memberService.find(r.entity(), r.person()).orElseThrow();
        assertThat(m.getD90OrderCount()).isZero();
        assertThat(m.getLevel()).isEqualTo(MbrMember.LEVEL_SLEEPING);
    }

    @Test
    @DisplayName("★★ 重算幂等：同一时刻连跑两次，第二次这一行的 version 与 updated_at 都不动")
    void recomputeTwiceChangesNoRow() {
        Regular r = regularLastSeen59DaysAgo();
        long at = r.now() + 3 * DAY;
        levelService.recompute(at);
        MbrMember first = memberService.find(r.entity(), r.person()).orElseThrow();

        levelService.recompute(at);

        MbrMember second = memberService.find(r.entity(), r.person()).orElseThrow();
        assertThat(second.getVersion()).isEqualTo(first.getVersion());
        assertThat(second.getUpdatedAt()).isEqualTo(first.getUpdatedAt());
    }

    @Test
    @DisplayName("★★ 重算不写触达记录 —— 分层变了不等于该给这个人发消息")
    void recomputeWritesNoReachLog() {
        Regular r = regularLastSeen59DaysAgo();
        long before = countReach();

        levelService.recompute(r.now() + 3 * DAY);

        assertThat(countReach()).isEqualTo(before);
    }

    @Test
    @DisplayName("★ 口径不自洽（常客门槛不低于熟客门槛）→ 拒绝保存，不能让「常客」整片消失")
    void inconsistentPolicyRejected() {
        assertThatThrownBy(() -> levelService.savePolicy(new LevelPolicy(60, 3, 3), "TEST"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> levelService.savePolicy(new LevelPolicy(3, 6, 2), "TEST"))
                .as("沉睡门槛低于 7 天等于把每周来一次的人都算成沉睡")
                .isInstanceOf(BizException.class);
        assertThat(levelService.policy()).isEqualTo(LevelPolicy.DEFAULT);
    }

    private long countReach() {
        return DataScopeContext.executeWithoutScope(
                () -> reachLogMapper.selectCount(Wrappers.<MbrReachLog>lambdaQuery()));
    }
}
