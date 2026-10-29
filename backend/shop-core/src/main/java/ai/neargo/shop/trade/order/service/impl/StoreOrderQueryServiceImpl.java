package ai.neargo.shop.trade.order.service.impl;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.order.contract.StoreOrderQuery;
import ai.neargo.shop.trade.order.contract.StoreOrderSummary;
import ai.neargo.shop.trade.order.contract.StoreOrderView;
import ai.neargo.shop.trade.order.service.StoreOrderQueryService;
import ai.neargo.shop.trade.order.service.query.StoreOrderAssembler;
import ai.neargo.shop.trade.order.service.query.StoreOrderMaterial;
import ai.neargo.shop.trade.order.service.query.CustomerReadScope;
import ai.neargo.shop.trade.order.service.query.StoreOrderReadStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Registered production query service shared by canonical projections and the full legacy JSON adapter. */
@Service
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class StoreOrderQueryServiceImpl implements StoreOrderQueryService {
    private final StoreOrderReadStore store;

    public StoreOrderQueryServiceImpl(StoreOrderReadStore store) {
        this.store = store;
    }

    @Override
    public StoreOrderMaterial materialReadable(String no, CustomerReadScope scope) {
        return store.findReadable(no, scope);
    }

    @Override
    public PageData<StoreOrderMaterial> materialsReadable(
            CustomerReadScope scope, StoreOrderQuery query, long page, long size) {
        return store.pageReadable(scope, query, page, size);
    }

    @Override
    public StoreOrderView detail(String no, String buyer) {
        var material = material(no, buyer);
        return material == null ? null : StoreOrderAssembler.view(material);
    }

    @Override
    public PageData<StoreOrderSummary> list(String buyer, StoreOrderQuery query, long page, long size) {
        var material = materials(buyer, query, page, size);
        return PageData.of(material.records().stream().map(StoreOrderAssembler::summary).toList(),
                material.total(), material.page(), material.size());
    }

    @Override
    public StoreOrderMaterial material(String no, String buyer) {
        return store.findOwned(no, buyer);
    }

    @Override
    public PageData<StoreOrderMaterial> materials(String buyer, StoreOrderQuery query, long page, long size) {
        return store.pageOwned(buyer, query, page, size);
    }
}
