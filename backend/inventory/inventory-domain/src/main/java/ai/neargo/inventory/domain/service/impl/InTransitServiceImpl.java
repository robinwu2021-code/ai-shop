package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.profile.Capability;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvOutboundLine;
import ai.neargo.inventory.domain.entity.InvTransferOrder;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OutboundLineMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.TransferOrderMapper;
import ai.neargo.inventory.domain.service.BusinessProfileService;
import ai.neargo.inventory.domain.service.DispatchService;
import ai.neargo.inventory.domain.service.InTransitService;
import ai.neargo.inventory.domain.service.PairedTransferService;
import ai.neargo.inventory.domain.support.InvEnums;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@ConditionalOnInventory
@Service
public class InTransitServiceImpl implements InTransitService {

    private final DispatchService dispatch;
    private final PairedTransferService paired;
    private final TransferOrderMapper transferMapper;
    private final OutboundLineMapper outboundLineMapper;
    private final BusinessProfileService profiles;

    public InTransitServiceImpl(DispatchService dispatch, PairedTransferService paired,
                                TransferOrderMapper transferMapper, OutboundLineMapper outboundLineMapper,
                                BusinessProfileService profiles) {
        this.dispatch = dispatch;
        this.paired = paired;
        this.transferMapper = transferMapper;
        this.outboundLineMapper = outboundLineMapper;
        this.profiles = profiles;
    }

    @Override
    public List<InTransit> of(String ownerId) {
        List<InTransit> out = new ArrayList<>();
        // 出库池只在开了能力包的账本上存在；没开就没有这一类，不是错误
        boolean pool = profiles.current(ownerId)
                .map(v -> v.profile().capabilities() != null
                        && v.profile().capabilities().contains(Capability.DISPATCH_POOL))
                .orElse(false);
        if (pool) {
            dispatch.inTransit(ownerId).forEach(t -> out.add(new InTransit("POOL", t.rootTargetType(),
                    t.rootTargetRef(), t.itemId(), t.qty(), null)));
        }
        for (PairedTransferService.View p : paired.list(ownerId, "OUT", InvEnums.TransferStatus.SHIPPED)) {
            for (PairedTransferService.Line l : p.lines()) {
                out.add(new InTransit("PAIRED", p.to().ownerType(), p.to().ownerRef(), l.fromItemId(),
                        BigDecimal.valueOf(l.shipped()), p.from().docNo()));
            }
        }
        for (InvTransferOrder t : transferMapper.selectList(Wrappers.<InvTransferOrder>lambdaQuery()
                .eq(InvTransferOrder::getOwnerId, ownerId)
                .eq(InvTransferOrder::getStatus, InvEnums.TransferStatus.SHIPPED)
                .orderByAsc(InvTransferOrder::getId))) {
            for (InvOutboundLine l : outboundLineMapper.selectList(Wrappers.<InvOutboundLine>lambdaQuery()
                    .eq(InvOutboundLine::getOwnerId, ownerId)
                    .eq(InvOutboundLine::getOutboundNo, t.getShippedOutboundNo())
                    .orderByAsc(InvOutboundLine::getLineNo))) {
                out.add(new InTransit("TRANSFER", "LOCATION", t.getToLocationId(), l.getItemId(),
                        l.getQtyQuantity().stripTrailingZeros(), t.getTransferNo()));
            }
        }
        return out;
    }
}
