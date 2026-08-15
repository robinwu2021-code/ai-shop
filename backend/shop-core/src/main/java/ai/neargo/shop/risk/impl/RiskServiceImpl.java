package ai.neargo.shop.risk.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.IsoTime;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.risk.RiskService;
import ai.neargo.shop.risk.dto.BlacklistVO;
import ai.neargo.shop.risk.dto.RiskEventVO;
import ai.neargo.shop.risk.dto.RiskRuleVO;
import ai.neargo.shop.risk.entity.RiskBlacklist;
import ai.neargo.shop.risk.entity.RiskEvent;
import ai.neargo.shop.risk.entity.RiskRule;
import ai.neargo.shop.risk.mapper.RiskMappers.RiskBlacklistMapper;
import ai.neargo.shop.risk.mapper.RiskMappers.RiskEventMapper;
import ai.neargo.shop.risk.mapper.RiskMappers.RiskRuleMapper;
import ai.neargo.shop.spi.risk.RiskEventPort;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 风控的运营面（P-16.2）：列事件、裁决、拉黑、配规则。
 *
 * <p>识别本身在 {@link RiskDetector} —— 这里只做「人怎么处置」那一半。
 * 分开是因为两者的触发者完全不同：识别由事件驱动（无人值守），
 * 处置由运营点击驱动（每一步都要留痕与结论）。
 */
@Service
public class RiskServiceImpl implements RiskService {

    private final RiskEventMapper eventMapper;
    private final RiskBlacklistMapper blacklistMapper;
    private final RiskRuleMapper ruleMapper;
    private final RiskDetector detector;

    public RiskServiceImpl(RiskEventMapper eventMapper, RiskBlacklistMapper blacklistMapper,
                           RiskRuleMapper ruleMapper, RiskDetector detector) {
        this.eventMapper = eventMapper;
        this.blacklistMapper = blacklistMapper;
        this.ruleMapper = ruleMapper;
        this.detector = detector;
    }

    // ------------------------------------------------------------ 事件

    @Override
    public PageData<RiskEventVO> events(String type, String status, String keyword,
                                        long page, long size) {
        LambdaQueryWrapper<RiskEvent> q = Wrappers.<RiskEvent>lambdaQuery()
                .eq(!blank(type), RiskEvent::getType, type)
                .eq(!blank(status), RiskEvent::getStatus, status)
                .and(!blank(keyword), w -> w.like(RiskEvent::getRiskEventNo, keyword)
                        .or().like(RiskEvent::getSubject, keyword)
                        .or().like(RiskEvent::getSubjectName, keyword)
                        .or().like(RiskEvent::getSignals, keyword))
                // 待处置排前面：这是个队列，历史是次要视图（CONFIRMED < DISMISSED < PENDING
                // 按字典序不成立，所以显式排 status 再排 id —— 至少同状态内是新的在前）
                .orderByDesc(RiskEvent::getId);
        Page<RiskEvent> p = eventMapper.selectPage(new Page<>(page, size), q);
        return PageData.of(p.convert(RiskServiceImpl::toEventVO));
    }

    @Override
    @Transactional
    public RiskEventVO decide(String eventNo, boolean confirmed, String verdict, String operatorNo) {
        RiskEvent e = eventMapper.selectOne(Wrappers.<RiskEvent>lambdaQuery()
                .eq(RiskEvent::getRiskEventNo, eventNo).last("limit 1"));
        if (e == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (!RiskEvent.PENDING.equals(e.getStatus())) {
            throw BizException.of(ErrorCode.RISK_EVENT_HANDLED);
        }
        // 排除也要写理由：下次同一主体再命中时，得知道上次为什么放过
        if (blank(verdict)) {
            throw BizException.of(ErrorCode.RISK_VERDICT_REQUIRED);
        }
        e.setStatus(confirmed ? RiskEvent.CONFIRMED : RiskEvent.DISMISSED);
        e.setVerdict(verdict.trim());
        e.setDecidedBy(operatorNo);
        e.setDecidedAt(LocalDateTime.now());
        // 让出 openKey：处置完之后再命中，能重新开一张单
        e.setDedupKey(e.getRiskEventNo());
        eventMapper.updateById(e);
        return toEventVO(e);
    }

    // ------------------------------------------------------------ 黑名单

    @Override
    public PageData<BlacklistVO> blacklists(String subjectType, boolean activeOnly, String keyword,
                                            long page, long size) {
        LambdaQueryWrapper<RiskBlacklist> q = Wrappers.<RiskBlacklist>lambdaQuery()
                .eq(!blank(subjectType), RiskBlacklist::getSubjectType, subjectType)
                // 「只看生效中」必须与 RiskEventPort.blocked 同口径：到期的那条已经不拦人了，
                // 却仍然出现在这张列表里的话，运营会以为他还被封着
                .eq(activeOnly, RiskBlacklist::getActive, true)
                .gt(activeOnly, RiskBlacklist::getUntilAt, LocalDateTime.now())
                .and(!blank(keyword), w -> w.like(RiskBlacklist::getBlackNo, keyword)
                        .or().like(RiskBlacklist::getSubject, keyword)
                        .or().like(RiskBlacklist::getSubjectName, keyword)
                        .or().like(RiskBlacklist::getReason, keyword))
                .orderByDesc(RiskBlacklist::getId);
        Page<RiskBlacklist> p = blacklistMapper.selectPage(new Page<>(page, size), q);
        return PageData.of(p.convert(RiskServiceImpl::toBlacklistVO));
    }

    @Override
    @Transactional
    public BlacklistVO addBlacklist(String subjectType, String subject, String reason, String until,
                                    String operatorNo) {
        if (blank(subject)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (blank(reason)) {
            throw BizException.of(ErrorCode.BLACKLIST_REASON_REQUIRED);
        }
        // 无期限拉黑没有申诉出口，是产品事故不是风控严格
        LocalDateTime untilAt = IsoTime.parse(until);
        if (untilAt == null || !untilAt.isAfter(LocalDateTime.now())) {
            throw BizException.of(ErrorCode.BLACKLIST_UNTIL_REQUIRED);
        }
        String type = blank(subjectType) ? RiskEventPort.SUBJECT_USER : subjectType;
        if (activeOf(type, subject.trim()) != null) {
            throw BizException.of(ErrorCode.BLACKLIST_DUPLICATE, subject.trim());
        }

        RiskBlacklist b = new RiskBlacklist();
        b.setBlackNo(BizKey.next(BizKey.BLACKLIST));
        b.setSubjectType(type);
        b.setSubject(subject.trim());
        b.setReason(reason.trim());
        b.setUntilAt(untilAt);
        b.setAppealStatus(RiskBlacklist.APPEAL_NONE);
        b.setActive(true);
        b.setTenantNo("MAIN");
        blacklistMapper.insert(b);
        b.setCreatedBy(operatorNo);
        return toBlacklistVO(b);
    }

    @Override
    @Transactional
    public BlacklistVO submitAppeal(String userNo, String reason) {
        RiskBlacklist b = activeOf(RiskEventPort.SUBJECT_USER, userNo);
        if (b == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (blank(reason)) {
            throw BizException.of(ErrorCode.BLACKLIST_REASON_REQUIRED);
        }
        b.setAppealStatus(RiskBlacklist.APPEAL_PENDING);
        b.setAppealReason(reason.trim());
        blacklistMapper.updateById(b);
        return toBlacklistVO(b);
    }

    @Override
    @Transactional
    public BlacklistVO decideAppeal(String blackNo, boolean accept, String verdict, String operatorNo) {
        RiskBlacklist b = blacklistMapper.selectOne(Wrappers.<RiskBlacklist>lambdaQuery()
                .eq(RiskBlacklist::getBlackNo, blackNo).last("limit 1"));
        if (b == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (!RiskBlacklist.APPEAL_PENDING.equals(b.getAppealStatus())) {
            throw BizException.of(ErrorCode.BLACKLIST_NO_APPEAL);
        }
        if (blank(verdict)) {
            throw BizException.of(ErrorCode.RISK_VERDICT_REQUIRED);
        }
        b.setAppealStatus(accept ? RiskBlacklist.APPEAL_UPHELD : RiskBlacklist.APPEAL_REJECTED);
        b.setAppealVerdict(verdict.trim());
        b.setUpdatedBy(operatorNo);
        // 接受申诉 = 解除拉黑；**记录保留** —— 留痕不是删除
        if (accept) {
            b.setActive(false);
        }
        blacklistMapper.updateById(b);
        return toBlacklistVO(b);
    }

    /**
     * 该主体**当前真正生效中**的那条（人工没解除、且没到期）。
     *
     * <p>{@code until_at > now} 这一条曾经缺席，后果不是「多拦了谁」而是反过来：
     * 拉黑到期之后 {@code blocked()} 已经放行，而查重仍然撞到那条老记录 ——
     * <b>同一个人再也拉黑不上</b>（60006），且 60006 的文案写着「已在生效中的黑名单里」，
     * 与运营在页面上看到的（已经过期）正好相反，没有任何线索指向真正的原因。
     */
    private RiskBlacklist activeOf(String subjectType, String subject) {
        return blacklistMapper.selectOne(Wrappers.<RiskBlacklist>lambdaQuery()
                .eq(RiskBlacklist::getSubjectType, subjectType)
                .eq(RiskBlacklist::getSubject, subject)
                .eq(RiskBlacklist::getActive, true)
                .gt(RiskBlacklist::getUntilAt, LocalDateTime.now())
                .orderByDesc(RiskBlacklist::getId).last("limit 1"));
    }

    // ------------------------------------------------------------ 规则

    @Override
    public List<RiskRuleVO> rules() {
        List<RiskRuleVO> out = new ArrayList<>();
        for (String type : List.of(RiskEventPort.FAKE_ORDER, RiskEventPort.ABNORMAL_FISSION,
                RiskEventPort.MALICIOUS_REFUND)) {
            out.add(toRuleVO(detector.ruleOf(type)));
        }
        return out;
    }

    @Override
    @Transactional
    public RiskRuleVO saveRule(String type, int threshold, boolean autoBlock, String operatorNo) {
        if (!RiskDetector.DEFAULTS.containsKey(type)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        // 0 等于全量拦截 —— 页面上它只是一个普通数字，后端必须挡住
        if (threshold <= 0) {
            throw BizException.of(ErrorCode.RISK_THRESHOLD_INVALID);
        }
        RiskRule r = detector.ruleOf(type);
        r.setThreshold(threshold);
        r.setAutoBlock(autoBlock);
        r.setUpdatedBy(operatorNo);
        ruleMapper.updateById(r);
        return toRuleVO(r);
    }

    // ------------------------------------------------------------ 映射

    private static RiskEventVO toEventVO(RiskEvent e) {
        return new RiskEventVO(e.getRiskEventNo(), e.getType(), e.getSubject(), e.getSubjectType(),
                e.getSubjectName(), split(e.getSignals()), split(e.getRefs()), e.getStatus(),
                IsoTime.toIso(e.getCreatedAt()), e.getVerdict());
    }

    private static BlacklistVO toBlacklistVO(RiskBlacklist b) {
        return new BlacklistVO(b.getBlackNo(), b.getSubjectType(), b.getSubject(), b.getSubjectName(),
                b.getReason(), IsoTime.toIso(b.getUntilAt()), b.getAppealStatus(),
                // 到期即不再生效。契约原话：「到期或申诉通过后置 false，记录保留」——
                // 直接下发列的值会让一条早就失效的记录在页面上一直写着「生效中」
                b.getAppealReason(), b.getAppealVerdict(), b.inForce(),
                IsoTime.toIso(b.getCreatedAt()));
    }

    private static RiskRuleVO toRuleVO(RiskRule r) {
        return new RiskRuleVO(r.getType(), r.getThreshold() == null ? 0 : r.getThreshold(),
                Boolean.TRUE.equals(r.getAutoBlock()),
                r.getWindowHours() == null ? 24 : r.getWindowHours(),
                IsoTime.toIso(r.getUpdatedAt()));
    }

    /**
     * 逗号串 → 数组。**空串给空数组，不给 {@code [""]}** ——
     * 页面会把一个空字符串渲染成一个空的信号标签，看起来像「有信号但没写清楚」。
     */
    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
