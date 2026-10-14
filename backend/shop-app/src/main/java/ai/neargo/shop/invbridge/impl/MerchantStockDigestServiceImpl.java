package ai.neargo.shop.invbridge.impl;

import ai.neargo.shop.invbridge.MerchantStockDigestService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.inventory.dto.InventoryVOs.SummaryVO;
import ai.neargo.shop.inventory.entity.InvLedger;
import ai.neargo.shop.inventory.entity.InvOwner;
import ai.neargo.shop.inventory.mapper.InventoryMappers.LedgerMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.shop.inventory.service.StockQueryService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 概况的实现。
 *
 * <p>物料那几个数复用本域已有的 {@code StockQueryService.summary} —— <b>不另算一遍</b>：
 * 另算的迟早出现「概况说 12 条、点进去只有 11 条」，而两个数都出自这套代码。
 */
@Service
@ConditionalOnProperty(prefix = "shop.inventory", name = "enabled", havingValue = "true")
public class MerchantStockDigestServiceImpl implements MerchantStockDigestService {

    private final OwnerMapper ownerMapper;
    private final LedgerMapper ledgerMapper;
    private final StockQueryService stock;

    public MerchantStockDigestServiceImpl(OwnerMapper ownerMapper, LedgerMapper ledgerMapper,
                                          StockQueryService stock) {
        this.ownerMapper = ownerMapper;
        this.ledgerMapper = ledgerMapper;
        this.stock = stock;
    }

    @Override
    public Digest of(String entityNo) {
        InvOwner owner = ownerMapper.selectOne(Wrappers.<InvOwner>lambdaQuery()
                .eq(InvOwner::getExternalRef, entityNo)
                .last("limit 1"));
        if (owner == null) {
            // 「这家还没搬进进销存」与「搬了但一条账都没有」是两件事，
            // 前者要去看投影链路，后者要去催商家。返回 null，由界面分开说
            return null;
        }
        SummaryVO s = stock.summary(owner.getOwnerId(), null);

        Map<String, Object> row = one(ledgerMapper.selectMaps(Wrappers.<InvLedger>query()
                .select("COUNT(*) AS c", "MAX(occurred_at) AS last_at")
                .eq("owner_id", owner.getOwnerId())));

        return new Digest(entityNo, owner.getOwnerId(),
                s.itemCount(), s.shortageCount(), s.staleCount(),
                num(row.get("c")), time(row.get("last_at")), s.openCountNo());
    }

    /** 聚合查询可能返回「一个 null 元素」而不是空列表（全列为 null 的行被映射成 null） */
    private static Map<String, Object> one(List<Map<String, Object>> rows) {
        Map<String, Object> row = rows.isEmpty() ? null : rows.get(0);
        return row == null ? Map.of() : row;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static LocalDateTime time(Object o) {
        return o instanceof LocalDateTime t ? t
                : o instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : null;
    }
}
