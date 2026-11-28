package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 主体的评分：由它名下各门店的评分按评价数加权汇总，现算（TDD-平台基础库换表 第 2 步 A，Q1）。
 *
 * <p>评分原先在主体行和门店行上各存一份、各自重算；新库主体上去掉了这五列，门店上保留。
 * 汇总口径：综合分与三维度分都按门店的评价数加权；名下没有一条评价时回到中位分 {@link #UNRATED}，
 * 与门店、商品未评价时同一条规矩（0 分会让新店在按评分排的列表里垫底）。评价数是各门店之和。
 */
@Component
public class EntityRatings {

    /** 未评价时的中位分（×10，即 5.0） */
    public static final int UNRATED = 50;

    private final MchStoreMapper storeMapper;

    public EntityRatings(MchStoreMapper storeMapper) {
        this.storeMapper = storeMapper;
    }

    public Rating of(String entityNo) {
        return of(List.of(entityNo)).getOrDefault(entityNo, Rating.NONE);
    }

    /** 每个主体一份；名下没有门店的也给中位分 */
    public Map<String, Rating> of(Collection<String> entityNos) {
        Map<String, Rating> out = new HashMap<>();
        if (entityNos.isEmpty()) {
            return out;
        }
        List<MchStore> stores = DataScopeContext.executeWithoutScope(() -> storeMapper.selectList(
                Wrappers.<MchStore>lambdaQuery()
                        .select(MchStore::getEntityNo, MchStore::getRating, MchStore::getRatingCount,
                                MchStore::getScoreGoods, MchStore::getScoreService, MchStore::getScoreSpeed)
                        .in(MchStore::getEntityNo, entityNos)));
        Map<String, long[]> sums = new HashMap<>();   // 评价数、综合、商品、服务、时效（各自乘评价数）
        for (MchStore s : stores) {
            int n = s.getRatingCount() == null ? 0 : s.getRatingCount();
            if (n <= 0) {
                continue;
            }
            long[] acc = sums.computeIfAbsent(s.getEntityNo(), k -> new long[5]);
            acc[0] += n;
            acc[1] += (long) nz(s.getRating()) * n;
            acc[2] += (long) nz(s.getScoreGoods()) * n;
            acc[3] += (long) nz(s.getScoreService()) * n;
            acc[4] += (long) nz(s.getScoreSpeed()) * n;
        }
        for (String e : entityNos) {
            long[] acc = sums.get(e);
            out.put(e, acc == null ? Rating.NONE : new Rating(avg(acc[1], acc[0]), (int) acc[0],
                    avg(acc[2], acc[0]), avg(acc[3], acc[0]), avg(acc[4], acc[0])));
        }
        return out;
    }

    private static int avg(long weighted, long count) {
        return (int) Math.round((double) weighted / count);
    }

    private static int nz(Integer v) {
        return v == null ? UNRATED : v;
    }

    /** 分数都是 ×10 的整数（50 = 5.0） */
    public record Rating(int ratingX10, int count, int goodsX10, int serviceX10, int speedX10) {
        public static final Rating NONE = new Rating(UNRATED, 0, UNRATED, UNRATED, UNRATED);
    }
}
