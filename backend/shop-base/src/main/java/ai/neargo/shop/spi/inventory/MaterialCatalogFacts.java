package ai.neargo.shop.spi.inventory;

import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort.MaterialFact;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Shared canonicalization prevents a remote definition from silently changing its identity or capabilities. */
public final class MaterialCatalogFacts {
    private MaterialCatalogFacts() { }

    public static boolean validKey(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && value.equals(value.strip());
    }

    public static boolean validDefinition(String item, String entity, String name, String unit,
            Integer weighed, String source) {
        return validKey(item, 32) && validKey(entity, 32) && name != null && !name.isBlank()
                && name.length() <= 128 && validKey(unit, 16) && weighed != null && (weighed == 0 || weighed == 1)
                && ("OWN".equals(source) || "SYNCED".equals(source));
    }

    /** Length prefixes are byte counts, making concatenation unambiguous for arbitrary Unicode names. */
    public static String definitionHash(String item, String entity, String name, String unit,
            Integer weighed, String source) {
        if (!validDefinition(item, entity, name, unit, weighed, source)) {
            throw new IllegalArgumentException("Invalid material definition");
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[]{"material-definition:v1", entity, item, name, unit,
                    weighed.toString(), source, "BASE_ONLY", "INTEGER_ONLY"}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static boolean validFact(MaterialFact fact) {
        return fact != null && validDefinition(fact.itemId(), fact.entityNo(), fact.name(), fact.baseUom(),
                fact.weighed(), fact.dataSource()) && "BASE_ONLY".equals(fact.unitSupport())
                && "INTEGER_ONLY".equals(fact.postingSupport())
                && definitionHash(fact.itemId(), fact.entityNo(), fact.name(), fact.baseUom(), fact.weighed(),
                        fact.dataSource()).equals(fact.definitionHash());
    }

    /** IDs and unit codes are textual identities; coercing numbers or booleans changes the protocol first. */
    public static final class TextDeserializer extends ValueDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return context.reportInputMismatch(String.class, "Material text must be a string token");
            }
            return parser.getString();
        }
    }

    /** Rejects fractional and string tokens rather than letting Jackson round them into a valid flag. */
    public static final class WeighedDeserializer extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Integer.class, "Material weighed must be an integer flag");
            }
            int value = parser.getIntValue();
            if (value != 0 && value != 1) {
                return context.reportInputMismatch(Integer.class, "Material weighed must be zero or one");
            }
            return value;
        }
    }
}
