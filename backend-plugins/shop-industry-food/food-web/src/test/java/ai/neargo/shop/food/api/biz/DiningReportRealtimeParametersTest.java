package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class DiningReportRealtimeParametersTest {
    @Test void unsupportedScopeOrMeasureParametersAreRejectedInsteadOfIgnored() {
        for(String name:new String[]{"storeNos","currency","unit","metricCodes","from","to","businessDate"}) {
            var request=new MockHttpServletRequest();request.setParameter(name,"value");
            assertThatThrownBy(()->BizDiningReportController.validateRealtimeParameters(request))
                    .isInstanceOfSatisfying(BizException.class,failure->assertThat(failure.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        }
    }
    @Test void onlyMetricVersionIsAcceptedForTheCurrentAuthorizedStore() {
        var request=new MockHttpServletRequest();request.setParameter("metricVersion","1");
        assertThatCode(()->BizDiningReportController.validateRealtimeParameters(request)).doesNotThrowAnyException();
    }
}
