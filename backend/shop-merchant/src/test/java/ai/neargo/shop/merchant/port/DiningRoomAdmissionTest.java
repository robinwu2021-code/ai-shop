package ai.neargo.shop.merchant.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.AdmissionPolicyMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.DepositMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.PickupQueryPort;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DiningRoomAdmissionTest {
    @Test void genuineDineInUsesNativeT1RiskMatrixInsteadOfUnknownFulfillmentOrPickupAlias() {
        var entities=mock(MchEntityMapper.class);
        var port=new AdmissionPortImpl(entities,mock(AdmissionPolicyMapper.class),mock(DepositMapper.class),
                JsonMapper.builder().build(),mock(PickupQueryPort.class),mock(MerchantQueryPort.class));
        var entity=new MchEntity(); entity.setEntityNo("E");
        when(entities.selectOne(any())).thenReturn(entity);
        for(String legal: new String[]{"ENTERPRISE","INDIVIDUAL"}) {
            entity.setLegalForm(legal);
            assertThat(port.requireFulfillmentAllowed("E",Fulfillments.DINE_IN,null)).isFalse();
        }
        for(String legal: new String[]{"NATURAL_PERSON","MICRO"}) {
            entity.setLegalForm(legal);
            assertThatThrownBy(() -> port.requireFulfillmentAllowed("E",Fulfillments.DINE_IN,null))
                    .isInstanceOfSatisfying(BizException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.FULFILLMENT_TIER_DENIED));
        }
        assertThat(port.requireFulfillmentAllowed("E",Fulfillments.STORE_PICKUP,null)).isFalse();
    }
}
