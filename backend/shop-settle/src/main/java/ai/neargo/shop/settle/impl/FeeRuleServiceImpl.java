package ai.neargo.shop.settle.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.settle.entity.StlFeeRule;
import ai.neargo.shop.settle.mapper.SettleMappers.FeeRuleMapper;
import ai.neargo.shop.settle.service.FeeRuleService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeeRuleServiceImpl implements FeeRuleService {

    private final FeeRuleMapper feeRuleMapper;

    public FeeRuleServiceImpl(FeeRuleMapper feeRuleMapper) {
        this.feeRuleMapper = feeRuleMapper;
    }

    @Override
    public int rateOf(String businessMode, String trafficSource, long atMillis) {
        return effectiveRates(atMillis).getOrDefault(key(businessMode, trafficSource), 0);
    }

    @Override
    public Map<String, Integer> effectiveRates(long atMillis) {
        /*
         * 一次查出所有「生效时刻 <= at」的规则，按生效时刻升序，同一格后来的覆盖先来的。
         *
         * 不在 SQL 里做 group by + max：那样要么写原生 SQL（本仓库的 Mapper 只做单表
         * CRUD），要么每格查一次（四格四次，而结算是逐子单调的）。规则表天然很小
         * —— 四格 × 调整次数 —— 全量拉回来在内存里折叠是最省事也最好读的。
         */
        List<StlFeeRule> rows = feeRuleMapper.selectList(Wrappers.<StlFeeRule>lambdaQuery()
                .le(StlFeeRule::getEffectiveFrom, atMillis)
                .orderByAsc(StlFeeRule::getEffectiveFrom));
        Map<String, Integer> out = new HashMap<>();
        for (StlFeeRule r : rows) {
            if (!r.active()) {
                /*
                 * 停用的版本要**参与覆盖**再被移除，不能直接跳过：
                 * 「停用最新版本」的意图是回退到上一版，而直接跳过会让最新版形同没存在过，
                 * 于是命中的是更早的某一版 —— 两者在只调过一次时看不出区别，
                 * 调过三次时结果完全不同。
                 */
                out.remove(key(r.getBusinessMode(), r.getTrafficSource()));
                continue;
            }
            out.put(key(r.getBusinessMode(), r.getTrafficSource()),
                    r.getRateBp() == null ? 0 : r.getRateBp());
        }
        return out;
    }

    @Override
    public List<StlFeeRule> rules() {
        return feeRuleMapper.selectList(Wrappers.<StlFeeRule>lambdaQuery()
                .orderByAsc(StlFeeRule::getBusinessMode)
                .orderByAsc(StlFeeRule::getTrafficSource)
                .orderByDesc(StlFeeRule::getEffectiveFrom));
    }

    @Override
    @Transactional
    public StlFeeRule addRule(String businessMode, String trafficSource, int rateBp,
                              long effectiveFrom, String remark, String operator) {
        if (businessMode == null || businessMode.isBlank()
                || trafficSource == null || trafficSource.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 费率不能为负，也不能超过 100%。
         *
         * 上界看着多余，但少一个零和多一个零在输入框里是同一次手滑：
         * 5000（50%）打成 50000 就是 500%，净额直接变成大额负数，
         * 而那笔单会一路走到分账。
         */
        if (rateBp < 0 || rateBp > StlFeeRule.BP_SCALE) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        StlFeeRule rule = new StlFeeRule();
        rule.setRuleNo(BizKey.next(BizKey.FEE_RULE));
        rule.setBusinessMode(businessMode);
        rule.setTrafficSource(trafficSource);
        rule.setRateBp(rateBp);
        rule.setEffectiveFrom(effectiveFrom);
        rule.setEnabled(1);
        rule.setRemark(remark);
        rule.setCreatedBy(operator);
        feeRuleMapper.insert(rule);
        return rule;
    }

    private static String key(String businessMode, String trafficSource) {
        return businessMode + "|" + trafficSource;
    }
}
