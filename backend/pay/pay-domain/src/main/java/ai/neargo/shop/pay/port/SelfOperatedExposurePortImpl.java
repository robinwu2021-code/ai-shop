package ai.neargo.shop.pay.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.spi.settle.SelfOperatedExposurePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link SelfOperatedExposurePort} 的实现。
 *
 * <p>金额取 {@code net_minor}（商家实得），不取 {@code gross_minor} ——
 * 需要进项发票的是<b>实际付给供应商的那笔钱</b>，佣金与服务费是平台自己的收入，
 * 不构成成本。取错的话敞口会被高估，而运营会照着这个数去做处置。
 */
@Component
public class SelfOperatedExposurePortImpl implements SelfOperatedExposurePort {

    private final BillMapper billMapper;

    public SelfOperatedExposurePortImpl(BillMapper billMapper) {
        this.billMapper = billMapper;
    }

    @Override
    public Map<String, Exposure> selfOperatedExposure(Collection<String> entityNos) {
        if (entityNos == null || entityNos.isEmpty()) {
            return Map.of();
        }
        /*
         * executeWithoutScope：调用方是运营侧的风险清单，本来就要跨全部商家看。
         * 不解除数据域的话 where 会被追加成匹配不到任何行 —— 而查询返回空**不报错**，
         * 页面会显示「没有风险」。这正是这份清单最不能出的错。
         */
        List<StlBill> rows = DataScopeContext.executeWithoutScope(() ->
                billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                        .select(StlBill::getEntityNo, StlBill::getNetMinor)
                        // 用账单上的**快照**，不是门店当前模式：
                        // 门店改了模式，历史单的税务性质不会跟着改
                        .eq(StlBill::getBusinessMode, "SELF_OPERATED")
                        .in(StlBill::getEntityNo, entityNos)));

        Map<String, long[]> acc = new HashMap<>();
        for (StlBill b : rows) {
            long[] a = acc.computeIfAbsent(b.getEntityNo(), k -> new long[2]);
            a[0] += 1;
            a[1] += b.getNetMinor() == null ? 0L : b.getNetMinor();
        }
        Map<String, Exposure> out = new HashMap<>();
        acc.forEach((k, a) -> out.put(k, new Exposure(a[0], a[1])));
        return out;
    }
}
