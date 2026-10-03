package ai.neargo.shop.pay.market.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entity.SysMarket;
import ai.neargo.shop.pay.mapper.SettleMappers.MarketMapper;
import ai.neargo.shop.pay.market.MarketService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class MarketServiceImpl implements MarketService {

    private final MarketMapper marketMapper;

    public MarketServiceImpl(MarketMapper marketMapper) {
        this.marketMapper = marketMapper;
    }

    @Override
    public List<MarketRow> all() {
        return rows(false);
    }

    @Override
    public List<MarketRow> enabled() {
        return rows(true);
    }

    private List<MarketRow> rows(boolean onlyEnabled) {
        return DataScopeContext.executeWithoutScope(() -> marketMapper.selectList(
                        Wrappers.<SysMarket>lambdaQuery()
                                .eq(onlyEnabled, SysMarket::getEnabled, true)
                                .orderByAsc(SysMarket::getSortNo)))
                .stream().map(MarketServiceImpl::toRow).toList();
    }

    @Override
    public Optional<MarketRow> find(String market) {
        if (market == null || market.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(DataScopeContext.executeWithoutScope(() ->
                        marketMapper.selectOne(Wrappers.<SysMarket>lambdaQuery()
                                .eq(SysMarket::getMarket, market).last("LIMIT 1"))))
                .map(MarketServiceImpl::toRow);
    }

    @Override
    public String currencyOf(String market) {
        // 查不到给 null，**不兜底成 CNY** —— 端上据此不显示金额，
        // 好过按 2 位小数把日元显示错 100 倍
        return find(market).map(MarketRow::currency).orElse(null);
    }

    @Override
    @Transactional("payTxManager")
    public MarketRow saveRate(String market, double displayRate, boolean enabled,
                              String operatorNo) {
        SysMarket row = DataScopeContext.executeWithoutScope(() ->
                marketMapper.selectOne(Wrappers.<SysMarket>lambdaQuery()
                        .eq(SysMarket::getMarket, market).last("LIMIT 1")));
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        /*
         * **只改汇率与启停。**币种、小数位、时区都不在这里 ——
         * 改币种等于换账本，而历史账不会跟着变；
         * 改小数位会让所有既有金额的含义变掉，且不报错。
         *
         * 要改的话是「关掉这个市场、开一个新的」，那是个明确的运营决定，
         * 而不是在一个编辑框里改一个数。
         */
        SysMarket patch = new SysMarket();
        patch.setId(row.getId());
        patch.setDisplayRate(BigDecimal.valueOf(displayRate));
        patch.setEnabled(enabled);
        patch.setUpdatedBy(operatorNo);
        DataScopeContext.executeWithoutScope(() -> marketMapper.updateById(patch));
        return find(market).orElseThrow();
    }

    private static MarketRow toRow(SysMarket m) {
        return new MarketRow(m.getMarket(), m.getName(), m.getCurrency(),
                m.getCurrencyScale() == null ? 2 : m.getCurrencyScale(),
                m.getTimeZone(),
                m.getDisplayRate() == null ? 1d : m.getDisplayRate().doubleValue(),
                Boolean.TRUE.equals(m.getEnabled()),
                m.getSortNo() == null ? 0 : m.getSortNo());
    }
}
