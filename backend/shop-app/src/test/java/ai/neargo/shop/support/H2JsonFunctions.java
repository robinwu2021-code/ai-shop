package ai.neargo.shop.support;

import java.util.HashSet;
import org.h2.util.json.JSONArray;
import org.h2.util.json.JSONObject;
import org.h2.util.json.JSONStringSource;
import org.h2.util.json.JSONValueTarget;

/** Single-argument MySQL JSON_LENGTH semantics for the generated H2 fixture. */
public final class H2JsonFunctions {
    private H2JsonFunctions() {}

    public static Integer length(String document) {
        if (document == null) return null;
        var value = JSONStringSource.parse(document, new JSONValueTarget());
        if (value instanceof JSONArray array) return array.length();
        if (value instanceof JSONObject object) {
            // MySQL normalizes duplicate object keys before evaluating JSON_LENGTH.
            var keys = new HashSet<String>();
            for (var member : object.getMembers()) keys.add(member.getKey());
            return keys.size();
        }
        return 1;
    }
}
