package ai.neargo.shop.merchant.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchAdmissionPolicy;
import ai.neargo.shop.merchant.entity.MchDeposit;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.AdmissionPolicyMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.DepositMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.spi.user.AdmissionPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * {@link AdmissionPort} 的实现。
 *
 * <p>单独成类而不是并入 {@code MerchantPortImpl}：那个类承载的是「商家是谁」，
 * 这个类承载的是「让不让他做这件事」——两者的变更理由不同，
 * 合在一起会让调整准入规则的改动落进跨域查询契约里。
 *
 * <p><b>贯穿全类的一条原则：查不到就放行。</b>
 * 没配策略、策略停用、档位为空，都当作「本档位不限制」。
 * 反过来（查不到就拦）会让一次配置疏漏变成全站商家无法上架，
 * 而这里限制的对象只是最弱的一档，误拦的代价远大于漏放一时。
 * 真正兜底的是 S3 那三样同时生效，不是靠这里的默认值。
 */
@Component
public class AdmissionPortImpl implements AdmissionPort {

    private final MchEntityMapper merchantMapper;
    private final AdmissionPolicyMapper policyMapper;
    private final DepositMapper depositMapper;
    private final tools.jackson.databind.ObjectMapper json;

    public AdmissionPortImpl(MchEntityMapper merchantMapper, AdmissionPolicyMapper policyMapper,
                             DepositMapper depositMapper, tools.jackson.databind.ObjectMapper json) {
        this.merchantMapper = merchantMapper;
        this.policyMapper = policyMapper;
        this.depositMapper = depositMapper;
        this.json = json;
    }

    @Override
    public void requireListingAllowed(String merchantNo, String categoryNo, boolean categoryNeedsQualification) {
        MchAdmissionPolicy p = policyOf(merchantNo).orElse(null);
        if (p == null) {
            return;
        }

        /*
         * 品类先判，钱后判。
         *
         * 顺序不是随意的：被禁的品类补多少保证金都没用，先报「补钱」会让商家
         * 白缴一笔再撞上第二堵墙。报错顺序应当与「有没有解法」一致 ——
         * 无解的先说。
         */
        if (categoryNeedsQualification && p.bansQualifiedCategory()) {
            throw BizException.of(ErrorCode.CATEGORY_BANNED);
        }
        if (categoryNo != null && !categoryNo.isBlank() && bannedCategories(p).contains(categoryNo)) {
            throw BizException.of(ErrorCode.CATEGORY_BANNED);
        }

        long required = orZero(p.getRequiredDepositMinor());
        if (required > MchAdmissionPolicy.UNLIMITED && availableDeposit(merchantNo) < required) {
            throw BizException.of(ErrorCode.DEPOSIT_INSUFFICIENT);
        }
    }

    @Override
    public void requireOrderAllowed(String merchantNo, long amountMinor, LongSupplier todayPaidMinor) {
        MchAdmissionPolicy p = policyOf(merchantNo).orElse(null);
        if (p == null) {
            return;
        }

        long single = orZero(p.getSingleOrderLimitMinor());
        if (single > MchAdmissionPolicy.UNLIMITED && amountMinor > single) {
            throw BizException.of(ErrorCode.ORDER_LIMIT_EXCEEDED);
        }

        long daily = orZero(p.getDailyAmountLimitMinor());
        if (daily > MchAdmissionPolicy.UNLIMITED) {
            /*
             * supplier 到这一行才被调用 —— 绝大多数商户（S1/S2 默认不限日累计）
             * 根本不会触发那次按天聚合查询，而它是本次改动里唯一一处会随订单量增长的查询。
             */
            if (todayPaidMinor.getAsLong() + amountMinor > daily) {
                throw BizException.of(ErrorCode.DAILY_LIMIT_EXCEEDED);
            }
        }
    }

    /**
     * 找商户所属档位的策略。
     *
     * <p>返回空表示「不限制」，三种情况都归到这里：商户不存在、主体类型为空、
     * 该档位没配或已停用。
     */
    private Optional<MchAdmissionPolicy> policyOf(String merchantNo) {
        if (merchantNo == null || merchantNo.isBlank()) {
            return Optional.empty();
        }
        MchEntity m = merchantMapper.selectOne(
                Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getEntityNo, merchantNo).last("LIMIT 1"));
        if (m == null || m.getLegalForm() == null || m.getLegalForm().isBlank()) {
            return Optional.empty();
        }
        MchAdmissionPolicy p = policyMapper.selectOne(
                Wrappers.<MchAdmissionPolicy>lambdaQuery()
                        .eq(MchAdmissionPolicy::getLegalForm, m.getLegalForm())
                        .last("LIMIT 1"));
        return p != null && p.active() ? Optional.of(p) : Optional.empty();
    }

    /** 可用余额 = 实缴 − 冻结；没开户按 0 算，等价于「一分没缴」。 */
    private long availableDeposit(String merchantNo) {
        MchDeposit d = depositMapper.selectOne(
                Wrappers.<MchDeposit>lambdaQuery().eq(MchDeposit::getMerchantNo, merchantNo).last("LIMIT 1"));
        return d == null ? 0L : d.availableMinor();
    }

    private java.util.Set<String> bannedCategories(MchAdmissionPolicy p) {
        String raw = p.getBannedCategoryCodes();
        if (raw == null || raw.isBlank()) {
            return java.util.Set.of();
        }
        try {
            return new java.util.HashSet<>(json.readValue(raw,
                    new tools.jackson.core.type.TypeReference<List<String>>() {
                    }));
        } catch (RuntimeException e) {
            /*
             * 与 authorizedCategoryCodes 里那段坏 JSON 的处理方向相反，且是有意的：
             * 那边解析失败按「没有授权」（更严），这边按「没有禁售」（更松）。
             * 判据是同一条 —— 出错时倒向「不因一行坏数据造成全档位误伤」。
             * 那边的严来自「授权是白名单，空即最严」，这边的松来自「禁售是黑名单，空即最松」。
             */
            return java.util.Set.of();
        }
    }

    private static long orZero(Long v) {
        return v == null ? 0L : v;
    }
}
