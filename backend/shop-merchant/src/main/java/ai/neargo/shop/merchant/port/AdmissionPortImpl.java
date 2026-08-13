package ai.neargo.shop.merchant.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchAdmissionPolicy;
import ai.neargo.shop.merchant.entity.MchDeposit;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.AdmissionPolicyMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.DepositMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.common.Fulfillments;
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
    private final ai.neargo.shop.spi.user.PickupQueryPort pickupPort;
    private final ai.neargo.shop.spi.user.MerchantQueryPort merchantQueryPort;

    public AdmissionPortImpl(MchEntityMapper merchantMapper, AdmissionPolicyMapper policyMapper,
                             DepositMapper depositMapper, tools.jackson.databind.ObjectMapper json,
                             ai.neargo.shop.spi.user.PickupQueryPort pickupPort,
                             ai.neargo.shop.spi.user.MerchantQueryPort merchantQueryPort) {
        this.pickupPort = pickupPort;
        this.merchantQueryPort = merchantQueryPort;
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

    // ---------------------------------------------------------------- 准入矩阵（§7.7）

    /**
     * 矩阵的一格。
     *
     * <p><b>写死在代码里而不是做成配置表</b>，与费率、限额那些「运营旋钮」是两回事：
     * 这张表是**风险规范**，改它意味着平台愿意承担的责任变了，
     * 该走代码评审而不是后台表单。费率调错了是少收几个点，这里放错一格
     * 是让平台在自己完全不接触货的情况下为一个追不到的主体兜底。
     */
    private enum Cell {
        /** 允许 */
        ALLOW,
        /** 允许，但要买家确认收货 —— 交付环节已经没有独立第三方了 */
        NEEDS_BUYER_CONFIRM,
        /** 不允许：弱主体 + 交付零留痕 = 出事只有平台兜底 */
        DENY
    }

    /**
     * S（供货方风险，1 最强 3 最弱）× T（交付留痕，3 最强 1 最弱）。
     *
     * <pre>
     *          T3 快递      T2 自提核销   T1 商家自送
     *   S1     ALLOW        ALLOW        ALLOW
     *   S2     ALLOW        ALLOW        CONFIRM
     *   S3     ALLOW*       ALLOW*       DENY
     * </pre>
     *
     * <p>* S3 那两格在文档里是「⚠️ 保证金 + 限品类 + 限额」——
     * 这三样由 F-6 的策略表管，不在这张矩阵里重复表达；
     * 矩阵只回答「这个组合本身准不准」。
     */
    private static final Cell[][] MATRIX = {
            //          T1                      T2                  T3
            { Cell.ALLOW,               Cell.ALLOW,         Cell.ALLOW },          // S1
            { Cell.NEEDS_BUYER_CONFIRM, Cell.ALLOW,         Cell.ALLOW },          // S2
            { Cell.DENY,                Cell.ALLOW,         Cell.ALLOW },          // S3
    };

    @Override
    public boolean requireFulfillmentAllowed(String merchantNo, String fulfillment, String pickupNo) {
        int s = riskOf(merchantNo);
        int t = traceOf(fulfillment);
        if (s == 0 || t == 0) {
            // 主体类型或履约方式认不出来 —— 与本类其余判定同向：认不出就放行。
            // 这里限制的只是最弱一档，误拦的代价远大于漏放一时
            return false;
        }

        boolean degraded = false;
        if (t > 1 && supplierIsPickupOwner(merchantNo, pickupNo)) {
            /*
             * 自己发货、自己核销、自己证明送到了 —— 那道「独立第三方」不存在。
             * 降级而不是新增一个枚举值：往后再出现别的巧合组合，矩阵不用改。
             */
            t -= 1;
            degraded = true;
        }

        Cell cell = MATRIX[s - 1][t - 1];
        if (cell == Cell.DENY) {
            throw BizException.of(ErrorCode.FULFILLMENT_TIER_DENIED);
        }
        /*
         * 只有**降级之后**落进 CONFIRM 格才要买家确认。
         *
         * 没降级的 S2×T1（商家本来就自送）不加这一步：那是一种正常经营形态，
         * 给每一单都加确认会把它变成一道人人都要点的噪音，
         * 而噪音多了真正需要确认的那一单就没人看。
         */
        return degraded && cell == Cell.NEEDS_BUYER_CONFIRM;
    }

    /** S 轴：1=企业 2=个体户 3=小微；认不出返回 0。 */
    private int riskOf(String merchantNo) {
        MchEntity m = merchantMapper.selectOne(
                Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getEntityNo, merchantNo).last("LIMIT 1"));
        if (m == null) {
            return 0;
        }
        return switch (String.valueOf(m.getLegalForm())) {
            case "ENTERPRISE" -> 1;
            case "INDIVIDUAL" -> 2;
            // V87 起 MICRO 改名 NATURAL_PERSON（那是通道档，不是法律形态）。
            // 旧值一并认，因为申请单上的历史快照不迁移
            case "NATURAL_PERSON", "MICRO" -> 3;
            default -> 0;
        };
    }

    /** T 轴：3=快递 2=自提核销 1=商家自送；认不出返回 0。 */
    private int traceOf(String fulfillment) {
        return switch (String.valueOf(fulfillment)) {
            case Fulfillments.EXPRESS -> 3;
            case Fulfillments.STORE_PICKUP, Fulfillments.NEIGHBOR_PICKUP -> 2;
            case Fulfillments.MERCHANT_DELIVERY -> 1;
            default -> 0;
        };
    }

    /**
     * 供货方是不是就是这个自提点的运营者。
     *
     * <p>只有邻居自提点会出现这种情况：它的 {@code ownerRef} 存的是用户号，
     * 而商户的所有人也是一个用户号 —— <b>只能在用户号这一层比</b>，
     * 商户号与用户号不是一个命名空间。
     */
    private boolean supplierIsPickupOwner(String merchantNo, String pickupNo) {
        if (pickupNo == null || pickupNo.isBlank()) {
            return false;
        }
        var pickup = pickupPort.find(pickupNo).orElse(null);
        if (pickup == null || !"NEIGHBOR".equals(pickup.type())
                || pickup.ownerRef() == null || pickup.ownerRef().isBlank()) {
            return false;
        }
        return merchantQueryPort.ownerUserNoOf(merchantNo)
                .map(owner -> owner.equals(pickup.ownerRef()))
                .orElse(false);
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
