package ai.neargo.shop.pay.points.type;

import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.RedeemRate;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.Map;

/** Independent types retain separate balances and rates; market order defaults remain stable. */
public interface PointTypeService {
    PointType create(CreateType command, String actor);

    PointType activate(String typeCode, String actor);

    PointType drain(String typeCode, String actor);

    PointType retire(String typeCode, String actor);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CreateType(String typeCode, String name, String currency, RedeemRate rate) {
        /** Only this command requires exact integer JSON units; never truncate a fractional rate. */
        @JsonCreator
        public static CreateType fromJson(
                @JsonProperty("typeCode") String typeCode,
                @JsonProperty("name") String name,
                @JsonProperty("currency") String currency,
                @JsonProperty("rate") Map<String, BigDecimal> units) {
            RedeemRate rate = null;
            if (units != null) {
                BigDecimal points = units.get("pointsUnit");
                BigDecimal minor = units.get("minorUnit");
                if (points == null || minor == null) {
                    throw new IllegalArgumentException("POINTS_RATE_INVALID");
                }
                try {
                    rate = new RedeemRate(points.longValueExact(), minor.longValueExact());
                } catch (ArithmeticException | IllegalArgumentException invalid) {
                    throw new IllegalArgumentException("POINTS_RATE_INVALID", invalid);
                }
            }
            return new CreateType(typeCode, name, currency, rate);
        }

        public CreateType(String typeCode, String name) {
            this(typeCode, name, null, null);
        }

        public CreateType(String typeCode, String name, String market, RedeemRate rate,
                          int deductRatioBp, int idleDays) {
            this(typeCode, name);
        }
        public CreateType(String typeCode, String market, RedeemRate rate,
                          int deductRatioBp, int idleDays) {
            this(typeCode, typeCode);
        }
    }
}
