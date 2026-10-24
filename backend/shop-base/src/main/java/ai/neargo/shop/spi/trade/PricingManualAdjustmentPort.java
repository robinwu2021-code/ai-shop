package ai.neargo.shop.spi.trade;

import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.QuoteVO;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Explicit staff concession intent; prices, tax and approval provenance always come from their real owners. */
public interface PricingManualAdjustmentPort {
    enum ManualKind { PERCENT, AMOUNT, WAIVER, ROUNDING }
    record Request(@NotNull PricingSourceRef source, @NotNull ManualKind kind,
            @Min(1) Long amountMinor, @Min(0) Integer payableRateBp,
            @NotBlank @Size(max=32) String reasonCode, @NotBlank @Size(max=255) String reason) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
        public static Request decode(JsonNode node) {
            StrictInput.fields(node,Set.of("source","kind","reasonCode","reason"),
                    Set.of("amountMinor","payableRateBp"));
            var source=StrictInput.source(node.get("source"));
            var kind=ManualKind.valueOf(StrictInput.text(node,"kind",false));
            var amount=StrictInput.integer(node,"amountMinor",true,1,MoneyMinor.MAX_JSON_SAFE_MINOR);
            var rate=StrictInput.integer(node,"payableRateBp",true,0,10000);
            return new Request(source,kind,amount,rate==null?null:rate.intValue(),
                    StrictInput.text(node,"reasonCode",false),StrictInput.text(node,"reason",false));
        }
    }
    /** Manual transport decoding only; no domain owner or pricing authority is inferred from these fields. */
    final class StrictInput {
        private StrictInput() { }
        public static void fields(JsonNode node,Set<String> required,Set<String> optional) {
            if(node==null || !node.isObject()) throw invalid();
            var allowed=new HashSet<>(required); allowed.addAll(optional);
            if(!node.propertyNames().containsAll(required) || !allowed.containsAll(node.propertyNames())) throw invalid();
        }
        public static Long integer(JsonNode node,String field,boolean optional,long minimum,long maximum) {
            var value=node.path(field);
            if(value.isMissingNode() || value.isNull()) {
                if(optional) return null;
                throw invalid();
            }
            if(!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
            long exact=value.asLong();
            if(exact<minimum || exact>maximum) throw invalid();
            return exact;
        }
        public static String text(JsonNode node,String field,boolean optional) {
            var value=node.path(field);
            if(optional && (value.isMissingNode() || value.isNull())) return null;
            if(!value.isString()) throw invalid();
            return value.asString();
        }
        public static boolean bool(JsonNode node,String field) {
            var value=node.path(field);
            if(!value.isBoolean()) throw invalid();
            return value.asBoolean();
        }
        private static PricingSourceRef source(JsonNode node) {
            fields(node,Set.of("sourceSystem","sourceType","sourceNo","sourceVersion"),
                    Set.of("scopeNo","scopeVersion"));
            return new PricingSourceRef(text(node,"sourceSystem",false),text(node,"sourceType",false),
                    text(node,"sourceNo",false),integer(node,"sourceVersion",false,0,Long.MAX_VALUE),
                    text(node,"scopeNo",true),integer(node,"scopeVersion",true,0,Long.MAX_VALUE));
        }
        private static IllegalArgumentException invalid() {
            return new IllegalArgumentException("Explicit closed manual adjustment JSON types required");
        }
    }
    record AdjustmentVO(String adjustmentNo, String status, String actorNo, String kind, String currency,
            long discountMinor, String reasonCode, String reason, String approvedBy, String expiresAt,
            long policyVersion, long version) { }
    AdjustmentVO propose(Scope scope, String actorNo, Request request, String key);
    AdjustmentVO get(Scope scope, String actorNo, String adjustmentNo);
    AdjustmentVO approve(Scope scope, String actorNo, String adjustmentNo, long version, String key);
    QuoteVO quote(Scope scope, String actorNo, String adjustmentNo, String key);
}
