package ai.neargo.shop.inventory.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.entity.InvOutboundLine;
import ai.neargo.shop.inventory.entity.InvOutboundOrder;
import ai.neargo.shop.inventory.entity.InvOwner;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboundLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboundOrderMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.OutboundService;
import ai.neargo.shop.spi.inventory.InventoryProductionPort;
import ai.neargo.shop.spi.inventory.InventoryProductionFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Production issues use the original outbound owner; no FOOD stock balance or second cost ledger is created. */
@Service
@ConditionalOnInventory
public class InventoryProductionPortImpl implements InventoryProductionPort {
    private final InventoryAclService acl;
    private final MaterialCatalogQueryPort materials;
    private final OwnerMapper owners;
    private final OutboundOrderMapper documents;
    private final OutboundLineMapper documentLines;
    private final OutboundService outbound;

    public InventoryProductionPortImpl(InventoryAclService acl, MaterialCatalogQueryPort materials,
            OwnerMapper owners, OutboundOrderMapper documents, OutboundLineMapper documentLines,
            OutboundService outbound) {
        this.acl = acl; this.materials = materials; this.owners = owners; this.documents = documents;
        this.documentLines = documentLines; this.outbound = outbound;
    }

    @Override
    @Transactional(transactionManager="invTransactionManager")
    public Receipt consume(Command input) {
        var command = normalized(input);
        String owner = owner(command);
        var locked = owners.selectOne(Wrappers.<InvOwner>lambdaQuery().eq(InvOwner::getOwnerId, owner)
                .last("FOR UPDATE"));
        if (locked == null || !"ACTIVE".equals(locked.getStatus())) throw failure(ErrorCode.FORBIDDEN);
        var prior = existing(owner, command);
        if (prior != null) return receipt(prior, command);
        String location = acl.stockLocationOfExistingStore(command.entityNo(), command.storeNo());
        if (location == null) throw failure(ErrorCode.NOT_FOUND);
        try { InventoryProductionFacts.integerTotal(command); }
        catch (ArithmeticException unsupported) { throw failure(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE); }
        var facts = materials.getMaterials(command.entityNo(), command.lines().stream().map(Line::itemRef).toList());
        for (Line line : command.lines()) {
            var fact = facts.stream().filter(f -> f.itemId().equals(line.itemRef())).findFirst()
                    .orElseThrow(() -> failure(ErrorCode.NOT_FOUND));
            if (!MaterialCatalogFacts.validFact(fact) || !command.entityNo().equals(fact.entityNo())
                    || !fact.baseUom().equals(line.unit())
                    || !fact.definitionHash().equals(line.definitionHash())) throw failure(ErrorCode.CONFLICT);
            try { line.quantity().intValueExact(); }
            catch (ArithmeticException unsupported) { throw failure(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE); }
        }
        String purpose = "MATERIAL_WASTE".equals(command.purpose()) ? "SCRAP" : "PRODUCTION";
        var draft = new OutboundService.Draft(owner, location, purpose, source(command), null,
                "MATERIAL_WASTE".equals(command.purpose()) ? "OTHER" : null,
                LocalDateTime.ofInstant(command.occurredAt(), ZoneOffset.UTC), fingerprint(command),
                command.lines().stream().map(l -> new OutboundService.Line(l.itemRef(),
                        l.quantity().intValueExact(), l.unit())).toList());
        String documentNo = outbound.postDirectly(draft, "FOOD_PRODUCTION");
        var posted = existing(owner, command);
        if (posted == null || !documentNo.equals(posted.getOutboundNo())) throw failure(ErrorCode.CONFLICT);
        return receipt(posted, command);
    }

    @Override
    @Transactional(transactionManager="invTransactionManager", readOnly=true)
    public Receipt query(Command input) {
        var command = normalized(input);
        var existing = existing(owner(command), command);
        return existing == null ? new Receipt(command.eventNo(), fingerprint(command), "ABSENT", null,
                List.of(), null, null) : receipt(existing, command);
    }

    private String owner(Command command) {
        String owner = acl.findOwnerId(command.entityNo());
        if (owner == null) throw failure(ErrorCode.NOT_FOUND);
        return owner;
    }
    private InvOutboundOrder existing(String owner, Command command) {
        return documents.selectOne(Wrappers.<InvOutboundOrder>lambdaQuery()
                .eq(InvOutboundOrder::getOwnerId, owner).eq(InvOutboundOrder::getSourceRef, source(command)));
    }
    private Receipt receipt(InvOutboundOrder original, Command command) {
        if (!fingerprint(command).equals(original.getRemark())) throw failure(ErrorCode.CONFLICT);
        var lines = documentLines.selectList(Wrappers.<InvOutboundLine>lambdaQuery()
                .eq(InvOutboundLine::getOwnerId, original.getOwnerId())
                .eq(InvOutboundLine::getOutboundNo, original.getOutboundNo())
                .orderByAsc(InvOutboundLine::getItemId));
        if (lines.size() != command.lines().size()) throw failure(ErrorCode.CONFLICT);
        for (int i=0; i<lines.size(); i++) {
            var row = lines.get(i); var wanted = command.lines().get(i);
            if (!row.getItemId().equals(wanted.itemRef()) || !row.getUom().equals(wanted.unit())
                    || wanted.quantity().compareTo(BigDecimal.valueOf(row.getQty())) != 0) {
                throw failure(ErrorCode.CONFLICT);
            }
        }
        String state = "POSTED".equals(original.getStatus()) ? "CONFIRMED" :
                "VOIDED".equals(original.getStatus()) ? "REVERSED" : "UNKNOWN";
        // Historical inv cost has no currency authority; do not label it with an input or invented currency.
        return new Receipt(command.eventNo(), fingerprint(command), state, original.getOutboundNo(),
                command.lines(), null, null);
    }
    public static Command normalized(Command c) {
        try { return InventoryProductionFacts.normalize(c); }
        catch (IllegalArgumentException invalid) { throw failure(ErrorCode.BAD_REQUEST); }
    }
    private static String fingerprint(Command c) { return InventoryProductionFacts.hash(c); }
    private static String source(Command c) {
        return "FP" + PricingSnapshotHash.of(List.of(c.storeNo(),c.eventNo())).substring(0,62);
    }
    private static BizException failure(ErrorCode code) { return BizException.of(code); }
}
