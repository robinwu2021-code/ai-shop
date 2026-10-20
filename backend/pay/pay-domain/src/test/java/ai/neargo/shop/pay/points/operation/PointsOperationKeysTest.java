package ai.neargo.shop.pay.points.operation;
import ai.neargo.shop.spi.points.PointsOperationsPort.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PointsOperationKeysTest {
    @Test void identityIsTypedLengthDelimitedAndBounded() {
        var a=new Operation(Source.TRADE, "AB", "C", "P");
        var b=new Operation(Source.TRADE, "A", "BC", "P");
        assertThat(PointsOperationKeys.key(a)).isNotEqualTo(PointsOperationKeys.key(b));
        assertThat(PointsOperationKeys.key(a)).isNotEqualTo(PointsOperationKeys.key(new Operation(Source.TRADE, "AB", "C", "Q")));
        assertThat(PointsOperationKeys.key(a)).hasSizeLessThan(80);
    }
    @Test void missingIdentityIsRejected() {
        assertThatThrownBy(()->PointsOperationKeys.key(new Operation(null, "B", "O", "P"))).hasMessage("POINTS_OPERATION_INPUT_INVALID");
    }
    @Test void legacyCommandsKeepTheirJsonAndVersionedReferencesResolveRealPositions() {
        var json=new tools.jackson.databind.ObjectMapper();
        var grant=new ai.neargo.shop.spi.points.PointsEarnPort.GrantCommand("E", "S", "U", "P", "ST", "TEST", "ONLINE", 1, java.util.List.of());
        String legacy=json.writeValueAsString(grant);
        assertThat(legacy).doesNotContain("operationKey");
        assertThat(json.readValue(legacy, ai.neargo.shop.spi.points.PointsEarnPort.GrantCommand.class)).isEqualTo(grant);
        var hold=new ai.neargo.shop.spi.points.PointsRedeemPort.HoldCommand("O", "CN", "U", 0, "DIRECT", "MP", java.util.List.of());
        assertThat(json.writeValueAsString(hold)).doesNotContain("operationKey", "typeCode");
        String position=ai.neargo.shop.pay.points.model.PointsKeys.position("G", "P", "S");
        assertThat(PointsOperationKeys.grantPosition("P", PointsOperationKeys.grantRef("P", "S", "V2:abc"))).isEqualTo(position);
        assertThat(PointsOperationKeys.grantRef("P", "S", null)).isEqualTo("GRANT:S");
    }
}
