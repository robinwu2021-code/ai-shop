package ai.neargo.shop.product;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.service.InvManagedService;
import ai.neargo.shop.product.service.impl.SkuIdentityServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SkuIdentityVersionGuardTest {
    @Test void importConflictingSkuVersionFailsBeforePublishing() {
        var skus = mock(SkuMapper.class);
        var goods = mock(GoodsMapper.class);
        var events = mock(OutboxEventBus.class);
        var managed = mock(InvManagedService.class);
        var sku = sku();
        when(skus.selectList(any())).thenReturn(List.of(sku));
        when(goods.selectList(any())).thenReturn(List.of());
        when(skus.updateById(any(PrdSku.class))).thenReturn(0);
        var service = new SkuIdentityServiceImpl(skus, goods, events, managed);
        assertThatThrownBy(() -> service.apply("E", "skuNo,单位\nSKU,KG"))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(skus).updateById(argThat((PrdSku row) -> Long.valueOf(4).equals(row.getVersion())));
        verifyNoInteractions(events, managed);
    }

    @Test void barcodeBindingCannotOverwriteAConcurrentUnitUpdate() {
        var skus = mock(SkuMapper.class);
        var goods = mock(GoodsMapper.class);
        var events = mock(OutboxEventBus.class);
        var managed = mock(InvManagedService.class);
        when(skus.selectList(any())).thenReturn(List.of(sku()));
        when(skus.updateById(any(PrdSku.class))).thenReturn(0);
        var service = new SkuIdentityServiceImpl(skus, goods, events, managed);
        assertThatThrownBy(() -> service.bindBarcode("E", "SKU", "123"))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(skus).updateById(argThat((PrdSku row) -> Long.valueOf(4).equals(row.getVersion())
                && "EA".equals(row.getSaleUnit())));
        verifyNoInteractions(events, managed);
    }

    private static PrdSku sku() {
        var row = new PrdSku();
        row.setId(1L);
        row.setSkuNo("SKU");
        row.setGoodsNo("G");
        row.setEntityNo("E");
        row.setVersion(4L);
        row.setSaleUnit("EA");
        return row;
    }
}
