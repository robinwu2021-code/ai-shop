package ai.neargo.shop.member.port;

import ai.neargo.shop.member.entity.MbrMember;
import ai.neargo.shop.member.mapper.MemberMappers.MemberMapper;
import ai.neargo.shop.member.service.MemberSegmentService;
import ai.neargo.shop.spi.member.MemberQueryPort;
import ai.neargo.shop.spi.user.PersonPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 人群 → 可触达账号（{@link MemberQueryPort}）。
 *
 * <p><b>两个数分开算</b>：命中多少人是条件的事，能发给多少人还要看
 * 他有没有账号、退没退订。发放页要能说出「25 发出、12 跳过」，
 * 而那 12 个里有多少是「还没注册」必须由这里报出来 —— 营销域看不见会员的身份字段。
 */
@Component
public class MemberQueryPortImpl implements MemberQueryPort {

    private final MemberSegmentService segmentService;
    /** 受众判断要标签集合。同域直接依赖，不必绕 Port */
    private final ai.neargo.shop.member.service.MemberTagService tagService;
    private final MemberMapper memberMapper;
    private final PersonPort personPort;

    public MemberQueryPortImpl(MemberSegmentService segmentService, MemberMapper memberMapper,
                               PersonPort personPort,
                               ai.neargo.shop.member.service.MemberTagService tagService) {
        this.tagService = tagService;
        this.segmentService = segmentService;
        this.memberMapper = memberMapper;
        this.personPort = personPort;
    }

    @Override
    public MemberSnapshot judge(String entityNo, String userNo) {
        if (entityNo == null || userNo == null || userNo.isBlank()) {
            return MemberSnapshot.notMember();
        }
        /*
         * 绕开数据域：这一刻的会话是**买家自己**（SELF），而 mbr_* 按 entity_no 登记。
         * 不绕的话查出来恒为空 —— 表现是「所有人都不是会员」，
         * 于是会员专享活动对谁都不生效，而日志干净、接口成功。
         */
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> {
            String personNo = personPort.findByUser(userNo)
                    .map(PersonPort.PersonView::personNo).orElse(null);
            if (personNo == null) {
                return MemberSnapshot.notMember();
            }
            MbrMember m = memberMapper.selectOne(Wrappers.<MbrMember>lambdaQuery()
                    .eq(MbrMember::getEntityNo, entityNo)
                    .eq(MbrMember::getPersonNo, personNo).last("limit 1"));
            if (m == null || !MbrMember.ACTIVE.equals(m.getStatus())) {
                // 线索会员不算会员：商家录了个号不等于这个人来过
                return MemberSnapshot.notMember();
            }
            java.util.Set<String> tags = tagService.tagsOf(entityNo, m.getMemberNo()).stream()
                    .map(ai.neargo.shop.member.dto.MemberVOs.TagVO::tagNo)
                    .collect(java.util.stream.Collectors.toSet());
            java.util.Set<String> segments = new java.util.HashSet<>();
            for (var sg : segmentService.list(entityNo)) {
                if (segmentService.matches(entityNo, sg.segmentNo(), m.getMemberNo())) {
                    segments.add(sg.segmentNo());
                }
            }
            return new MemberSnapshot(true, m.getLevel(), m.getSource(), tags, segments);
        });
    }

    /** 按分层筛人要用；setter 注入，免得改构造函数（切片测试里没有它时预设人群不可用） */
    private ai.neargo.shop.member.service.MemberService memberService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMemberService(ai.neargo.shop.member.service.MemberService memberService) {
        this.memberService = memberService;
    }

    /**
     * 预设人群的键：{@code @ALL} / {@code @NEW} / {@code @REGULAR} / {@code @LOYAL} / {@code @SLEEPING}。
     * 以 @ 开头是为了与存下来的人群号（{@code SG…}）永不相撞。不是预设时返回 null。
     */
    static String presetLevel(String segmentNo) {
        if (segmentNo == null || !segmentNo.startsWith("@")) {
            return null;
        }
        String level = segmentNo.substring(1);
        return java.util.Set.of(PRESET_ALL, "NEW", "REGULAR", "LOYAL", "SLEEPING").contains(level) ? level : null;
    }

    private static final String PRESET_ALL = "ALL";

    @Override
    public SegmentAudience resolveSegment(String entityNo, String segmentNo) {
        List<String> reachableNos;
        int matched;
        String level = presetLevel(segmentNo);
        if (level != null) {
            /*
             * 预设人群（原型 s18：全部会员 / 新客 / 熟客 / 沉睡）按分层现筛，不要求商家先存一个人群 ——
             * 「发给沉睡会员」是最常见的一次发放，让他先去会员页存人群是多出来的一步。
             * 与存下来的人群走同一个筛人实现（MemberService.match），不另写一份。
             */
            var q = new ai.neargo.shop.member.dto.MemberVOs.MemberQuery(null,
                    PRESET_ALL.equals(level) ? null : level, null, null, null, List.of(),
                    null, null, null, null, 1, 0);
            reachableNos = memberService.matchReachable(entityNo, q);
            matched = memberService.match(entityNo, q).size();
        } else {
            // resolve 给的已经是「可触达」的那一批（线索与退订的人不在内）
            reachableNos = segmentService.resolve(entityNo, segmentNo);
            matched = segmentService.matchedCount(entityNo, segmentNo);
        }

        List<Audience> out = new ArrayList<>();
        for (String memberNo : reachableNos) {
            MbrMember m = memberMapper.selectOne(Wrappers.<MbrMember>lambdaQuery()
                    .eq(MbrMember::getMemberNo, memberNo).last("limit 1"));
            if (m == null || m.getPersonNo() == null) {
                continue;
            }
            String userNo = personPort.find(m.getPersonNo())
                    .map(PersonPort.PersonView::userNo).orElse(null);
            if (userNo == null || userNo.isBlank()) {
                // 人档在、账号还没绑上：他收不到任何东西，算跳过而不是算发出
                continue;
            }
            out.add(new Audience(memberNo, userNo));
        }
        return new SegmentAudience(matched, out);
    }
}
