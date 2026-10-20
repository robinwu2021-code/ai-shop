package ai.neargo.shop.spi.entitlement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
/** Same canonical original-row digest in core and pay, independent of JSON object field ordering. */
public final class LegacyCouponHistoryFacts {
    private LegacyCouponHistoryFacts() { }
    public static String hash(ObjectMapper json,Object row) {
        String value="LEGACY_COUPON_HISTORY_V1:"+json.writeValueAsString(normalize(json.readTree(json.writeValueAsString(row))));
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private static Object normalize(JsonNode node) {
        if(node.isObject()) {
            var fields=new TreeMap<String,Object>();node.properties().forEach(entry->fields.put(entry.getKey(),normalize(entry.getValue())));return fields;
        }
        if(node.isArray()) {var values=new ArrayList<Object>();node.forEach(value->values.add(normalize(value)));return values;}
        return node;
    }
}
