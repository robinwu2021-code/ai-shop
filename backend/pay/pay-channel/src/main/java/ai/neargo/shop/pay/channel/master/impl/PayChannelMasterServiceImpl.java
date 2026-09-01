package ai.neargo.shop.pay.channel.master.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.channel.entity.SysPayChannel;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import ai.neargo.shop.pay.channel.master.PayChannelMasterService;

/**
 * 通道主数据的读取。<b>行为与搬家前逐字一致</b> ——
 * 从 {@code MasterDataServiceImpl} 整块搬过来（2026-09-01），
 * 包括那两条「查不到怎么办」的判断与它们的理由。
 *
 * <p>搬家时刻意不改任何判断：<b>结构变化与行为变化混在一起的话，
 * 出问题时不知道该回滚哪一个</b>。要改的话等搬完、绿了、单独一次。
 */
@Service
public class PayChannelMasterServiceImpl implements PayChannelMasterService {

    private static final String DEFAULT_MARKET = "CN";

    private final PayChannelMapper channelMapper;

    public PayChannelMasterServiceImpl(PayChannelMapper channelMapper) {
        this.channelMapper = channelMapper;
    }

    @Override
    public Optional<SysPayChannel> find(String payChannel) {
        if (payChannel == null || payChannel.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(DataScopeContext.executeWithoutScope(() ->
                channelMapper.selectOne(Wrappers.<SysPayChannel>lambdaQuery()
                        .eq(SysPayChannel::getPayChannel, payChannel)
                        .last("limit 1"))));
    }

    @Override
    public List<SysPayChannel> enabled(String market) {
        String m = market == null || market.isBlank() ? DEFAULT_MARKET : market;
        var rows = DataScopeContext.executeWithoutScope(() ->
                channelMapper.selectList(Wrappers.<SysPayChannel>lambdaQuery()
                        .eq(SysPayChannel::getEnabled, true)
                        .orderByAsc(SysPayChannel::getId)));
        return rows.stream().filter(r -> marketAllowed(r.getMarkets(), m)).toList();
    }

    @Override
    public boolean supportsSubsidy(String payChannel) {
        // 查不到按 false：这个字段建出来就是为了拦截，而「查不到 = 支持」
        // 会让不具备补差能力的通道静默开出积分抵扣 —— 症状是商家账上少一笔钱
        return find(payChannel)
                .map(r -> Boolean.TRUE.equals(r.getSupportsSubsidy()))
                .orElse(false);
    }

    @Override
    public String settleCycle(String payChannel) {
        // 查不到给 null 不兜 T+1：兜了之后「没配过」与「配成 T+1」在调用方看来一样，
        // 而这两者在排查「为什么这家的钱等这么久」时是完全不同的答案
        return find(payChannel)
                .map(SysPayChannel::getSettleCycle)
                .filter(c -> !c.isBlank())
                .orElse(null);
    }

    @Override
    public List<SysPayChannel> all() {
        return DataScopeContext.executeWithoutScope(() ->
                channelMapper.selectList(Wrappers.<SysPayChannel>lambdaQuery()
                        .orderByAsc(SysPayChannel::getId)));
    }

    @Override
    public SysPayChannel updateSettings(String payChannel, Boolean enabled, String markets,
                                        String currency, String settleCycle) {
        SysPayChannel row = find(payChannel)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        // 逐字段判 null：没传的不动。整体覆盖的话，只想改开关的一次请求会把市场清空
        if (enabled != null) {
            row.setEnabled(enabled);
        }
        if (markets != null) {
            row.setMarkets(markets);
        }
        if (currency != null) {
            row.setCurrency(currency);
        }
        if (settleCycle != null) {
            row.setSettleCycle(settleCycle);
        }
        DataScopeContext.executeWithoutScope(() -> channelMapper.updateById(row));
        return row;
    }

    /** markets 存的是 JSON 数组字面量；空表示不限市场 */
    private static boolean marketAllowed(String markets, String market) {
        if (markets == null || markets.isBlank()) {
            return true;
        }
        for (String token : markets.replaceAll("[\\[\\]\"\\\\\\s]", "").split(",")) {
            if (token.equals(market)) {
                return true;
            }
        }
        return false;
    }

}
