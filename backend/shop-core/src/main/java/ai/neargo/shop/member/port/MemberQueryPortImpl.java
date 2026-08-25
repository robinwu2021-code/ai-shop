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

    @Override
    public SegmentAudience resolveSegment(String entityNo, String segmentNo) {
        // resolve 给的已经是「可触达」的那一批（线索与退订的人不在内）
        List<String> reachableNos = segmentService.resolve(entityNo, segmentNo);
        int matched = segmentService.matchedCount(entityNo, segmentNo);

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
