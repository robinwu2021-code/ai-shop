package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdCommunityPool;
import ai.neargo.shop.product.mapper.ProductMappers;
import ai.neargo.shop.spi.user.SettlementRefPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 商品域这边指向聚落的引用：**社区货架**（{@code prd_community_pool}）。
 *
 * <p>它决定「这个小区的买家首页上摆哪些货」。合并时不跟着改的话，
 * 被并掉的那条聚落上的货架整片失效 —— 买家进到留下来的那个小区，
 * 看到的是一个空货架，而商家的商品列表里每一件都好端端地「在售」。
 */
@Component
public class ProductSettlementRefPortImpl implements SettlementRefPort {

    private final ProductMappers.CommunityPoolMapper poolMapper;

    public ProductSettlementRefPortImpl(ProductMappers.CommunityPoolMapper poolMapper) {
        this.poolMapper = poolMapper;
    }

    @Override
    public int repointSettlement(String fromNo, String intoNo) {
        if (fromNo == null || intoNo == null || fromNo.isBlank() || fromNo.equals(intoNo)) {
            return 0;
        }
        return DataScopeContext.executeWithoutScope(() -> {
            List<PrdCommunityPool> rows = poolMapper.selectList(Wrappers.<PrdCommunityPool>lambdaQuery()
                    .eq(PrdCommunityPool::getCommunityNo, fromNo));
            if (rows.isEmpty()) {
                return 0;
            }
            // 同一件货在两条聚落上各摆了一次时，直接改会撞 (community_no, goods_no) 唯一键
            Set<String> already = new HashSet<>(poolMapper
                    .selectList(Wrappers.<PrdCommunityPool>lambdaQuery()
                            .eq(PrdCommunityPool::getCommunityNo, intoNo))
                    .stream().map(PrdCommunityPool::getGoodsNo).toList());
            int n = 0;
            for (PrdCommunityPool r : rows) {
                if (already.contains(r.getGoodsNo())) {
                    poolMapper.deleteById(r.getId());
                } else {
                    r.setCommunityNo(intoNo);
                    poolMapper.updateById(r);
                    already.add(r.getGoodsNo());
                }
                n++;
            }
            return n;
        });
    }
}
