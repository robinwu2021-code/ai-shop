package ai.neargo.shop.member.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.dto.MemberVOs.MemberQuery;
import ai.neargo.shop.member.dto.MemberVOs.SegmentPreviewVO;
import ai.neargo.shop.member.dto.MemberVOs.SegmentVO;
import ai.neargo.shop.member.entity.MbrSegment;
import ai.neargo.shop.member.mapper.MemberMappers.SegmentMapper;
import ai.neargo.shop.member.service.MemberSegmentService;
import ai.neargo.shop.member.service.MemberService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 人群 —— <b>只存条件</b>。
 *
 * <p>命中人数在这里一律现算（{@link #preview}/{@link #resolve} 都走
 * {@link MemberService#match}）。{@code last_count} 只是给界面显示「上次算于 X」，
 * 任何判断都不许读它：名单每天都在变，而按两周前的名单发券没有任何补救办法。
 */
@Service
public class MemberSegmentServiceImpl implements MemberSegmentService {

    /** 一个主体能存多少个人群。够用即可 —— 攒到几百个的时候，商家自己也认不出哪个是哪个 */
    private static final int MAX_SEGMENTS = 50;

    private final SegmentMapper segmentMapper;
    private final MemberService memberService;
    private final ObjectMapper json;

    public MemberSegmentServiceImpl(SegmentMapper segmentMapper, MemberService memberService,
                                    ObjectMapper json) {
        this.segmentMapper = segmentMapper;
        this.memberService = memberService;
        this.json = json;
    }

    @Override
    public List<SegmentVO> list(String entityNo) {
        return segmentMapper.selectList(Wrappers.<MbrSegment>lambdaQuery()
                        .eq(MbrSegment::getEntityNo, entityNo)
                        .orderByDesc(MbrSegment::getId))
                .stream().map(this::vo).toList();
    }

    @Override
    @Transactional
    public SegmentVO save(String entityNo, String segmentNo, String name, String scopeStoreNo,
                          MemberQuery rule) {
        if (name == null || name.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MbrSegment row = segmentNo == null || segmentNo.isBlank() ? null : row(entityNo, segmentNo);
        if (row == null) {
            /*
             * 同名视为同一个人群（覆盖条件）而不是报「名称已存在」。
             * 商家改条件时的动作就是「再存一次，名字照旧」——
             * 那时候弹一个重名错误，他只会把名字改成「南门店沉睡老客2」，
             * 于是库里躺着两个人群，而没人知道哪个在用。
             */
            row = segmentMapper.selectOne(Wrappers.<MbrSegment>lambdaQuery()
                    .eq(MbrSegment::getEntityNo, entityNo)
                    .eq(MbrSegment::getName, name).last("limit 1"));
        }
        MemberQuery clean = normalize(rule, scopeStoreNo);
        int count = memberService.match(entityNo, clean).size();   // 展示用，不参与任何判断

        if (row == null) {
            Long used = segmentMapper.selectCount(Wrappers.<MbrSegment>lambdaQuery()
                    .eq(MbrSegment::getEntityNo, entityNo));
            if (used != null && used >= MAX_SEGMENTS) {
                throw BizException.of(ErrorCode.MEMBER_SEGMENT_LIMIT);
            }
            row = new MbrSegment();
            row.setSegmentNo(BizKey.next(BizKey.MEMBER_SEGMENT));
            row.setEntityNo(entityNo);
        }
        row.setName(name);
        row.setScopeStoreNo(scopeStoreNo);
        row.setRuleJson(json.writeValueAsString(clean));
        row.setLastCount(count);
        row.setCountedAt(System.currentTimeMillis());
        if (row.getId() == null) {
            segmentMapper.insert(row);
        } else {
            segmentMapper.updateById(row);
        }
        return vo(row);
    }

    @Override
    @Transactional
    public void remove(String entityNo, String segmentNo) {
        MbrSegment row = row(entityNo, segmentNo);
        if (row != null) {
            segmentMapper.deleteById(row.getId());
        }
    }

    @Override
    public SegmentPreviewVO preview(String entityNo, String scopeStoreNo, MemberQuery rule) {
        MemberQuery clean = normalize(rule, scopeStoreNo);
        return new SegmentPreviewVO(memberService.match(entityNo, clean).size(),
                memberService.matchReachable(entityNo, clean).size());
    }

    @Override
    public List<String> resolve(String entityNo, String segmentNo) {
        MbrSegment row = row(entityNo, segmentNo);
        if (row == null) {
            throw BizException.of(ErrorCode.MEMBER_SEGMENT_NOT_FOUND);
        }
        return memberService.matchReachable(entityNo,
                normalize(parse(row), row.getScopeStoreNo()));
    }

    @Override
    public int matchedCount(String entityNo, String segmentNo) {
        MbrSegment row = row(entityNo, segmentNo);
        if (row == null) {
            throw BizException.of(ErrorCode.MEMBER_SEGMENT_NOT_FOUND);
        }
        return memberService.match(entityNo, normalize(parse(row), row.getScopeStoreNo())).size();
    }

    @Override
    public List<String> resolve(String entityNo, String scopeStoreNo, MemberQuery rule) {
        return memberService.matchReachable(entityNo, normalize(rule, scopeStoreNo));
    }

    /**
     * 条件归一：门店以人群自身的 {@code scopeStoreNo} 为准，分页去掉。
     *
     * <p>条件里那个 {@code storeNo} 是列表页筛选用的，人群的门店范围是另一件事 ——
     * 两个都留着的话，「南门店沉睡老客」被在北门店的页面上打开一次，就会静默变成北门店的。
     */
    private MemberQuery normalize(MemberQuery rule, String scopeStoreNo) {
        MemberQuery q = rule == null
                ? new MemberQuery(null, null, null, null, null, List.of(),
                        null, null, null, null, 1, 0)
                : rule;
        return new MemberQuery(scopeStoreNo, q.level(), q.source(), q.status(),
                // 人群里不存手机号：那是找具体某个人用的，存进条件等于把号码抄了一份
                null,
                q.tagNos() == null ? List.of() : q.tagNos(),
                q.lastOrderBefore(), q.lastOrderAfter(), q.spentMin(), q.spentMax(), 1, 0);
    }

    private MbrSegment row(String entityNo, String segmentNo) {
        return segmentMapper.selectOne(Wrappers.<MbrSegment>lambdaQuery()
                .eq(MbrSegment::getEntityNo, entityNo)
                .eq(MbrSegment::getSegmentNo, segmentNo).last("limit 1"));
    }

    private MemberQuery parse(MbrSegment row) {
        try {
            return json.readValue(row.getRuleJson(), MemberQuery.class);
        } catch (RuntimeException e) {
            /*
             * 条件读不出来时**当成空条件会命中全部会员** —— 那是发券场景里最糟的默认值。
             * 宁可让这一次发放失败：坏掉的人群一定是我们自己改字段改出来的，
             * 而它应该在发出去之前被看见。
             */
            throw BizException.of(ErrorCode.MEMBER_SEGMENT_BROKEN);
        }
    }

    private SegmentVO vo(MbrSegment row) {
        return new SegmentVO(row.getSegmentNo(), row.getName(), row.getScopeStoreNo(),
                parse(row), row.getLastCount() == null ? 0 : row.getLastCount(),
                row.getCountedAt());
    }
}
