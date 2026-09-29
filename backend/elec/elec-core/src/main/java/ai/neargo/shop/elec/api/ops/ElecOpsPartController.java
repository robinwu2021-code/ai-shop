package ai.neargo.shop.elec.api.ops;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.dto.OpsDtos.OpsPartDetail;
import ai.neargo.shop.elec.dto.OpsDtos.OpsPartRow;
import ai.neargo.shop.elec.dto.OpsDtos.OpsStockRow;
import ai.neargo.shop.elec.service.ElecOpsPartService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运营端 · 料号与库存。「某料号谁有货」是运营报价时最常看的一屏。
 *
 * <p>权限码 {@code elec:part:read}：看得到每家的精确数量与电话。
 */
@ConditionalOnElec
@RestController
public class ElecOpsPartController {

    private final ElecOpsPartService parts;

    public ElecOpsPartController(ElecOpsPartService parts) {
        this.parts = parts;
    }

    /** 与买家同一套命中（开头 / 中段 / 「厂牌 + 料号」），但不计入买家需求统计。最多 50 条 */
    @GetMapping("/elec/ops/part")
    public List<OpsPartRow> search(@RequestParam(defaultValue = "") String q) {
        ElecOpsGuard.require(ElecInternal.PERM_PART_READ);
        return parts.search(q);
    }

    @GetMapping("/elec/ops/part/{partNo}")
    public OpsPartDetail detail(@PathVariable String partNo) {
        ElecOpsGuard.require(ElecInternal.PERM_PART_READ);
        return parts.detail(partNo);
    }

    /**
     * @param q          料号开头
     * @param supplierNo 只看这一家
     * @param filter     ALL / EXPIRING / EXPIRED
     */
    @GetMapping("/elec/ops/stock")
    public List<OpsStockRow> stocks(@RequestParam(defaultValue = "") String q,
                                    @RequestParam(required = false) String supplierNo,
                                    @RequestParam(defaultValue = "ALL") String filter,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        ElecOpsGuard.require(ElecInternal.PERM_PART_READ);
        return parts.stocks(q, supplierNo, filter, page, size);
    }
}
