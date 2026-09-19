package ai.neargo.shop.member.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.dto.MemberVOs.AudiencePreviewVO;
import ai.neargo.shop.member.dto.MemberVOs.BatchTagVO;
import ai.neargo.shop.member.dto.MemberVOs.MemberQuery;
import ai.neargo.shop.member.dto.MemberVOs.MergePreviewVO;
import ai.neargo.shop.member.dto.MemberVOs.SegmentDetailVO;
import ai.neargo.shop.member.dto.MemberVOs.SegmentVO;
import ai.neargo.shop.member.dto.MemberVOs.TagUsageVO;
import ai.neargo.shop.member.dto.MemberVOs.TagVO;
import ai.neargo.shop.member.entity.MbrMemberTag;
import ai.neargo.shop.member.mapper.MemberMappers.MemberTagMapper;
import ai.neargo.shop.member.service.MemberAudienceService;
import ai.neargo.shop.member.service.MemberSegmentService;
import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.member.service.MemberTagService;
import ai.neargo.shop.spi.marketing.AudienceRefPort;
import ai.neargo.shop.spi.member.MemberQueryPort.AudienceItem;
import ai.neargo.shop.spi.member.MemberQueryPort.AudienceResolution;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class MemberAudienceServiceImpl implements MemberAudienceService {

    private static final Logger log = LoggerFactory.getLogger(MemberAudienceServiceImpl.class);

    private final AudienceResolver resolver;
    private final MemberService memberService;
    private final MemberTagService tagService;
    private final MemberSegmentService segmentService;
    private final MemberTagMapper memberTagMapper;
    private final AudienceRefPort refPort;

    public MemberAudienceServiceImpl(AudienceResolver resolver, MemberService memberService,
                                     MemberTagService tagService, MemberSegmentService segmentService,
                                     MemberTagMapper memberTagMapper, AudienceRefPort refPort) {
        this.resolver = resolver;
        this.memberService = memberService;
        this.tagService = tagService;
        this.segmentService = segmentService;
        this.memberTagMapper = memberTagMapper;
        this.refPort = refPort;
    }

    @Override
    public AudiencePreviewVO preview(String entityNo, List<AudienceItem> items, String scene,
                                     boolean forActivity) {
        boolean empty = items == null || items.stream().noneMatch(i -> i != null && i.type() != null);
        if (empty && forActivity) {
            // 活动的空受众 = 所有人（向后兼容：老活动都是这样），人数不是一个有意义的数
            return new AudiencePreviewVO(null, null, List.of());
        }
        AudienceResolution r = resolver.resolve(entityNo, items, forActivity ? null : scene);
        if (!r.countable()) {
            return new AudiencePreviewVO(null, null, List.of());
        }
        return forActivity
                ? new AudiencePreviewVO(r.matched(), null, List.of())
                : new AudiencePreviewVO(r.matched(), r.reachable().size(), r.skips());
    }

    @Override
    public BatchTagVO batchTag(String entityNo, List<String> memberNos, MemberQuery rule,
                               String scopeStoreNo, String tagNo, boolean add, boolean confirm,
                               String operatorNo) {
        boolean byNos = memberNos != null && !memberNos.isEmpty();
        if (byNos == (rule != null)) {
            // 两种圈人方式只能给一种：都给时谁说了算是说不清的
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<String> targets = byNos ? memberNos : memberService.match(entityNo, normalize(rule, scopeStoreNo));
        return tagService.batch(entityNo, targets, tagNo, add, confirm, operatorNo);
    }

    /** 与人群同一条归一：门店以 scopeStoreNo 为准，手机号与分页去掉 */
    private static MemberQuery normalize(MemberQuery q, String scopeStoreNo) {
        return new MemberQuery(scopeStoreNo, q.level(), q.source(), q.status(), null,
                q.tagNos() == null ? List.of() : q.tagNos(),
                q.lastOrderBefore(), q.lastOrderAfter(), q.spentMin(), q.spentMax(), 1, 0);
    }

    @Override
    public TagUsageVO tagUsage(String entityNo, String tagNo) {
        TagVO tag = tagService.tags(entityNo).stream()
                .filter(t -> t.tagNo().equals(tagNo)).findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        long monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        int newThisMonth = Math.toIntExact(memberTagMapper.selectCount(Wrappers.<MbrMemberTag>lambdaQuery()
                .eq(MbrMemberTag::getEntityNo, entityNo)
                .eq(MbrMemberTag::getTagNo, tagNo)
                .ge(MbrMemberTag::getTaggedAt, monthStart)));
        return new TagUsageVO(tag, newThisMonth,
                refPort.activitiesUsing(entityNo, AudienceItem.TAG, tagNo),
                segmentService.usingTag(entityNo, tagNo));
    }

    @Override
    public SegmentDetailVO segmentDetail(String entityNo, String segmentNo) {
        SegmentVO sg = segmentService.list(entityNo).stream()
                .filter(x -> x.segmentNo().equals(segmentNo)).findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.MEMBER_SEGMENT_NOT_FOUND));
        AudienceResolution r = resolver.resolve(entityNo,
                List.of(new AudienceItem(AudienceItem.SEGMENT, segmentNo)), null);
        return new SegmentDetailVO(sg, r.matched(), r.reachable().size(),
                refPort.activitiesUsing(entityNo, AudienceItem.SEGMENT, segmentNo),
                refPort.couponIssuesUsing(entityNo, segmentNo));
    }

    @Override
    @Transactional
    public MergePreviewVO mergeTag(String entityNo, String fromTagNo, String intoTagNo, boolean confirm,
                                   String operatorNo) {
        int activities = refPort.activitiesUsing(entityNo, AudienceItem.TAG, fromTagNo).size();
        MergePreviewVO base = tagService.merge(entityNo, fromTagNo, intoTagNo, confirm, operatorNo);
        if (confirm) {
            /*
             * 关系行已经全部改指到目标标签。人群条件与活动受众里存的还是源标签号 ——
             * 不一起改，它们从这一刻起一个人都命中不了，而活动照样显示「进行中」。
             */
            int segments = segmentService.retargetTag(entityNo, fromTagNo, intoTagNo);
            int retargeted = refPort.retargetTag(entityNo, fromTagNo, intoTagNo);
            log.info("[member] 标签合并 {} → {}：改指人群 {} 个、活动受众 {} 行", fromTagNo, intoTagNo,
                    segments, retargeted);
        }
        return new MergePreviewVO(base.affectedMembers(), base.bothTagged(), activities, base.applied());
    }
}
