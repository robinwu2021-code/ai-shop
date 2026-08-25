package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.product.entity.PrdCategoryPayMode;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers.CategoryPayModeMapper;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.service.PayModeService;
import ai.neargo.shop.spi.user.QualificationPort;
import ai.neargo.shop.spi.user.StorePayPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 四层取交集的实现。判定顺序**从便宜到贵**：先读商品这一行（本来就要读），
 * 再查类目，最后才问跨域的资质与门店 —— 前面任何一层否掉就不用问后面。
 */
@Service
public class PayModeServiceImpl implements PayModeService {

    private final GoodsMapper goodsMapper;
    private final CategoryPayModeMapper catPayModeMapper;
    private final QualificationPort qualificationPort;
    private final StorePayPort storePayPort;
    private final ObjectMapper json;

    public PayModeServiceImpl(GoodsMapper goodsMapper, CategoryPayModeMapper catPayModeMapper,
                              QualificationPort qualificationPort, StorePayPort storePayPort,
                              ObjectMapper json) {
        this.goodsMapper = goodsMapper;
        this.catPayModeMapper = catPayModeMapper;
        this.qualificationPort = qualificationPort;
        this.storePayPort = storePayPort;
        this.json = json;
    }

    @Override
    public Set<String> availablePayModes(String goodsNo, String storeNo) {
        Set<String> out = new LinkedHashSet<>();
        /*
         * **线上永远在**，不受这四层约束。
         *
         * 不这么做的话，配错任何一层都会出现「这件商品谁也买不了」——
         * 而那比多开一种支付方式糟得多：前者是收入归零，后者只是多一个选项。
         */
        out.add(PayModes.ONLINE);

        PrdGoods goods = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getGoodsNo, goodsNo).last("LIMIT 1")));
        if (goods == null) {
            return out;
        }

        // ④ 商品：商家愿不愿意。读不出来按「只支持线上」处理 —— 与列的默认值一致
        if (!readPayModes(goods.getPayModes()).contains(PayModes.OFFLINE)) {
            return out;
        }
        // ① 平台 × 类目：**没有行即放行**，只有显式插了 allowed=0 才是禁止
        if (!categoryAllows(goods.getCategoryNo(), PayModes.OFFLINE)) {
            return out;
        }
        /*
         * ② 主体资质。短期这是主力那一层。
         *
         * 判据在 QualificationPort 里：**按 expire_at 现算**，不看 status 是否 EXPIRED ——
         * 置那个状态的定时任务在生产根本不跑（只有 api,ops 两个 profile，没有 worker）。
         */
        if (!qualificationPort.hasValidQualification(
                goods.getEntityNo(), QualificationPort.BUSINESS_LICENSE)) {
            return out;
        }
        // ③ 门店：默认关，商家自己开。storeNo 为空按「没开」处理
        if (!storePayPort.offlinePayEnabled(storeNo)) {
            return out;
        }

        out.add(PayModes.OFFLINE);
        return out;
    }

    /**
     * 类目这一层：**没有行即放行**。
     *
     * <p>设计成白名单的话，上线当天得先把 57 个类目全配一遍才有人下得了单 ——
     * 而一期只想用资质那一层做主力。
     */
    private boolean categoryAllows(String categoryNo, String payMode) {
        if (categoryNo == null || categoryNo.isBlank()) {
            return true;
        }
        PrdCategoryPayMode row = DataScopeContext.executeWithoutScope(() ->
                catPayModeMapper.selectOne(Wrappers.<PrdCategoryPayMode>lambdaQuery()
                        .eq(PrdCategoryPayMode::getCategoryNo, categoryNo)
                        .eq(PrdCategoryPayMode::getPayMode, payMode)
                        .last("LIMIT 1")));
        return row == null || !Integer.valueOf(0).equals(row.getAllowed());
    }

    /** 解析不出来按空处理 —— 存量行是 `["ONLINE"]`，脏数据不该让商品变成不可下单。 */
    private Set<String> readPayModes(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return Set.of();
        }
        try {
            List<String> list = json.readValue(jsonArray, new tools.jackson.core.type.TypeReference<List<String>>() { });
            return list == null ? Set.of() : Set.copyOf(list);
        } catch (Exception e) {
            return Set.of();
        }
    }
}
