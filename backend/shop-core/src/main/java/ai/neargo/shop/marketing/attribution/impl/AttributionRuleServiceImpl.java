package ai.neargo.shop.marketing.attribution.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.attribution.AttributionRuleService;
import ai.neargo.shop.marketing.attribution.entity.MktAttribution;
import ai.neargo.shop.marketing.attribution.entity.MktAttributionRule;
import ai.neargo.shop.marketing.attribution.mapper.AttributionMappers.AttributionRuleMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** {@link AttributionRuleService} 实现。 */
@Service
public class AttributionRuleServiceImpl implements AttributionRuleService {

    /** 三个来源的全序。少一个就有一种来源无从裁决 */
    private static final Set<String> SOURCES =
            Set.of(MktAttribution.STORE_CODE, MktAttribution.INVITER, MktAttribution.CHANNEL);

    private static final Set<String> POLICIES = Set.of(
            MktAttributionRule.KEEP_FIRST, MktAttributionRule.OVERWRITE, MktAttributionRule.ASK_USER);

    private static final Set<String> FACTORS = Set.of("DEVICE", "PHONE");

    /** 与 V121 的 DDL 默认值逐字一致 —— 两处分岔会让「没配过」与「配了默认值」行为不同 */
    private static final String DEFAULT_PRIORITY = "STORE_CODE,INVITER,CHANNEL";
    private static final int DEFAULT_WINDOW_DAYS = 30;
    private static final String DEFAULT_FACTORS = "DEVICE,PHONE";

    private static final int MIN_WINDOW = 1;
    private static final int MAX_WINDOW = 90;

    private final AttributionRuleMapper ruleMapper;

    public AttributionRuleServiceImpl(AttributionRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    @Override
    public RuleVO current() {
        MktAttributionRule row = find();
        if (row == null) {
            // 库里没有行时给 DDL 默认值，**不返回 null**：归因引擎每次判定都要读它，
            // 返回 null 会让「还没配过规则」变成一次 NPE
            return new RuleVO(split(DEFAULT_PRIORITY), DEFAULT_WINDOW_DAYS,
                    MktAttributionRule.OVERWRITE, split(DEFAULT_FACTORS), null, null);
        }
        return toVO(row);
    }

    @Override
    @Transactional
    public RuleVO save(SaveCommand cmd, String operatorNo) {
        List<String> priority = cmd.priority();
        /*
         * 优先级必须是三个来源的**全序**。
         *
         * 不校验的后果不是报错：少写一个来源之后，那种来源的权重变成 0，
         * 它与「没有归因」无法区分 —— 而商家的佣金档就是按这个判的。
         */
        if (priority == null || new LinkedHashSet<>(priority).size() != SOURCES.size()
                || !SOURCES.containsAll(priority)) {
            // 文案是「必须覆盖全部 {0} 个来源」—— 不传参，运营看到的就是字面的 {0}
            throw BizException.of(ErrorCode.ATTRIBUTION_PRIORITY_INVALID, SOURCES.size());
        }
        Integer days = cmd.windowDays();
        // 0 等于悄悄关掉归因：全平台订单都变成平台客流，商家佣金翻倍而没人收到通知
        if (days == null || days < MIN_WINDOW || days > MAX_WINDOW) {
            // 文案是「需在 {0}–{1} 天之间」，两个参数都要给
            throw BizException.of(ErrorCode.ATTRIBUTION_WINDOW_INVALID, MIN_WINDOW, MAX_WINDOW);
        }
        if (cmd.conflictPolicy() == null || !POLICIES.contains(cmd.conflictPolicy())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<String> factors = cmd.newUserFactors();
        // 一个因子都不选 = 所有人都是新客，新人券会被无限领
        if (factors == null || factors.isEmpty() || !FACTORS.containsAll(factors)) {
            throw BizException.of(ErrorCode.ATTRIBUTION_FACTOR_REQUIRED);
        }

        MktAttributionRule row = find();
        boolean fresh = row == null;
        if (fresh) {
            row = new MktAttributionRule();
            row.setRuleKey(MktAttributionRule.MAIN);
        }
        row.setPriority(String.join(",", priority));
        row.setWindowDays(days);
        row.setConflictPolicy(cmd.conflictPolicy());
        row.setNewUserFactors(String.join(",", factors));
        MktAttributionRule toSave = row;
        DataScopeContext.executeWithoutScope(() ->
                fresh ? ruleMapper.insert(toSave) : ruleMapper.updateById(toSave));
        return toVO(row);
    }

    private MktAttributionRule find() {
        return DataScopeContext.executeWithoutScope(() ->
                ruleMapper.selectOne(Wrappers.<MktAttributionRule>lambdaQuery()
                        .eq(MktAttributionRule::getRuleKey, MktAttributionRule.MAIN)
                        .last("limit 1")));
    }

    private RuleVO toVO(MktAttributionRule r) {
        return new RuleVO(split(r.getPriority()),
                r.getWindowDays() == null ? DEFAULT_WINDOW_DAYS : r.getWindowDays(),
                r.getConflictPolicy(), split(r.getNewUserFactors()),
                ai.neargo.shop.common.IsoTime.toIso(r.getUpdatedAt()), r.getUpdatedBy());
    }

    private static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of()
                : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
