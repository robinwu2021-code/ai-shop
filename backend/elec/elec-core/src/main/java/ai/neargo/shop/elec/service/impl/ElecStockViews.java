package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.dto.RfqDtos.OpsSource;
import ai.neargo.shop.elec.dto.SupplierDtos.PriceTier;
import ai.neargo.shop.elec.dto.SupplierDtos.StockView;
import ai.neargo.shop.elec.entity.ElcStock;
import ai.neargo.shop.elec.mapper.ElecMappers.SourceRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 库存行 → 视图。供应商自己看、运营看都走这里 —— 两边对「阶梯价怎么解」「过没过期」口径一致。
 *
 * <p>从 {@code ElecSupplierServiceImpl} 抽出来的：运营端要同一套转换，复制一份的话，
 * 哪天改了阶梯价格式只改一边，运营看到的价就和供应商自己看到的对不上。
 */
@ConditionalOnElec
@Component
public class ElecStockViews {

    private static final Logger log = LoggerFactory.getLogger(ElecStockViews.class);

    /** 过期的在售行显示成这个；库里仍是 ON（没下架，只是没续期） */
    static final String SHOWN_EXPIRED = "EXPIRED";

    private final ObjectMapper json;

    public ElecStockViews(ObjectMapper json) {
        this.json = json;
    }

    public StockView view(ElcStock r, LocalDate today) {
        return new StockView(r.getStockNo(), r.getMpnRaw(), r.getMfrRaw(), r.getQty(), r.getDateCode(), r.getPkg(),
                r.getMoq(), r.getSpq(), tiers(r.getPriceTiers()), r.getPriceE6(), r.getCurrency(),
                Boolean.TRUE.equals(r.getTaxIncluded()), r.getPacking(), r.getCondGrade(), r.getLeadDays(),
                r.getRegion(), r.getValidUntil(),
                r.getValidUntil().isBefore(today) ? SHOWN_EXPIRED : ElcStock.STATUS_ON);
    }

    public OpsSource source(SourceRow s) {
        return new OpsSource(s.getSupplierNo(), s.getCompanyName(), s.getContactPhone(), s.getStockNo(),
                s.getQty() == null ? 0 : s.getQty(), s.getDateCode(), s.getMoq(), s.getSpq(),
                tiers(s.getPriceTiers()), s.getPriceE6(), s.getCurrency(), Boolean.TRUE.equals(s.getTaxIncluded()),
                s.getPacking(), s.getCondGrade(), s.getLeadDays(), s.getRegion(), s.getValidUntil());
    }

    /**
     * 阶梯价 JSON → 列表。<b>解不开就当没报价</b>（返回空列表）：
     * 一条坏 JSON 不该让整页库存打不开。
     */
    public List<PriceTier> tiers(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<Map<String, Long>>>() { })
                    .stream()
                    .filter(m -> m.get("minQty") != null && m.get("e6") != null)
                    .map(m -> new PriceTier(m.get("minQty"), m.get("e6")))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("阶梯价解不开，当没报价处理：{}", e.toString());
            return List.of();
        }
    }
}
