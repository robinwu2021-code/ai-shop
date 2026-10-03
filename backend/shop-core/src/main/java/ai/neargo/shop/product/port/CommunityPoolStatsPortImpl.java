package ai.neargo.shop.product.port;

import ai.neargo.shop.product.entity.PrdCommunityPool;
import ai.neargo.shop.product.mapper.ProductMappers;
import ai.neargo.shop.spi.product.CommunityPoolStatsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Component
public class CommunityPoolStatsPortImpl implements CommunityPoolStatsPort {

    private final ProductMappers.CommunityPoolMapper poolMapper;

    public CommunityPoolStatsPortImpl(ProductMappers.CommunityPoolMapper poolMapper) {
        this.poolMapper = poolMapper;
    }

    @Override
    public Map<String, PoolStat> byCommunity() {
        /*
         * **不绕，但也没有域可走** —— `prd_community_pool` 没有登记进 DataScopeRegistration。
         *
         * 试过登记：全量当场红两条（ConsumerScopeParityTest / DataScopeFlowTest），
         * 症状是**游客有数据、登录是空的** —— 池是 C 端可见性的派生索引，
         * C 端会话的维度是 SELF，在池的锚点里找不到，fail-closed 拼出的是 1=0。
         * 与 cmt_community 那条同一个形状，理由记在 ops-data-scope 的
         * OPS_READS_UNREGISTERED_OK 里。
         *
         * ⚠️ 代价：配了数据域的运营在这一屏看到的供给数字是**全平台**口径。
         * 要收紧只能在服务层按域过滤，那是另一单。
         */
        Map<String, Set<String>> merchants = new HashMap<>();
        Map<String, Set<String>> goods = new HashMap<>();
        for (PrdCommunityPool row : poolMapper.selectList(Wrappers.<PrdCommunityPool>lambdaQuery())) {
            merchants.computeIfAbsent(row.getCommunityNo(), k -> new HashSet<>()).add(row.getEntityNo());
            goods.computeIfAbsent(row.getCommunityNo(), k -> new HashSet<>()).add(row.getGoodsNo());
        }
        Map<String, PoolStat> out = new HashMap<>();
        for (var e : merchants.entrySet()) {
            out.put(e.getKey(), new PoolStat(e.getValue().size(),
                    goods.getOrDefault(e.getKey(), Set.of()).size()));
        }
        return out;
    }
}
