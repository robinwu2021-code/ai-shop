package ai.neargo.shop.food.port;

import ai.neargo.shop.food.service.impl.KitchenReturnServiceImpl;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbReturn;
import ai.neargo.shop.food.entity.FnbReturnAmendment;
import ai.neargo.shop.food.entity.FnbReturnItem;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnMapper;
import ai.neargo.shop.food.mapper.FoodReturnAmendmentMapper;
import ai.neargo.shop.food.policy.BusinessReturnDocuments;
import ai.neargo.shop.spi.trade.IndustryAmendmentSourcePort;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort.Reduction;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Set;

/**
 * Core reads completed, immutable kitchen dispositions instead of trusting caller-supplied
 * reductions.
 */
public class FoodAmendmentSourcePortImpl implements IndustryAmendmentSourcePort {
    private final ReturnMapper returns;
    private final ReturnItemMapper items;
    private final OrderBatchMapper batches;
    private final CoreOrderBindingMapper bindings;
    private final FoodReturnAmendmentMapper approvals;
    private final ObjectMapper json;

    public FoodAmendmentSourcePortImpl(
            ReturnMapper returns,
            ReturnItemMapper items,
            OrderBatchMapper batches,
            CoreOrderBindingMapper bindings,
            FoodReturnAmendmentMapper approvals,
            ObjectMapper json) {
        this.returns = returns;
        this.items = items;
        this.batches = batches;
        this.bindings = bindings;
        this.approvals = approvals;
        this.json = json;
    }

    @Override
    public Evidence get(Reference ref, String number) {
        if (ref == null
                || ref.scope() == null
                || ref.source() == null
                || number == null
                || !"MAIN".equals(ref.scope().tenantNo())
                || !"FOOD".equals(ref.source().sourceSystem())
                || !"CART".equals(ref.source().sourceType()))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        return DataScopeContext.executeWithoutScope(
                () -> {
                    String e = ref.scope().entityNo(), s = ref.scope().storeNo();
                    var row =
                            returns.selectOne(
                                    Wrappers.<FnbReturn>lambdaQuery()
                                            .eq(FnbReturn::getEntityNo, e)
                                            .eq(FnbReturn::getStoreNo, s)
                                            .eq(FnbReturn::getReturnNo, number));
                    if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
                    var frozen = BusinessReturnDocuments.read(row, json);
                    var intent =
                            approvals.selectOne(
                                    Wrappers.<FnbReturnAmendment>lambdaQuery()
                                            .eq(FnbReturnAmendment::getEntityNo, e)
                                            .eq(FnbReturnAmendment::getStoreNo, s)
                                            .eq(FnbReturnAmendment::getReturnNo, number));
                    if (intent == null
                            || !"HANDLED".equals(row.getKitchenStatus())
                            || !frozen.sourceHash().equals(intent.getSourceHash()))
                        throw BizException.of(ErrorCode.CONFLICT);
                    var batch =
                            batches.selectOne(
                                    Wrappers.<FnbOrderBatch>lambdaQuery()
                                            .eq(FnbOrderBatch::getEntityNo, e)
                                            .eq(FnbOrderBatch::getStoreNo, s)
                                            .eq(FnbOrderBatch::getBatchNo, row.getBatchNo()));
                    var binding =
                            bindings.selectOne(
                                    Wrappers.<FnbCoreOrderBinding>lambdaQuery()
                                            .eq(FnbCoreOrderBinding::getEntityNo, e)
                                            .eq(FnbCoreOrderBinding::getBatchNo, row.getBatchNo())
                                            .eq(FnbCoreOrderBinding::getStatus, "BOUND"));
                    if (batch == null
                            || binding == null
                            || !ref.orderNo().equals(binding.getPaymentRootNo())
                            || !ref.subOrderNo().equals(binding.getStoreOrderNo())
                            || !ref.source().sourceNo().equals(batch.getCartNo())
                            || ref.source().sourceVersion() != batch.getCartVersion())
                        throw BizException.of(ErrorCode.FORBIDDEN);
                    var persisted =
                            items.selectList(
                                    Wrappers.<FnbReturnItem>lambdaQuery()
                                            .eq(FnbReturnItem::getEntityNo, e)
                                            .eq(FnbReturnItem::getReturnNo, number));
                    if (persisted.size() != frozen.items().size())
                        throw BizException.of(ErrorCode.CONFLICT);
                    var reductions = new ArrayList<Reduction>();
                    for (var source : frozen.items()) {
                        var item =
                                persisted.stream()
                                        .filter(
                                                i ->
                                                        i.getFoodLineNo()
                                                                        .equals(source.foodLineNo())
                                                                && source.mainAllocationNo()
                                                                        .equals(
                                                                                i
                                                                                        .getCoreLineAllocationRef()))
                                        .findFirst()
                                        .orElseThrow(() -> BizException.of(ErrorCode.CONFLICT));
                        if (item.getQty().compareTo(source.qty()) != 0
                                || !Set.of("CANCEL", "LOSS", "KEEP", "COMP", "GIFT")
                                        .contains(item.getKitchenDisposition()))
                            throw BizException.of(ErrorCode.CONFLICT);
                        for (var component : source.components())
                            reductions.add(
                                    new Reduction(
                                            component.orderItemId(),
                                            component.sourceComponentRef(),
                                            KitchenReturnServiceImpl.quantity(
                                                    source.qty(),
                                                    component.originalQty(),
                                                    source.originalQty()),
                                            component.unit(),
                                            item.getKitchenDisposition()));
                    }
                    return new Evidence(
                            ref,
                            number,
                            frozen.sourceHash(),
                            row.getKitchenStatus(),
                            intent.getActorNo(),
                            reductions,
                            intent.getReplacementCartNo());
                });
    }
}
