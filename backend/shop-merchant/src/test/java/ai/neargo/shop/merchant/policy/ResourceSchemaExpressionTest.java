package ai.neargo.shop.merchant.policy;

import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceSchemaExpressionTest {
    private String canonical(String sql) throws Exception {
        var method = ResourceOccupationSchemaVerifier.class.getDeclaredMethod("expression", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, sql);
    }

    @Test
    void recognizesOnlyEquivalentMysqlFiveMinuteGridExpressions() throws Exception {
        assertThat(canonical("MOD(MINUTE(start_at),5)=0 AND end_at=TIMESTAMPADD(MINUTE,5,start_at)"))
                .isEqualTo(canonical("(MINUTE(start_at)%5)=0 AND end_at=(start_at+INTERVAL 5 MINUTE)"));
        assertThat(canonical("end_at=start_at+INTERVAL 5 MINUTE"))
                .isNotEqualTo(canonical("end_at=start_at+INTERVAL 6 MINUTE"));
        assertThat(canonical("MOD(MINUTE(start_at),5)=0"))
                .isNotEqualTo(canonical("MOD(MINUTE(start_at),6)=0"));
        assertThatThrownBy(() -> canonical("end_at=start_at+INTERVAL 5 SECOND"))
                .isInstanceOf(InvocationTargetException.class);
    }
}
