package ai.neargo.shop.scenario;

import ai.neargo.shop.member.dto.MemberVOs.MemberQuery;
import ai.neargo.shop.member.entity.MbrReachLog;
import ai.neargo.shop.member.mapper.MemberMappers.ReachLogMapper;
import ai.neargo.shop.member.service.MemberAudienceService;
import ai.neargo.shop.member.service.MemberReachService;
import ai.neargo.shop.member.service.MemberReachService.ReachTaskVO;
import ai.neargo.shop.member.service.MemberSegmentService;
import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.member.service.MemberTagService;
import ai.neargo.shop.member.service.OpsMemberService;
import ai.neargo.shop.member.mapper.MemberMappers.MemberMapper;
import ai.neargo.shop.member.mapper.MemberMappers.ReachTaskMapper;
import ai.neargo.shop.member.service.impl.AudienceResolver;
import ai.neargo.shop.member.service.impl.MemberReachServiceImpl;
import ai.neargo.shop.member.service.impl.ReachAttribution;
import ai.neargo.shop.spi.notify.UserPushPort;
import ai.neargo.shop.spi.user.PersonPort;
import ai.neargo.shop.spi.member.MemberQueryPort.AudienceItem;
import ai.neargo.shop.user.service.PersonService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 触达效果回看（TDD-会员标签与定向营销 批 C · AC-12 … AC-15、AC-17）。
 *
 * <p>守的是「数字是真的」：来了要是本人来的、成单只算窗口内最近那一次的第一单、
 * 重复进店与重复回调不多算。效果页的数字一旦虚高，商家会照着它继续发 ——
 * 那是这条线上唯一会打扰真实用户的动作。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReachEffectFlowTest {

    private static final long DAY = 24L * 3600_000;

    @Autowired private MemberReachService reachService;
    @Autowired private MemberService memberService;
    @Autowired private MemberSegmentService segmentService;
    @Autowired private MemberAudienceService audienceService;
    @Autowired private MemberTagService tagService;
    @Autowired private OpsMemberService opsMemberService;
    @Autowired private PersonService personService;
    @Autowired private ReachLogMapper reachMapper;
    @Autowired private ReachTaskMapper taskMapper;
    @Autowired private MemberMapper memberMapper;
    @Autowired private AudienceResolver resolver;
    @Autowired private PersonPort personPort;
    @Autowired private ReachAttribution attribution;
    @Autowired private ai.neargo.shop.message.notify.PushTokenBinder tokenBinder;
    @Autowired private ai.neargo.shop.spi.notify.UserInboxPort inboxPort;
    @Autowired private ObjectMapper json;

    private static int seq = 9500;

    private record Buyer(String memberNo, String userNo, String personNo) {
    }

    /** 已注册、下过一单（可触达） */
    private Buyer buyer(String entityNo) {
        String phone = "1350000" + (++seq);
        String userNo = "U-EFF-" + seq;
        String personNo = personService.resolveOrCreateByPhone(phone).getPersonNo();
        personService.bindOnLogin(userNo, phone);
        tokenBinder.register("USER", userNo, "APP_ANDROID", "GETUI", "cid-eff-" + seq);
        memberService.onOrderPaid("SUB-EFF-" + seq, userNo, personNo, entityNo, "ST-1",
                5_000, System.currentTimeMillis() - 30 * DAY);
        return new Buyer(memberService.find(entityNo, personNo).orElseThrow().getMemberNo(), userNo, personNo);
    }

    private List<Buyer> buyers(String entityNo, int n) {
        List<Buyer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(buyer(entityNo));
        }
        return out;
    }

    private static final List<AudienceItem> ALL = List.of(new AudienceItem(AudienceItem.ALL, "*"));

    private String send(String entityNo, String scene) {
        return reachService.send(entityNo, ALL, "全部会员", scene, "中秋新米到了", "来店里看看", "OP").taskNo();
    }

    private String reachNoOf(String taskNo, String memberNo) {
        return reachMapper.selectOne(Wrappers.<MbrReachLog>lambdaQuery()
                .eq(MbrReachLog::getTaskNo, taskNo).eq(MbrReachLog::getMemberNo, memberNo)).getReachNo();
    }

    private void pay(String entityNo, Buyer b, long amount, long paidAt) {
        memberService.onOrderPaid("SUB-EFF-P" + (++seq), b.userNo(), b.personNo(), entityNo, "ST-1",
                amount, paidAt);
    }

    private ReachTaskVO task(String entityNo, String taskNo) {
        return reachService.task(entityNo, taskNo).orElseThrow();
    }

    @Test
    @DisplayName("★★★ AC-12 发 5 人、3 人进店、2 人下单 → 5 / 3 / 2；重复进店、重复回调不多算")
    void sentOpenedOrderedCounts() {
        String e = "M-EFF-" + (++seq);
        List<Buyer> bs = buyers(e, 5);
        String taskNo = send(e, MbrReachLog.SCENE_NOTICE);
        long now = System.currentTimeMillis();

        for (int i = 0; i < 3; i++) {
            assertThat(reachService.opened(reachNoOf(taskNo, bs.get(i).memberNo()), bs.get(i).userNo())).isTrue();
        }
        // 同一个人再点一次推送：不重复计
        assertThat(reachService.opened(reachNoOf(taskNo, bs.get(0).memberNo()), bs.get(0).userNo())).isFalse();

        pay(e, bs.get(0), 6_200, now + 3600_000);
        pay(e, bs.get(1), 8_800, now + 7200_000);
        // 同一个人第二单：只记触达后的第一单
        pay(e, bs.get(1), 1_000, now + 9000_000);

        ReachTaskVO t = task(e, taskNo);
        assertThat(t.sent()).isEqualTo(5);
        assertThat(t.opened()).isEqualTo(3);
        assertThat(t.ordered()).isEqualTo(2);
        assertThat(t.orderedAmountMinor()).isEqualTo(15_000);
        assertThat(t.orderedMembers()).extracting(m -> m.memberNo())
                .containsExactlyInAnyOrder(bs.get(0).memberNo(), bs.get(1).memberNo());
        assertThat(t.notOpened()).isEqualTo(2);
        assertThat(t.audienceDesc()).isEqualTo("全部会员");
        assertThat(t.settled()).as("窗口还没关").isFalse();

        assertThat(reachService.tasks(e, 1, 20)).extracting(ReachTaskVO::taskNo).containsExactly(taskNo);
    }

    @Test
    @DisplayName("★★★ 推送点开落在这家店、带着自己那一条的号（此前写死平台首页，回写无从谈起）")
    void pushLinkCarriesStoreAndReachNo() {
        String e = "M-EFF-" + (++seq);
        Buyer b = buyer(e);
        /*
         * 推送通道不留链接的痕（只记设备号与结果），所以这里用真 Service + 真 Mapper，
         * 只把最末端的推送换成一个记下参数的替身 —— 验的是 send 交给通道的那一串。
         */
        List<String[]> pushed = new ArrayList<>();
        UserPushPort capture = new UserPushPort() {
            @Override
            public boolean pushToUser(String userNo, String title, String body, String link) {
                return pushed.add(new String[] {userNo, link});
            }

            @Override
            public java.util.Set<String> withDevice(java.util.Collection<String> userNos) {
                return new java.util.HashSet<>(userNos);
            }
        };
        MemberReachService svc = new MemberReachServiceImpl(reachMapper, taskMapper, memberMapper, resolver,
                capture, personPort, attribution, json, inboxPort);
        String taskNo = svc.send(e, ALL, "全部会员", MbrReachLog.SCENE_NOTICE, "中秋新米到了", "来", "OP").taskNo();
        String reachNo = reachNoOf(taskNo, b.memberNo());

        assertThat(pushed).hasSize(1);
        assertThat(pushed.get(0)[0]).isEqualTo(b.userNo());
        assertThat(pushed.get(0)[1]).isEqualTo("/pages/store/index?merchantNo=" + e + "&reach=" + reachNo);
    }

    @Test
    @DisplayName("★★★ 伪造：拿别人的 reachNo 来点，不计入")
    void forgedReachNoIsNotCounted() {
        String e = "M-EFF-" + (++seq);
        Buyer victim = buyer(e);
        Buyer other = buyer(e);
        String taskNo = send(e, MbrReachLog.SCENE_NOTICE);

        assertThat(reachService.opened(reachNoOf(taskNo, victim.memberNo()), other.userNo())).isFalse();
        assertThat(reachService.opened(reachNoOf(taskNo, victim.memberNo()), "U-NOBODY")).isFalse();
        assertThat(reachService.opened("RC-NOT-EXIST", victim.userNo())).isFalse();
        assertThat(task(e, taskNo).opened()).isZero();
    }

    @Test
    @DisplayName("★★ AC-13 第 8 天下单不计")
    void orderOnDayEightNotAttributed() {
        String e = "M-EFF-" + (++seq);
        Buyer b = buyer(e);
        String taskNo = send(e, MbrReachLog.SCENE_NOTICE);

        pay(e, b, 5_000, System.currentTimeMillis() + 8 * DAY);

        assertThat(task(e, taskNo).ordered()).isZero();
    }

    @Test
    @DisplayName("★★ AC-14 两次触达后下单，只归最近一次")
    void orderAttributedToLatestReachOnly() {
        String e = "M-EFF-" + (++seq);
        Buyer b = buyer(e);
        String older = send(e, MbrReachLog.SCENE_NOTICE);
        // 场景不同才过得了频次闸；把第一次挪到两天前，时间先后才分得清
        reachMapper.update(null, Wrappers.<MbrReachLog>lambdaUpdate()
                .set(MbrReachLog::getSentAt, System.currentTimeMillis() - 2 * DAY)
                .eq(MbrReachLog::getTaskNo, older));
        String latest = send(e, MbrReachLog.SCENE_WAKEUP);

        pay(e, b, 5_000, System.currentTimeMillis() + 3600_000);

        assertThat(task(e, latest).ordered()).isEqualTo(1);
        assertThat(task(e, older).ordered()).isZero();
    }

    @Test
    @DisplayName("★★ AC-17 没来的存人群、下单的打标签 —— 从效果页回到起点")
    void saveNotOpenedAsSegment() {
        String e = "M-EFF-" + (++seq);
        List<Buyer> bs = buyers(e, 3);
        String taskNo = send(e, MbrReachLog.SCENE_NOTICE);
        reachService.opened(reachNoOf(taskNo, bs.get(0).memberNo()), bs.get(0).userNo());
        pay(e, bs.get(0), 5_000, System.currentTimeMillis() + 3600_000);

        MemberQuery notOpened = new MemberQuery(null, null, null, null, null, List.of(),
                null, null, null, null, 1, 0, taskNo, MemberQuery.REACH_NOT_OPENED);
        var seg = segmentService.save(e, null, "中秋没来的", null, notOpened);
        assertThat(memberService.match(e, seg.rule()))
                .containsExactlyInAnyOrder(bs.get(1).memberNo(), bs.get(2).memberNo());
        assertThat(seg.rule().reachTaskNo()).as("存进人群的条件带着批次号").isEqualTo(taskNo);

        String tagNo = tagService.create(e, "中秋下单" + seq, "OP").tagNo();
        MemberQuery ordered = new MemberQuery(null, null, null, null, null, List.of(),
                null, null, null, null, 1, 0, taskNo, MemberQuery.REACH_ORDERED);
        var r = audienceService.batchTag(e, null, ordered, null, tagNo, true, true, "OP");
        assertThat(r.matched()).isEqualTo(1);
        assertThat(memberService.match(e, new MemberQuery(null, null, null, null, null, List.of(tagNo),
                null, null, null, null, 1, 0))).containsExactly(bs.get(0).memberNo());
    }

    @Test
    @DisplayName("★★ 会员详情带最近一次触达（m04）：发过、来了、下了单")
    void memberDetailShowsLastReach() {
        String e = "M-EFF-" + (++seq);
        Buyer b = buyer(e);
        String taskNo = send(e, MbrReachLog.SCENE_WAKEUP);
        reachService.opened(reachNoOf(taskNo, b.memberNo()), b.userNo());

        var last = memberService.detail(e, b.memberNo()).orElseThrow().lastReach();
        assertThat(last.taskNo()).isEqualTo(taskNo);
        assertThat(last.scene()).isEqualTo(MbrReachLog.SCENE_WAKEUP);
        assertThat(last.openedAt()).isNotNull();
        assertThat(last.orderedAt()).isNull();
    }

    @Test
    @DisplayName("★★★ AC-15 运营端只看计数：触达次数与跳过率有，标签名与人群条件没有")
    void statsExposeCountsNotTagNames() throws Exception {
        String e = "M-EFF-" + (++seq);
        Buyer b = buyer(e);
        String secretTag = "机密标签" + seq;
        String tagNo = tagService.create(e, secretTag, "OP").tagNo();
        audienceService.batchTag(e, List.of(b.memberNo()), null, null, tagNo, true, true, "OP");
        segmentService.save(e, null, "机密人群" + seq, null, new MemberQuery(null, null, null, null, null,
                List.of(tagNo), null, null, null, null, 1, 0));
        send(e, MbrReachLog.SCENE_NOTICE);
        send(e, MbrReachLog.SCENE_NOTICE);   // 第二次被频次闸拦下

        var row = opsMemberService.reachStats(30).stream()
                .filter(s -> e.equals(s.entityNo())).findFirst().orElseThrow();
        assertThat(row.tasks()).isEqualTo(2);
        assertThat(row.tagCount()).isEqualTo(1);
        assertThat(row.segmentCount()).isEqualTo(1);
        assertThat(row.skipped()).isEqualTo(1);
        assertThat(row.skipRate()).isEqualTo(50d);

        String out = json.writeValueAsString(row);
        assertThat(out).doesNotContain(secretTag).doesNotContain("机密人群").doesNotContain(tagNo);
    }
}
