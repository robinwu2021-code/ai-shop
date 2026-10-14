package ai.neargo.shop.invbridge;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.service.InvManagedService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.StoreCategoryPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「记不记库存」的编排（TDD-商品纳入进销存开关 §3）：商品域判与存，进销存域查在途与余额，两边只在这里碰头。
 *
 * <h2>改为不记库存的三道判</h2>
 * <ol>
 *   <li><b>有在途单据 → 拒绝，整批不动</b>。未收货的进货单、未过账的出库单、已发出的调拨、
 *       线上订单占着待出库、正在盘 —— 这时关掉，货到了没处入、单出不了库。一件被拒整类不动：
 *       半开半关比不动更难收拾。</li>
 *   <li><b>只有库存 → 要店主确认</b>。不逼他先报损：那几件货是真实存在的，报损等于做假账。
 *       确认后物料停用，余额与流水只读保留，改回来原样恢复。</li>
 *   <li>什么都没有 → 直接改。</li>
 * </ol>
 * 改为记库存不用判：物料按需建，旧的停用物料原样恢复。
 *
 * <p>进销存没开（{@code shop.inventory.enabled=false}）时没有在途可查，只存设置。
 */
@Service
public class InvManagedAppService {

    public static final String DONE = "DONE";
    public static final String BLOCKED = "BLOCKED";
    public static final String NEEDS_CONFIRM = "NEEDS_CONFIRM";

    private final InvManagedService invManaged;
    private final MerchantQueryPort merchants;
    private final StoreCategoryPort storeCategories;
    private final ObjectProvider<InventoryAclService> acl;

    public InvManagedAppService(InvManagedService invManaged, MerchantQueryPort merchants,
                                StoreCategoryPort storeCategories, ObjectProvider<InventoryAclService> acl) {
        this.invManaged = invManaged;
        this.merchants = merchants;
        this.storeCategories = storeCategories;
        this.acl = acl;
    }

    /** 切换结果里的一件商品：为什么被拦 / 为什么要确认 */
    public record AffectedGoods(String goodsNo, String title, int onHand,
                                List<InventoryAclService.Blocker> blockers) {
    }

    /**
     * @param status {@link #DONE} 已改 / {@link #BLOCKED} 有在途单据、什么都没改 /
     *               {@link #NEEDS_CONFIRM} 还有库存、带 {@code confirm=true} 再来一次
     * @param goods  BLOCKED 时是有在途单据的商品；NEEDS_CONFIRM 时是还有库存的商品；DONE 时是生效值变了的商品
     */
    public record ModeChange(String status, List<AffectedGoods> goods) {
    }

    /** 本主体各门店经营类目的合集，加上已有商品挂着的类目 —— 类目从门店撤了，货还在 */
    public List<InvManagedService.CategorySetting> categorySettings(String entityNo) {
        Set<String> cats = new LinkedHashSet<>();
        for (String storeNo : merchants.storeNos(entityNo)) {
            cats.addAll(storeCategories.categoryNosOf(storeNo));
        }
        cats.addAll(invManaged.categoryNosWithGoods(entityNo));
        return invManaged.categorySettings(entityNo, cats);
    }

    @Transactional
    public ModeChange setCategory(String entityNo, String categoryNo, boolean managed, boolean confirm,
                                  String operator) {
        if (categoryNo == null || categoryNo.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<PrdGoods> affected = invManaged.goodsAffectedByCategory(entityNo, categoryNo, managed);
        if (!managed) {
            ModeChange stop = gate(entityNo, affected, confirm);
            if (stop != null) {
                return stop;
            }
        }
        invManaged.saveCategory(entityNo, categoryNo, managed, operator);
        affected.forEach(g -> invManaged.publishModeChanged(g, managed));
        return new ModeChange(DONE, brief(affected));
    }

    public List<InvManagedService.GoodsInvMode> goodsModes(String entityNo, Collection<String> goodsNos) {
        return new ArrayList<>(invManaged.modesOf(entityNo, goodsNos).values());
    }

    @Transactional
    public ModeChange setGoods(String entityNo, String goodsNo, String mode, boolean confirm, String operator) {
        PrdGoods g = invManaged.goodsOf(entityNo, goodsNo);
        if (g == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        boolean before = invManaged.isManaged(g);
        boolean after = switch (mode == null ? "" : mode) {
            case PrdGoods.INV_ON -> true;
            case PrdGoods.INV_OFF -> false;
            case PrdGoods.INV_INHERIT -> invManaged.categoryManaged(entityNo, g.getCategoryNo());
            default -> throw BizException.of(ErrorCode.BAD_REQUEST);
        };
        if (before && !after) {
            ModeChange stop = gate(entityNo, List.of(g), confirm);
            if (stop != null) {
                return stop;
            }
        }
        invManaged.saveGoodsMode(g, mode, operator);
        if (before == after) {
            return new ModeChange(DONE, List.of());
        }
        invManaged.publishModeChanged(g, after);
        return new ModeChange(DONE, brief(List.of(g)));
    }

    /** 改为不记之前的两道判。{@code null} = 放行 */
    private ModeChange gate(String entityNo, List<PrdGoods> goods, boolean confirm) {
        InventoryAclService inv = acl.getIfAvailable();
        if (inv == null || goods.isEmpty()) {
            return null;
        }
        List<PrdSku> skus = invManaged.skusOf(goods.stream().map(PrdGoods::getGoodsNo).toList());
        Map<String, InventoryAclService.ItemState> states =
                inv.stateOf(entityNo, skus.stream().map(PrdSku::getSkuNo).toList());
        List<AffectedGoods> blocked = new ArrayList<>();
        List<AffectedGoods> stocked = new ArrayList<>();
        for (PrdGoods g : goods) {
            int onHand = 0;
            boolean busy = false;
            List<InventoryAclService.Blocker> blockers = new ArrayList<>();
            for (PrdSku s : skus) {
                if (!g.getGoodsNo().equals(s.getGoodsNo())) {
                    continue;
                }
                InventoryAclService.ItemState st = states.get(s.getSkuNo());
                if (st == null) {
                    continue;
                }
                onHand += st.onHand();
                busy |= st.onHand() != 0 || st.reserved() != 0;
                st.blockers().stream().filter(b -> !blockers.contains(b)).forEach(blockers::add);
            }
            AffectedGoods a = new AffectedGoods(g.getGoodsNo(), g.getTitle(), onHand, blockers);
            if (!blockers.isEmpty()) {
                blocked.add(a);
            } else if (busy) {
                stocked.add(a);
            }
        }
        if (!blocked.isEmpty()) {
            return new ModeChange(BLOCKED, blocked);
        }
        if (!stocked.isEmpty() && !confirm) {
            return new ModeChange(NEEDS_CONFIRM, stocked);
        }
        return null;
    }

    private static List<AffectedGoods> brief(List<PrdGoods> goods) {
        return goods.stream().map(g -> new AffectedGoods(g.getGoodsNo(), g.getTitle(), 0, List.of())).toList();
    }
}
