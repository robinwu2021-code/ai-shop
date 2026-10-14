package ai.neargo.shop.invbridge.impl;

import ai.neargo.shop.invbridge.GoodsChainService;
import ai.neargo.shop.invbridge.MerchantChainService.Stuck;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.inventory.dto.InventoryVOs.BalanceVO;
import ai.neargo.shop.inventory.entity.InvItemRef;
import ai.neargo.shop.inventory.entity.InvOwner;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemRefMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.shop.inventory.service.StockQueryService;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 单商品全链路的实现。
 *
 * <p>**读 prd_goods 时接数据域**（与链条画像同一条口径）：配了商家域的运营
 * 点开一个域外商品，该得到「查不到」，而不是一份他本来看不到的档案。
 * 进销存那侧没有域锚点，但入口在商品这一侧 —— 域在一处生效就够了。
 */
@Service
@ConditionalOnProperty(prefix = "shop.inventory", name = "enabled", havingValue = "true")
public class GoodsChainServiceImpl implements GoodsChainService {

    /** 一个 SPU 下最多看多少条余额。SKU 矩阵再大也不该把整页拖垮 */
    private static final int BALANCE_MAX = 500;

    private final GoodsMapper goodsMapper;
    private final SkuMapper skuMapper;
    private final OwnerMapper ownerMapper;
    private final ItemRefMapper itemRefMapper;
    private final StockQueryService stock;

    public GoodsChainServiceImpl(GoodsMapper goodsMapper, SkuMapper skuMapper,
                                 OwnerMapper ownerMapper, ItemRefMapper itemRefMapper,
                                 StockQueryService stock) {
        this.goodsMapper = goodsMapper;
        this.skuMapper = skuMapper;
        this.ownerMapper = ownerMapper;
        this.itemRefMapper = itemRefMapper;
        this.stock = stock;
    }

    @Override
    public GoodsChain of(String goodsNo) {
        PrdGoods g = goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                .eq(PrdGoods::getGoodsNo, goodsNo)
                .eq(PrdGoods::getDeleted, 0));
        if (g == null) {
            return null;
        }
        List<PrdSku> skus = skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                .eq(PrdSku::getGoodsNo, goodsNo)
                .eq(PrdSku::getDeleted, 0));
        int sold = skus.stream().mapToInt(s -> nz(s.getSoldCount())).sum();
        Set<String> skuNos = new HashSet<>();
        for (PrdSku s : skus) {
            skuNos.add(s.getSkuNo());
        }

        /*
         * 进销存那侧从 owner 反查。**不能顺着 SKU 调 itemIdOf** ——
         * 那个方法查不到会**创建**（搬运需要这个语义），于是「运营点开一个商品」
         * 就往进销存库里凭空写入一批空余额物料。一个只读的界面不该改数据。
         * 与 InventoryHealthService 同一条理由。
         */
        int booked = 0;
        int onHand = 0;
        int available = 0;
        InvOwner owner = DataScopeContext.executeWithoutScope(() ->
                ownerMapper.selectOne(Wrappers.<InvOwner>lambdaQuery()
                        .eq(InvOwner::getExternalRef, g.getEntityNo())
                        .last("limit 1")));
        if (owner != null && !skuNos.isEmpty()) {
            Set<String> itemIds = new HashSet<>();
            for (InvItemRef ref : itemRefMapper.selectList(Wrappers.<InvItemRef>lambdaQuery()
                    .eq(InvItemRef::getOwnerId, owner.getOwnerId())
                    .in(InvItemRef::getRef, skuNos))) {
                itemIds.add(ref.getItemId());
            }
            booked = itemIds.size();
            for (BalanceVO b : stock.balances(owner.getOwnerId(), null, "all", BALANCE_MAX)) {
                if (itemIds.contains(b.itemId())) {
                    onHand += b.onHand();
                    available += b.available();
                }
            }
        }

        return new GoodsChain(g.getGoodsNo(), g.getTitle(), g.getEntityNo(),
                g.getAuditStatus(), Boolean.TRUE.equals(g.getOnSale()),
                skus.size(), booked, onHand, available, sold,
                stuckAt(g, skus.size(), booked, onHand));
    }

    /**
     * 单件的卡点。**词与链条画像同一套**，但判据是这一件的：
     *
     * <p>多一档 {@code NO_ACCOUNT} 的细分意义 —— 这里能看出「建了一部分」：
     * {@code bookedSkus < skuCount} 说明投影只搬过去一半，
     * 而那在商家端的表现是「有些规格盘得着、有些盘不着」，极难自查。
     */
    private static String stuckAt(PrdGoods g, int skuCount, int booked, int onHand) {
        if (!"APPROVED".equals(g.getAuditStatus())) {
            return "AUDITING".equals(g.getAuditStatus()) ? Stuck.IN_AUDIT : Stuck.NOT_ON_SALE;
        }
        if (!Boolean.TRUE.equals(g.getOnSale())) {
            return Stuck.NOT_ON_SALE;
        }
        if (skuCount == 0 || booked < skuCount) {
            return Stuck.NO_ACCOUNT;
        }
        if (onHand == 0) {
            return Stuck.NO_INBOUND;
        }
        return null;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
