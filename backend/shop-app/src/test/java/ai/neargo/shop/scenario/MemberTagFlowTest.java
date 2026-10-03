package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.entity.MbrMember;
import ai.neargo.shop.member.entity.MbrTag;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 手工录入、线索转正、标签与合并（P2）。
 *
 * <p>三条硬规则在这里锁死：<b>线索不可触达</b>、<b>系统标签只读</b>、
 * <b>合并前先给影响面</b>。前两条是合规与口径边界，写在文档里三个月后没人记得；
 * 第三条是因为合并不可逆。
 */
@SpringBootTest
@ActiveProfiles("test")
class MemberTagFlowTest {

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberTagService tagService;

    @Autowired
    private PersonService personService;

    @Autowired
    private ai.neargo.shop.user.mapper.UserMappers.UserMapper userMapper;

    private static int seq = 7000;

    private static String phone() {
        return "1360000" + (++seq);
    }

    private static String entity() {
        return "M-TAG-" + seq;
    }

    private String account() {
        var u = new ai.neargo.shop.user.entity.UsrAccount();
        u.setUserNo("U-TAG-" + seq);
        u.setNickname("测试");
        u.setStatus("NORMAL");
        userMapper.insert(u);
        return u.getUserNo();
    }

    @Test
    @DisplayName("★★ 商家录入未注册的号 = **线索**：不可触达、不进受众")
    void manualEnrollCreatesLead() {
        String e = entity();
        MbrMember m = memberService.enroll(e, phone(), "三单元张阿姨", null, "ST-1", "OP-1");

        assertThat(m.getStatus()).isEqualTo(MbrMember.LEAD);
        assertThat(m.reachable()).as("线索不可触达 —— 录入手机号不等于拿到推送许可").isFalse();
        assertThat(m.getSource()).isEqualTo(MbrMember.SOURCE_MANUAL);
        assertThat(m.getRemark()).isEqualTo("三单元张阿姨");
    }

    @Test
    @DisplayName("★ 重复录入同一个号不报错 —— 店员重复录入是常态，报错只会让他再录一次")
    void enrollTwiceMergesRemark() {
        String e = entity();
        String p = phone();
        String first = memberService.enroll(e, p, "张阿姨", null, "ST-1", "OP-1").getMemberNo();
        MbrMember again = memberService.enroll(e, p, "三单元张阿姨", null, "ST-1", "OP-2");

        assertThat(again.getMemberNo()).isEqualTo(first);
        assertThat(again.getRemark()).as("备注并进去").isEqualTo("三单元张阿姨");
    }

    @Test
    @DisplayName("★★ 他自己登录那一刻，线索转正 —— 一次绑定，几家商家的会员同时生效")
    void leadBecomesActiveWhenOwnerLogsIn() {
        String p = phone();
        String e1 = "M-TAG-A" + seq;
        String e2 = "M-TAG-B" + seq;
        memberService.enroll(e1, p, "甲店录的", null, "ST-1", "OP-1");
        memberService.enroll(e2, p, "乙店录的", null, "ST-9", "OP-2");

        // 本人注册/登录
        personService.bindOnLogin(account(), p);

        String personNo = personService.resolveOrCreateByPhone(p).getPersonNo();
        assertThat(memberService.find(e1, personNo).orElseThrow().getStatus())
                .isEqualTo(MbrMember.ACTIVE);
        assertThat(memberService.find(e2, personNo).orElseThrow().getStatus())
                .as("两家店的线索一起转正，不需要逐家认领").isEqualTo(MbrMember.ACTIVE);
        assertThat(memberService.find(e1, personNo).orElseThrow().getClaimedAt()).isNotNull();
    }

    @Test
    @DisplayName("★★ 商家不能把线索点成正式会员 —— 转正只能由本人绑定账号触发")
    void merchantCannotPromoteLead() {
        String e = entity();
        MbrMember lead = memberService.enroll(e, phone(), null, null, "ST-1", "OP-1");

        memberService.patch(e, lead.getMemberNo(), "改个备注", MbrMember.ACTIVE);

        assertThat(memberService.detail(e, lead.getMemberNo()).orElseThrow().member().status())
                .isEqualTo(MbrMember.LEAD);
    }

    @Test
    @DisplayName("★ 改名只动字典一行 —— 关系表存的是号，所以历史统计不断")
    void renameKeepsRelations() {
        String e = entity();
        var tag = tagService.create(e, "囤货党", "OP-1");
        MbrMember m = memberService.enroll(e, phone(), null, List.of(tag.tagNo()), "ST-1", "OP-1");

        tagService.rename(e, tag.tagNo(), "爱囤货");

        var tags = tagService.tagsOf(e, m.getMemberNo());
        assertThat(tags).hasSize(1);
        assertThat(tags.getFirst().name()).isEqualTo("爱囤货");
        assertThat(tags.getFirst().tagNo()).as("号不变").isEqualTo(tag.tagNo());
    }

    @Test
    @DisplayName("★★ 合并：先试算给影响面，确认后才落库；源标签保留为 MERGED")
    void mergePreviewsThenApplies() {
        String e = entity();
        var from = tagService.create(e, "囤货党" + seq, "OP-1");
        var into = tagService.create(e, "爱囤货" + seq, "OP-1");

        MbrMember both = memberService.enroll(e, phone(), null,
                List.of(from.tagNo(), into.tagNo()), "ST-1", "OP-1");
        MbrMember onlyFrom = memberService.enroll(e, phone(), null,
                List.of(from.tagNo()), "ST-1", "OP-1");

        var preview = tagService.merge(e, from.tagNo(), into.tagNo(), false, "OP-1");
        assertThat(preview.applied()).as("试算不落库").isFalse();
        assertThat(preview.affectedMembers()).isEqualTo(2);
        assertThat(preview.bothTagged()).as("两个标签都有的人，合并后只留一条").isEqualTo(1);
        // 试算之后源标签还在
        assertThat(tagService.tags(e)).anySatisfy(t -> {
            if (t.tagNo().equals(from.tagNo())) {
                assertThat(t.status()).isEqualTo(MbrTag.ACTIVE);
            }
        });

        var applied = tagService.merge(e, from.tagNo(), into.tagNo(), true, "OP-1");
        assertThat(applied.applied()).isTrue();

        assertThat(tagService.tagsOf(e, onlyFrom.getMemberNo()))
                .singleElement().satisfies(t -> assertThat(t.tagNo()).isEqualTo(into.tagNo()));
        assertThat(tagService.tagsOf(e, both.getMemberNo()))
                .as("两个都有的人合并后只剩一条").hasSize(1);
        // 源标签从字典列表里消失（状态 MERGED），但**行还在** —— 活动受众可能还引用着它
        assertThat(tagService.tags(e)).noneSatisfy(t ->
                assertThat(t.tagNo()).isEqualTo(from.tagNo()));
    }

    @Test
    @DisplayName("★★ 系统标签只读：不能改名、不能停用、不能手动打")
    void systemTagsAreReadOnly() {
        String e = entity();
        // 直接造一个系统标签（正常由每日任务写）
        var sys = tagService.create(e, "沉睡" + seq, "OP-1");
        promoteToSystem(e, sys.tagNo());

        assertThatThrownBy(() -> tagService.rename(e, sys.tagNo(), "睡着了"))
                .isInstanceOf(BizException.class)
                .satisfies(x -> assertThat(((BizException) x).errorCode())
                        .isEqualTo(ErrorCode.MEMBER_TAG_SYSTEM_READONLY));

        MbrMember m = memberService.enroll(e, phone(), null, null, "ST-1", "OP-1");
        assertThatThrownBy(() ->
                tagService.tag(e, List.of(m.getMemberNo()), List.of(sys.tagNo()), List.of(), "OP-1"))
                .isInstanceOf(BizException.class);
    }

    @Autowired
    private ai.neargo.shop.member.mapper.MemberMappers.TagMapper tagMapper;

    private void promoteToSystem(String entityNo, String tagNo) {
        var t = tagMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<MbrTag>lambdaQuery().eq(MbrTag::getTagNo, tagNo).last("limit 1"));
        t.setTagType(MbrTag.SYS);
        tagMapper.updateById(t);
    }

    @Test
    @DisplayName("★ 重复打标不报错 —— 先筛后打时人群会重叠，报错等于让他一个个挑出来")
    void taggingTwiceIsIdempotent() {
        String e = entity();
        var tag = tagService.create(e, "不要辣" + seq, "OP-1");
        MbrMember m = memberService.enroll(e, phone(), null, null, "ST-1", "OP-1");

        tagService.tag(e, List.of(m.getMemberNo()), List.of(tag.tagNo()), List.of(), "OP-1");
        tagService.tag(e, List.of(m.getMemberNo()), List.of(tag.tagNo()), List.of(), "OP-1");

        assertThat(tagService.tagsOf(e, m.getMemberNo())).hasSize(1);

        tagService.tag(e, List.of(m.getMemberNo()), List.of(), List.of(tag.tagNo()), "OP-1");
        assertThat(tagService.tagsOf(e, m.getMemberNo())).isEmpty();
    }
}
