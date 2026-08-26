package ai.neargo.shop.invbridge.impl;

import ai.neargo.shop.invbridge.InventoryHealthService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.inventory.dto.InventoryVOs.BalanceVO;
import ai.neargo.shop.inventory.entity.InvItemRef;
import ai.neargo.shop.inventory.entity.InvOwner;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemRefMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.shop.inventory.service.StockQueryService;
import ai.neargo.shop.inventory.support.InvEnums;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 平台级健康度扫描。**只读两边，一个字节都不写。**
 *
 * <h2>为什么从进销存那侧往平台反查，而不是顺着平台的 SKU 表扫下来</h2>
 * {@code InventoryAclService.ownerIdOf} / {@code itemIdOf} <b>查不到会创建</b>
 * （搬运需要这个语义）。顺着平台 SKU 调它们，等于「运营点开一次健康度」
 * 就往进销存库里凭空写入一批主体与物料 —— 而那批数据永远是空余额，
 * 于是这一页下次会把它们全算成待办。<b>一个只读的界面不该改数据</b>。
 *
 * <p>反过来走还有一个好处：健康度问的是「<b>已经在进销存里的</b>货此刻怎么样」，
 * 从没搬进来的 SKU 本来就不该出现在这一页上。
 *
 * <h2>三类的判据</h2>
 * <ul>
 *   <li><b>NEGATIVE</b> 可用为负。只看进销存这一侧 —— 它不是「少了几件」，
 *       是「还能卖多少」这个数已经没有意义了。</li>
 *   <li><b>ZERO_ON_SALE</b> 可用为零、而商品还挂在架上。<b>这一类必须同时读两边</b>：
 *       「有多少」在进销存库，「还在不在卖」在平台的 {@code prd_goods.on_sale}。
 *       这也是整个类为什么落在装配层的原因。</li>
 *   <li><b>STALE</b> 长期未动销。判据直接用本域 {@code BalanceVO.flags} 里的
 *       {@code STALE} —— <b>不在这里重算一遍 90 天</b>：两处各算一次，
 *       商家端和运营端迟早会对同一件货给出两种说法。</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "shop.inventory", name = "enabled", havingValue = "true")
public class InventoryHealthServiceImpl implements InventoryHealthService {

    /** 一个商家一次最多取多少条余额。扫描上限落在 SKU 数上，这里只防单商家爆量 */
    private static final int PER_OWNER_MAX = 2000;
    private static final String FLAG_STALE = "STALE";

    private final OwnerMapper ownerMapper;
    private final ItemRefMapper itemRefMapper;
    private final StockQueryService query;
    private final SkuMapper skuMapper;
    private final GoodsMapper goodsMapper;
    private final MchEntityMapper merchantMapper;

    public InventoryHealthServiceImpl(OwnerMapper ownerMapper, ItemRefMapper itemRefMapper,
                                      StockQueryService query, SkuMapper skuMapper,
                                      GoodsMapper goodsMapper, MchEntityMapper merchantMapper) {
        this.ownerMapper = ownerMapper;
        this.itemRefMapper = itemRefMapper;
        this.query = query;
        this.skuMapper = skuMapper;
        this.goodsMapper = goodsMapper;
        this.merchantMapper = merchantMapper;
    }

    @Override
    public List<HealthRow> scan(String kind, int limit) {
        boolean all = kind == null || kind.isBlank() || "ALL".equals(kind);
        List<HealthRow> out = new ArrayList<>();

        for (InvOwner owner : ownerMapper.selectList(Wrappers.emptyWrapper())) {
            if (out.size() >= limit) {
                break;
            }
            String entityNo = owner.getExternalRef();
            if (entityNo == null) {
                continue;   // 独立交付时的外部主体：这一页认不出它是谁，跳过好过瞎标一个号
            }
            List<BalanceVO> balances = query.balances(owner.getOwnerId(), null, "all", PER_OWNER_MAX);
            if (balances.isEmpty()) {
                continue;
            }

            Map<String, String> itemToSku = itemToSku(owner.getOwnerId());
            Set<String> onSaleSkus = onSaleSkus(entityNo);
            String merchantName = merchantNameOf(entityNo);

            for (BalanceVO b : balances) {
                if (out.size() >= limit) {
                    break;
                }
                String skuNo = itemToSku.get(b.itemId());
                String row = classify(b, skuNo, onSaleSkus);
                if (row == null || (!all && !row.equals(kind))) {
                    continue;
                }
                out.add(new HealthRow(row, entityNo, merchantName, null,
                        b.itemId(), b.name(), b.specText(),
                        b.onHand(), b.reserved(), b.available(), idleDaysOf(row, b)));
            }
        }
        return out;
    }

    /**
     * 一条余额属于哪一类；都不属于返回 null。
     *
     * <p>顺序即优先级：<b>负库存压过零库存在架</b> —— 两者都成立时，
     * 要人先去处理的是负数那件事。
     */
    private String classify(BalanceVO b, String skuNo, Set<String> onSaleSkus) {
        if (b.available() < 0) {
            return "NEGATIVE";
        }
        if (b.available() == 0 && skuNo != null && onSaleSkus.contains(skuNo)) {
            return "ZERO_ON_SALE";
        }
        if (b.flags() != null && b.flags().contains(FLAG_STALE)) {
            return "STALE";
        }
        return null;
    }

    /** 滞销才有闲置天数；其余两类给 null，界面上显示「—」而不是一个 0 */
    private Integer idleDaysOf(String kind, BalanceVO b) {
        if (!"STALE".equals(kind) || b.lastMovedAt() == null) {
            return null;
        }
        return (int) Duration.between(b.lastMovedAt(), LocalDateTime.now()).toDays();
    }

    /** itemId → skuNo。**反查，不创建** —— 见类注释 */
    private Map<String, String> itemToSku(String ownerId) {
        Map<String, String> map = new HashMap<>();
        for (InvItemRef ref : itemRefMapper.selectList(Wrappers.<InvItemRef>lambdaQuery()
                .eq(InvItemRef::getOwnerId, ownerId)
                .eq(InvItemRef::getRefSystem, InvEnums.RefSystem.AISHOP))) {
            map.put(ref.getItemId(), ref.getRef());
        }
        return map;
    }

    /**
     * 这个商家此刻还挂在架上的 skuNo。
     *
     * <p>{@code executeWithoutScope}：运营看的是<b>全平台</b>，而 prd_* 带数据域 ——
     * 不绕过的话运营自己的域是空集，这一页会永远是空的，且不报错。
     */
    private Set<String> onSaleSkus(String entityNo) {
        List<PrdGoods> goods = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getEntityNo, entityNo)
                        .eq(PrdGoods::getOnSale, true)));
        if (goods.isEmpty()) {
            return Set.of();
        }
        Set<String> goodsNos = new HashSet<>();
        for (PrdGoods g : goods) {
            goodsNos.add(g.getGoodsNo());
        }
        Set<String> skus = new HashSet<>();
        for (PrdSku s : DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .in(PrdSku::getGoodsNo, goodsNos)))) {
            skus.add(s.getSkuNo());
        }
        return skus;
    }

    private String merchantNameOf(String entityNo) {
        MchEntity e = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, entityNo).last("LIMIT 1")));
        return e == null ? null : e.getName();
    }
}
