package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.profile.Capability;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvLedger;
import ai.neargo.inventory.domain.entity.InvSerial;
import ai.neargo.inventory.domain.entity.InvStockEpisode;
import ai.neargo.inventory.domain.entity.InvStockHold;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LedgerMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.SerialMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockEpisodeMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockHoldMapper;
import ai.neargo.inventory.domain.service.AuditService;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.SerialService;
import ai.neargo.inventory.domain.support.InvEnums;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ConditionalOnInventory
@Service
public class SerialServiceImpl implements SerialService {

    private static final int MAX_PAGE = 500;

    private final HostDocumentSupport support;
    private final SerialMapper serialMapper;
    private final StockEpisodeMapper episodeMapper;
    private final StockHoldMapper holdMapper;
    private final LedgerMapper ledgerMapper;
    private final AuditService audit;

    public SerialServiceImpl(HostDocumentSupport support, SerialMapper serialMapper, StockEpisodeMapper episodeMapper,
                             StockHoldMapper holdMapper, LedgerMapper ledgerMapper, AuditService audit) {
        this.support = support;
        this.serialMapper = serialMapper;
        this.episodeMapper = episodeMapper;
        this.holdMapper = holdMapper;
        this.ledgerMapper = ledgerMapper;
        this.audit = audit;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void assemble(Caller caller, String childSerialNo, String parentSerialNo) {
        support.requireCapability(caller.ownerId(), Capability.SERIAL);
        if (childSerialNo == null || childSerialNo.equals(parentSerialNo)) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "serialNo");
        }
        // 固定锁序：两个 SN 按号升序加锁
        boolean childFirst = childSerialNo.compareTo(parentSerialNo) < 0;
        InvSerial first = support.lockSerial(caller.ownerId(), childFirst ? childSerialNo : parentSerialNo);
        InvSerial second = support.lockSerial(caller.ownerId(), childFirst ? parentSerialNo : childSerialNo);
        InvSerial child = childFirst ? first : second;
        InvSerial parent = childFirst ? second : first;
        InvStockEpisode childEp = inStock(caller.ownerId(), child);
        InvStockEpisode parentEp = inStock(caller.ownerId(), parent);
        if (childEp == null || parentEp == null || !childEp.getLocationId().equals(parentEp.getLocationId())) {
            throw InventoryException.of(InventoryError.SERIAL_CONFLICT, childSerialNo + ":not_in_stock");
        }
        // 只一层：上级件不能再挂别人下面，下级件不能已经有下级
        boolean childHasChildren = serialMapper.exists(Wrappers.<InvSerial>lambdaQuery()
                .eq(InvSerial::getOwnerId, caller.ownerId()).eq(InvSerial::getParentSerialId, child.getSerialId()));
        if (parent.getParentSerialId() != null || childHasChildren) {
            throw InventoryException.of(InventoryError.SERIAL_CONFLICT, childSerialNo + ":parent");
        }
        String before = child.getParentSerialId();
        serialMapper.update(null, Wrappers.<InvSerial>lambdaUpdate()
                .set(InvSerial::getParentSerialId, parent.getSerialId())
                .set(InvSerial::getUpdatedBy, HostDocumentSupport.operator(caller))
                .eq(InvSerial::getOwnerId, caller.ownerId()).eq(InvSerial::getSerialId, child.getSerialId()));
        audit.record(caller, "serial.assemble", "SERIAL", childSerialNo, before, parentSerialNo, null);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void disassemble(Caller caller, String childSerialNo) {
        support.requireCapability(caller.ownerId(), Capability.SERIAL);
        InvSerial child = support.lockSerial(caller.ownerId(), childSerialNo);
        if (inStock(caller.ownerId(), child) == null) {
            throw InventoryException.of(InventoryError.SERIAL_CONFLICT, childSerialNo + ":not_in_stock");
        }
        if (child.getParentSerialId() == null) {
            return;
        }
        // updateById 会跳过 null 字段 —— 「拆下来」必须显式写 null
        serialMapper.update(null, Wrappers.<InvSerial>lambdaUpdate()
                .set(InvSerial::getParentSerialId, null)
                .set(InvSerial::getUpdatedBy, HostDocumentSupport.operator(caller))
                .eq(InvSerial::getOwnerId, caller.ownerId()).eq(InvSerial::getSerialId, child.getSerialId()));
        audit.record(caller, "serial.disassemble", "SERIAL", childSerialNo, child.getParentSerialId(), null, null);
    }

    @Override
    public List<InStock> inStock(String ownerId, String itemId, String locationId, String parentSerialNo) {
        List<InvStockEpisode> episodes = episodeMapper.selectList(Wrappers.<InvStockEpisode>lambdaQuery()
                .eq(InvStockEpisode::getOwnerId, ownerId)
                .eq(InvStockEpisode::getStatus, "OPEN")
                .eq(itemId != null, InvStockEpisode::getItemId, itemId)
                .eq(locationId != null, InvStockEpisode::getLocationId, locationId)
                .orderByAsc(InvStockEpisode::getId));
        if (episodes.isEmpty()) {
            return List.of();
        }
        Map<String, InvSerial> serials = serialMapper.selectList(Wrappers.<InvSerial>lambdaQuery()
                        .eq(InvSerial::getOwnerId, ownerId)
                        .in(InvSerial::getSerialId, episodes.stream().map(InvStockEpisode::getSerialId).toList()))
                .stream().collect(Collectors.toMap(InvSerial::getSerialId, s -> s));
        Map<String, String> numbers = new HashMap<>();
        List<String> parentIds = serials.values().stream().map(InvSerial::getParentSerialId)
                .filter(p -> p != null).distinct().toList();
        if (!parentIds.isEmpty()) {
            serialMapper.selectList(Wrappers.<InvSerial>lambdaQuery()
                            .eq(InvSerial::getOwnerId, ownerId).in(InvSerial::getSerialId, parentIds))
                    .forEach(p -> numbers.put(p.getSerialId(), p.getSerialNo()));
        }
        Map<String, List<String>> holds = holdMapper.selectList(Wrappers.<InvStockHold>lambdaQuery()
                        .eq(InvStockHold::getOwnerId, ownerId)
                        .isNotNull(InvStockHold::getSerialId)
                        .eq(InvStockHold::getStatus, InvEnums.HoldStatus.ACTIVE))
                .stream().collect(Collectors.groupingBy(InvStockHold::getSerialId,
                        Collectors.mapping(InvStockHold::getReason, Collectors.toList())));
        return episodes.stream()
                .map(e -> {
                    InvSerial s = serials.get(e.getSerialId());
                    String parentNo = s.getParentSerialId() == null ? null : numbers.get(s.getParentSerialId());
                    return new InStock(s.getSerialNo(), e.getItemId(), e.getLocationId(), parentNo, e.getEpisodeId(),
                            e.getGateRef(), holds.getOrDefault(s.getSerialId(), List.of()));
                })
                .filter(x -> parentSerialNo == null || parentSerialNo.equals(x.parentSerialNo()))
                .toList();
    }

    @Override
    public List<InvLedger> ledger(String ownerId, String itemId, String locationId, String docNo, Long afterId,
                                  int limit) {
        int page = Math.max(1, Math.min(limit, MAX_PAGE));
        return ledgerMapper.selectList(Wrappers.<InvLedger>lambdaQuery()
                .eq(InvLedger::getOwnerId, ownerId)
                .eq(itemId != null, InvLedger::getItemId, itemId)
                .eq(locationId != null, InvLedger::getLocationId, locationId)
                .eq(docNo != null, InvLedger::getDocNo, docNo)
                .gt(afterId != null, InvLedger::getId, afterId)
                // 游标一律按 id：时钟回拨会让按时间排序的游标漏行
                .orderByAsc(InvLedger::getId)
                .last("LIMIT " + page));
    }

    private InvStockEpisode inStock(String ownerId, InvSerial serial) {
        if (serial == null || serial.getActiveEpisodeId() == null) {
            return null;
        }
        return episodeMapper.selectOne(Wrappers.<InvStockEpisode>lambdaQuery()
                .eq(InvStockEpisode::getOwnerId, ownerId)
                .eq(InvStockEpisode::getEpisodeId, serial.getActiveEpisodeId()));
    }
}
