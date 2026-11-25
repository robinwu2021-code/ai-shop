package ai.neargo.shop.merchant.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingOwnerMapper;
import org.junit.jupiter.api.Test;

/** TDD-手持收银机后端 W2：店内设定在主体审核中也放行，其余状态回 70200，找不到回 10404。 */
class StoreConfigScopeTest {
    private final BookingOwnerMapper parents = mock(BookingOwnerMapper.class);

    @Test
    void usesConfigLockNotTheSellableLock() {
        var row = new ResourceCommandScopeRow("MAIN", "E", "S");
        when(parents.lockConfigScope("MAIN", "E", "S")).thenReturn(row);
        assertThat(StoreConfigScope.lock(parents, "E", "S")).isSameAs(row);
        verify(parents).lockConfigScope("MAIN", "E", "S");
    }

    @Test
    void existingStoreOfDisallowedEntityIsNotOperational() {
        when(parents.selectEntityStatusOfStore("MAIN", "E", "S")).thenReturn("SUSPENDED");
        assertThatThrownBy(() -> StoreConfigScope.lock(parents, "E", "S"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ENTITY_NOT_OPERATIONAL));
    }

    @Test
    void missingStoreIsNotFound() {
        assertThatThrownBy(() -> StoreConfigScope.lock(parents, "E", "S"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void mismatchedRowIsTreatedAsMissing() {
        when(parents.lockConfigScope("MAIN", "E", "S")).thenReturn(new ResourceCommandScopeRow("MAIN", "E", "OTHER"));
        assertThatThrownBy(() -> StoreConfigScope.lock(parents, "E", "S"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
