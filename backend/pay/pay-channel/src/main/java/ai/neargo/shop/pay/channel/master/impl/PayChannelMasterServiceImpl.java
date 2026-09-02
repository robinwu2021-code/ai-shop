package ai.neargo.shop.pay.channel.master.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.channel.entity.SysPayChannel;
import ai.neargo.shop.pay.channel.entity.SysPayChannelMarket;
import ai.neargo.shop.pay.channel.master.PayChannelMasterService;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelMapper;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelMarketMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

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
    private final PayChannelMarketMapper marketMapper;

    public PayChannelMasterServiceImpl(PayChannelMapper channelMapper,
                                      PayChannelMarketMapper marketMapper) {
        this.channelMapper = channelMapper;
        this.marketMapper = marketMapper;
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
        return DataScopeContext.executeWithoutScope(() -> {
            var rows = channelMapper.selectList(Wrappers.<SysPayChannel>lambdaQuery()
                    .eq(SysPayChannel::getEnabled, true)
                    .orderByAsc(SysPayChannel::getId));
            /*
             * 一次把全部关系行捞回来再在内存里分组 —— 通道是个位数，
             * 逐个通道查一次是 N+1 而换不来任何东西。
             */
            var pairs = marketMapper.selectList(Wrappers.emptyWrapper());
            Map<String, Set<String>> byChannel = new HashMap<>();
            for (var p : pairs) {
                byChannel.computeIfAbsent(p.getPayChannel(), k -> new HashSet<>()).add(p.getMarket());
            }
            return rows.stream()
                    .filter(r -> {
                        var allowed = byChannel.get(r.getPayChannel());
                        // 无行 = 不限市场。见 SysPayChannelMarket 的类注释
                        return allowed == null || allowed.isEmpty() || allowed.contains(m);
                    })
                    .toList();
        });
    }

    @Override
    public List<String> marketsOf(String payChannel) {
        if (payChannel == null || payChannel.isBlank()) {
            return List.of();
        }
        return DataScopeContext.executeWithoutScope(() ->
                marketMapper.selectList(Wrappers.<SysPayChannelMarket>lambdaQuery()
                                .eq(SysPayChannelMarket::getPayChannel, payChannel))
                        .stream().map(SysPayChannelMarket::getMarket).sorted().toList());
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
        // markets 不再写 sys_pay_channel 那一列 —— 见下方 replaceMarkets
        if (markets != null) {
            replaceMarkets(payChannel, markets);
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

    /**
     * 整体替换一个通道的市场行。
     *
     * <p>入参仍是 JSON 数组字面量（如 {@code ["CN","TW"]}）—— <b>运营端的契约不变</b>。
     * 解析只剩这一处：去掉 {@code []"\\} 与空白、按逗号切开。
     * 此前同样的正则在 pay-channel 与 shop-core 各有一份，
     * 而 shop-core 那份<b>没有任何调用方</b>。
     *
     * <p><b>先删后插，不做增量比对</b>：整体覆盖是运营的语义
     * （「这个通道就在这几个市场」），而增量比对会让「取消一个市场」
     * 这个动作没有表达方式。
     */
    private void replaceMarkets(String payChannel, String markets) {
        DataScopeContext.executeWithoutScope(() -> {
            marketMapper.deleteByChannel(payChannel);
            for (String token : markets.replaceAll("[\\[\\]\"\\\\\\s]", "").split(",")) {
                if (token.isBlank()) {
                    continue;
                }
                SysPayChannelMarket row = new SysPayChannelMarket();
                row.setPayChannel(payChannel);
                row.setMarket(token);
                marketMapper.insert(row);
            }
            return null;
        });
    }

}
