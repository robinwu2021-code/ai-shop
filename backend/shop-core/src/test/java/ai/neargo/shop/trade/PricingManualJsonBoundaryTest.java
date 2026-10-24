package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.trade.PricingManualAdjustmentPort.ManualKind;
import ai.neargo.shop.spi.trade.PricingManualAdjustmentPort.Request;
import ai.neargo.shop.trade.dto.PricingManualPolicyCommands.Approve;
import ai.neargo.shop.trade.dto.PricingManualPolicyCommands.Save;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** HTTP coercion cannot turn fractional money, opaque authority fields or strings into admitted manual intent. */
class PricingManualJsonBoundaryTest {
    // Closed DTO admission must remain effective even if the hosting application's unknown-field setting is relaxed.
    private final JsonMapper json=JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final String REQUEST="""
            {"source":{"sourceSystem":"FOOD","sourceType":"CART","sourceNo":"C","sourceVersion":4},
             "kind":"AMOUNT","amountMinor":101,"reasonCode":"PRICE_CORRECTION","reason":"Price correction"}
            """;
    private static final String POLICY="""
            {"expectedVersion":-1,"enabled":true,"selfLimitMinor":0,"maxLimitMinor":500,"minPayableRateBp":8000}
            """;

    @Test void acceptsExactLegalAmountPercentagePolicyAndApprovalWithoutChangingTheJsonContract() {
        var amount=json.readValue(REQUEST,Request.class);
        assertThat(amount.kind()).isEqualTo(ManualKind.AMOUNT);
        assertThat(amount.amountMinor()).isEqualTo(101L);
        assertThat(amount.payableRateBp()).isNull();
        assertThat(amount.source().sourceVersion()).isEqualTo(4L);
        assertThat(json.readValue(json.writeValueAsString(amount),Request.class)).isEqualTo(amount);
        var percent=node(REQUEST); percent.put("kind","PERCENT"); percent.remove("amountMinor");
        percent.put("payableRateBp",9000);
        assertThat(json.readValue(percent.toString(),Request.class).payableRateBp()).isEqualTo(9000);
        var policy=json.readValue(POLICY,Save.class);
        assertThat(policy.expectedVersion()).isEqualTo(-1L);
        assertThat(policy.enabled()).isTrue();
        assertThat(policy.minPayableRateBp()).isEqualTo(8000);
        assertThat(json.readValue("{\"version\":0}",Approve.class).version()).isZero();
    }

    @Test void rejectsFractionalStringBooleanAndOverflowMoneyBasisPointsAndVersions() {
        for(String raw:List.of("101.0","101.75","\"101\"","true","9223372036854775808"))
            rejects(replace(REQUEST,"amountMinor",raw),Request.class);
        for(String raw:List.of("9000.5","\"9000\"","true","2147483648"))
            rejects(replace(REQUEST,"payableRateBp",raw),Request.class);
        for(String field:List.of("expectedVersion","selfLimitMinor","maxLimitMinor","minPayableRateBp"))
            for(String raw:List.of("0.5","\"0\"","false","9223372036854775808"))
                rejects(replace(POLICY,field,raw),Save.class);
        for(String raw:List.of("1.0","\"1\"","true","9223372036854775808"))
            rejects("{\"version\":"+raw+"}",Approve.class);
    }

    @Test void rejectsImplicitBooleanAndTextConversionsAndOrdinalKinds() {
        for(String raw:List.of("\"true\"","1","0.0","{}","null"))
            rejects(replace(POLICY,"enabled",raw),Save.class);
        for(String field:List.of("reasonCode","reason","kind"))
            for(String raw:List.of("0","123","true","{}","[]")) rejects(replace(REQUEST,field,raw),Request.class);
        for(String field:List.of("sourceSystem","sourceType","sourceNo","scopeNo")) {
            var request=node(REQUEST); var source=(ObjectNode)request.get("source");
            source.set(field,json.readTree("123")); rejects(request.toString(),Request.class);
        }
    }

    @Test void rejectsNestedSourceVersionCoercionOverflowAndUnknownAuthorityEvenOnPermissiveMapper() {
        for(String field:List.of("sourceVersion","scopeVersion"))
            for(String raw:List.of("4.5","\"4\"","true","9223372036854775808")) {
                var request=node(REQUEST); var source=(ObjectNode)request.get("source");
                if(field.equals("scopeVersion")) source.put("scopeNo","SCOPE");
                source.set(field,json.readTree(raw)); rejects(request.toString(),Request.class);
            }
        for(String field:List.of("entityNo","actorNo","approvalRef","unitPrice","taxMinor")) {
            rejects(replace(REQUEST,field,"\"FORGED\""),Request.class);
            rejects(replace(POLICY,field,"\"FORGED\""),Save.class);
            rejects("{\"version\":0,\""+field+"\":\"FORGED\"}",Approve.class);
        }
        var nested=node(REQUEST); ((ObjectNode)nested.get("source")).put("approvedBy","OWNER");
        rejects(nested.toString(),Request.class);
    }

    @Test void rejectsMissingRequiredFieldsButPreservesExplicitOptionalNullsAndFullLongVersions() {
        for(String field:List.of("source","kind","reasonCode","reason")) {
            var missing=node(REQUEST); missing.remove(field); rejects(missing.toString(),Request.class);
        }
        for(String field:List.of("expectedVersion","enabled","selfLimitMinor","maxLimitMinor","minPayableRateBp")) {
            var missing=node(POLICY); missing.remove(field); rejects(missing.toString(),Save.class);
        }
        rejects("{}",Approve.class);
        var optional=node(REQUEST); optional.putNull("payableRateBp");
        var source=(ObjectNode)optional.get("source"); source.putNull("scopeNo"); source.putNull("scopeVersion");
        source.put("sourceVersion",Long.MAX_VALUE);
        assertThat(json.readValue(optional.toString(),Request.class).source().sourceVersion()).isEqualTo(Long.MAX_VALUE);
        assertThat(json.readValue("{\"version\":9223372036854775807}",Approve.class).version())
                .isEqualTo(Long.MAX_VALUE);
    }
    private ObjectNode node(String body) { return (ObjectNode)json.readTree(body); }
    private String replace(String body,String field,String raw) {
        var result=node(body); JsonNode value=json.readTree(raw); result.set(field,value); return result.toString();
    }
    private void rejects(String body,Class<?> type) {
        assertThatThrownBy(() -> json.readValue(body,type)).as(body).isInstanceOf(RuntimeException.class);
    }
}
