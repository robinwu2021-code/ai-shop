package ai.neargo.shop.pay.channel.master.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import ai.neargo.shop.pay.channel.entity.SysPayChannelRate;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelRateMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import ai.neargo.shop.pay.channel.master.PayChannelRateService;

@Service
public class PayChannelRateServiceImpl implements PayChannelRateService {

    private final PayChannelRateMapper mapper;

    public PayChannelRateServiceImpl(PayChannelRateMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PayChannelRateService.ChannelFeeRate effective(String payChannel, String payMethod, String legalForm, long at) {
        List<SysPayChannelRate> rows = DataScopeContext.executeWithoutScope(() ->
                mapper.selectList(Wrappers.<SysPayChannelRate>lambdaQuery()
                        .eq(SysPayChannelRate::getPayChannel, payChannel)
                        .eq(SysPayChannelRate::getEnabled, true)
                        .le(SysPayChannelRate::getEffectiveFrom, at)));
        /*
         * 精确 → 通配，四档。**顺序不能反**：
         * 先匹通配的话，配了「企业专属费率」也永远取不到，
         * 而那种错不报警，只会让某一类商家一直按通用费率结算。
         */
        for (String pm : new String[]{payMethod, SysPayChannelRate.ANY}) {
            for (String lf : new String[]{legalForm, SysPayChannelRate.ANY}) {
                SysPayChannelRate hit = pick(rows, pm, lf);
                if (hit != null) {
                    return toRate(hit);
                }
            }
        }
        return null;
    }

    /** 同一格里取<b>生效时间最晚</b>的那一版 —— 版本表里同一格会有多条。 */
    /** entity → 三个数。null 字段按 0，与搬家前 MasterDataPortImpl 里的处理逐字一致 */
    private static PayChannelRateService.ChannelFeeRate toRate(SysPayChannelRate r) {
        return new PayChannelRateService.ChannelFeeRate(
                r.getRateBp() == null ? 0 : r.getRateBp(),
                r.getMinFeeMinor() == null ? 0L : r.getMinFeeMinor(),
                r.getRateNo());
    }

    private static SysPayChannelRate pick(List<SysPayChannelRate> rows, String payMethod, String legalForm) {
        if (payMethod == null || legalForm == null) {
            return null;
        }
        return rows.stream()
                .filter(r -> payMethod.equals(r.getPayMethod()) && legalForm.equals(r.getLegalForm()))
                .max(Comparator.comparingLong(SysPayChannelRate::getEffectiveFrom))
                .orElse(null);
    }

    @Override
    public List<SysPayChannelRate> history(String payChannel) {
        return DataScopeContext.executeWithoutScope(() ->
                mapper.selectList(Wrappers.<SysPayChannelRate>lambdaQuery()
                        .eq(SysPayChannelRate::getPayChannel, payChannel)
                        .orderByDesc(SysPayChannelRate::getEffectiveFrom)));
    }

    @Override
    public SysPayChannelRate add(SysPayChannelRate rate) {
        if (rate.getRateBp() == null || rate.getRateBp() < 0 || rate.getRateBp() > 10000) {
            // 万分比越界多半是把「0.38%」直接写成了 0.38 或 38%，两种都危险
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (rate.getEffectiveFrom() == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (rate.getPayMethod() == null || rate.getPayMethod().isBlank()) {
            rate.setPayMethod(SysPayChannelRate.ANY);
        }
        if (rate.getLegalForm() == null || rate.getLegalForm().isBlank()) {
            rate.setLegalForm(SysPayChannelRate.ANY);
        }
        if (rate.getRateNo() == null || rate.getRateNo().isBlank()) {
            /*
             * **不能用 `"PCR" + currentTimeMillis()`。** 同一毫秒里加两版就撞唯一键 ——
             * 不是理论风险：全量测试里当场撞了一次，而单独跑那个类永远撞不上
             * （慢到不会同毫秒）。运营连点两下也会撞，报错还是「数据库约束冲突」。
             * BizKey.next 里带序号与随机数，全站单号都走它。
             */
            rate.setRateNo(ai.neargo.shop.common.BizKey.next("PCR"));
        }
        rate.setEnabled(true);
        DataScopeContext.executeWithoutScope(() -> mapper.insert(rate));
        return rate;
    }
}
