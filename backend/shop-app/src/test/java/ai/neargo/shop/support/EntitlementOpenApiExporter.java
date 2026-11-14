package ai.neargo.shop.support;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.HashSet;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.introspect.AnnotatedMember;

/** Build-time documentation only. Jackson resolves names, ignored fields and generic types for each actual DTO. */
public final class EntitlementOpenApiExporter {
    private final ObjectMapper mapper;
    private final Map<String, Object> paths = new TreeMap<>();
    private final Map<String, Object> schemas = new TreeMap<>();
    private final Set<String> ids = new HashSet<>();

    public EntitlementOpenApiExporter(ObjectMapper mapper) { this.mapper = mapper; }

    @SuppressWarnings("unchecked")
    public void add(String method, String path, HandlerMethod handler, boolean unified) {
        String operationId = handler.getBeanType().getSimpleName() + "_" + handler.getMethod().getName();
        if (!ids.add(operationId)) throw new IllegalArgumentException("Duplicate operationId " + operationId);
        var operation = new TreeMap<String, Object>();
        operation.put("operationId", operationId);
        operation.put("tags", List.of(path.split("/")[1]));
        operation.put("security", List.of(Map.of("bearerAuth", List.of())));
        var permission = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
        if (permission == null) permission = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
        if (permission != null) operation.put("x-permission-expression", permission.value());
        var parameters = new ArrayList<Map<String, Object>>();
        if (unified) parameters.add(Map.of("name", "model", "in", "query", "required", true,
                "schema", Map.of("type", "string", "const", "unified")));
        for (var parameter : handler.getMethod().getParameters()) {
            if (parameter.isAnnotationPresent(RequestBody.class)) {
                operation.put("requestBody", Map.of("required", parameter.getAnnotation(RequestBody.class).required(),
                        "content", Map.of("application/json", Map.of("schema",
                                schema(mapper.constructType(parameter.getParameterizedType()), false)))));
            } else {
                var value = parameter(parameter);
                if (value != null) parameters.add(value);
                else throw new IllegalArgumentException("Unmapped HTTP parameter " + handler + " " + parameter);
            }
        }
        if (!parameters.isEmpty()) operation.put("parameters", parameters);
        var body = schema(mapper.constructType(handler.getMethod().getGenericReturnType()), true);
        operation.put("responses", Map.of("200", Map.of("description",
                "Business envelope: code=0 succeeds; validation and business failures use nonzero code. "
                        + "Constructor and contextual business validation remain defined by the API TDD.",
                "content", Map.of("application/json", Map.of("schema", Map.of("type", "object",
                        "required", List.of("code", "msg"), "properties", Map.of("code", Map.of("type", "integer"),
                                "msg", Map.of("type", "string"), "data", nullable(body)))))),
                "401", Map.of("description", "Authentication required"),
                "403", Map.of("description", "Authorization denied")));
        var methods = (Map<String, Object>) paths.computeIfAbsent(path, ignored -> new TreeMap<String, Object>());
        if (methods.put(method.toLowerCase(Locale.ROOT), operation) != null) {
            throw new IllegalArgumentException("Duplicate path/method " + method + " " + path);
        }
    }

    public void write(Path path) throws IOException {
        var root = Map.of("openapi", "3.1.0", "info", Map.of("title", "Activity and entitlement backend contracts",
                "version", "2026-10-19", "description", "Selected verified Java handlers. Unified activity routes "
                        + "require model=unified. Internal owner transport and legacy activity variants are outside this spec. "
                        + "Schemas include Jackson fields and declared Bean Validation, not contextual business rules."),
                "paths", paths, "components", Map.of("schemas", schemas, "securitySchemes",
                        Map.of("bearerAuth", Map.of("type", "http", "scheme", "bearer"))));
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
    }

    private Map<String, Object> parameter(Parameter parameter) {
        String name, location, defaultValue = ValueConstants.DEFAULT_NONE;
        boolean required;
        if (parameter.isAnnotationPresent(PathVariable.class)) {
            var annotation = parameter.getAnnotation(PathVariable.class);
            name = name(annotation.name(), annotation.value(), parameter);
            location = "path"; required = true;
        } else if (parameter.isAnnotationPresent(RequestParam.class)) {
            var annotation = parameter.getAnnotation(RequestParam.class);
            name = name(annotation.name(), annotation.value(), parameter);
            location = "query"; defaultValue = annotation.defaultValue();
            required = annotation.required() && ValueConstants.DEFAULT_NONE.equals(defaultValue);
        } else if (parameter.isAnnotationPresent(RequestHeader.class)) {
            var annotation = parameter.getAnnotation(RequestHeader.class);
            name = name(annotation.name(), annotation.value(), parameter);
            location = "header"; defaultValue = annotation.defaultValue();
            required = annotation.required() && ValueConstants.DEFAULT_NONE.equals(defaultValue);
        } else return null;
        var type = new LinkedHashMap<>(schema(mapper.constructType(parameter.getParameterizedType()), false));
        if (!ValueConstants.DEFAULT_NONE.equals(defaultValue)) {
            Object value = defaultValue;
            if (parameter.getType() == int.class || parameter.getType() == long.class) value = Long.valueOf(defaultValue);
            else if (parameter.getType() == boolean.class) value = Boolean.valueOf(defaultValue);
            type.put("default", value);
        }
        var minimum = parameter.getAnnotation(Min.class);
        if (minimum != null) type.put("minimum", minimum.value());
        var maximum = parameter.getAnnotation(Max.class);
        if (maximum != null) type.put("maximum", maximum.value());
        var size = parameter.getAnnotation(Size.class);
        if (size != null) size(type, parameter.getType(), size);
        return Map.of("name", name, "in", location, "required", required, "schema", type);
    }

    private static String name(String name, String alias, Parameter parameter) {
        return !name.isBlank() ? name : !alias.isBlank() ? alias : parameter.getName();
    }

    private Map<String, Object> schema(JavaType type, boolean response) {
        Class<?> raw = type.getRawClass();
        if (raw == void.class || raw == Void.class) return Map.of("type", "null");
        if (raw == Object.class || JsonNode.class.isAssignableFrom(raw)) return Map.of();
        if (raw == String.class || raw == Character.class || raw == char.class) return Map.of("type", "string");
        if (raw == boolean.class || raw == Boolean.class) return Map.of("type", "boolean");
        if (raw == long.class || raw == Long.class) return Map.of("type", "integer", "format", "int64");
        if (raw == int.class || raw == Integer.class || raw == short.class || raw == Short.class
                || raw == byte.class || raw == Byte.class) return Map.of("type", "integer", "format", "int32");
        if (raw == BigInteger.class) return Map.of("type", "integer");
        if (raw == BigDecimal.class || raw == double.class || raw == Double.class
                || raw == float.class || raw == Float.class) return Map.of("type", "number");
        if (raw == UUID.class) return Map.of("type", "string", "format", "uuid");
        if (raw == Instant.class || raw == OffsetDateTime.class || raw == ZonedDateTime.class) {
            return Map.of("type", "string", "format", "date-time");
        }
        if (raw == LocalDate.class) return Map.of("type", "string", "format", "date");
        if (raw == LocalDateTime.class) return Map.of("type", "string", "description", "Local date-time without offset; consult endpoint time-zone rules");
        if (raw == LocalTime.class || raw == Duration.class) return Map.of("type", "string");
        if (raw == byte[].class) return Map.of("type", "string", "format", "byte");
        if (raw.isEnum()) return Map.of("enum", Arrays.stream(raw.getEnumConstants()).map(mapper::valueToTree).toList());
        if (type.isReferenceType()) return nullable(schema(type.getReferencedType(), response));
        if (type.isArrayType() || type.isCollectionLikeType()) {
            var result = new LinkedHashMap<String, Object>();
            result.put("type", "array"); result.put("items", schema(type.getContentType(), response));
            if (Set.class.isAssignableFrom(raw)) result.put("uniqueItems", true);
            return result;
        }
        if (type.isMapLikeType()) return Map.of("type", "object", "additionalProperties", schema(type.getContentType(), response));
        // DEDUCTION scopes use their real record fields, without an invented discriminator.
        if (raw.isSealed() && raw.isInterface()) {
            var alternatives = new ArrayList<Map<String,Object>>();
            for (Class<?> subtype : raw.getPermittedSubclasses()) alternatives.add(schema(mapper.constructType(subtype), response));
            return Map.of("oneOf", alternatives);
        }
        String canonical = type.toCanonical() + (response ? ":response" : ":request");
        String name = raw.getSimpleName() + "_" + digest(canonical);
        if (!schemas.containsKey(name)) {
            var definition = new TreeMap<String, Object>();
            schemas.put(name, definition);
            definition.put("type", "object"); definition.put("x-java-type", type.toCanonical());
            var properties = new TreeMap<String, Object>();
            var required = new ArrayList<String>();
            var config = response ? mapper.serializationConfig() : mapper.deserializationConfig();
            var introspector = config.classIntrospectorInstance().forOperation(config);
            var annotations = introspector.introspectClassAnnotations(type);
            var description = response ? introspector.introspectForSerialization(type, annotations)
                    : introspector.introspectForDeserialization(type, annotations);
            for (var property : description.findProperties()) {
                if (response ? !property.couldSerialize() : !property.couldDeserialize()) continue;
                var member = property.getPrimaryMember();
                if (description.getIgnoredPropertyNames().contains(property.getName())) continue;
                var ignored = member == null ? null : member.getAnnotation(JsonIgnore.class);
                if (ignored != null && ignored.value()) continue;
                boolean nonnull = property.isRequired() || marked(member, NotNull.class)
                        || marked(member, NotBlank.class) || marked(member, NotEmpty.class);
                var value = new LinkedHashMap<>(schema(property.getPrimaryType(), response));
                if (member != null) {
                    var minimum = member.getAnnotation(Min.class);
                    if (minimum != null) value.put("minimum", minimum.value());
                    var maximum = member.getAnnotation(Max.class);
                    if (maximum != null) value.put("maximum", maximum.value());
                    if (marked(member, Positive.class)) value.put("exclusiveMinimum", 0);
                    if (marked(member, PositiveOrZero.class)) value.put("minimum", 0);
                    var size = member.getAnnotation(Size.class);
                    if (size != null) size(value, property.getRawPrimaryType(), size);
                }
                properties.put(property.getName(), property.getPrimaryType().isPrimitive() || nonnull
                        ? value : nullable(value));
                if (nonnull) required.add(property.getName());
            }
            if (properties.isEmpty()) throw new IllegalArgumentException("Unsupported DTO " + type);
            definition.put("properties", properties);
            if (!required.isEmpty()) definition.put("required", required.stream().sorted().toList());
        }
        return Map.of("$ref", "#/components/schemas/" + name);
    }

    private static boolean marked(AnnotatedMember member, Class<? extends Annotation> type) {
        return member != null && member.getAnnotation(type) != null;
    }
    private static Map<String, Object> nullable(Map<String, Object> schema) {
        return schema.isEmpty() ? schema : Map.of("anyOf", List.of(schema, Map.of("type", "null")));
    }
    private static void size(Map<String, Object> schema, Class<?> raw, Size constraint) {
        String minimum = raw == String.class ? "minLength" : Map.class.isAssignableFrom(raw) ? "minProperties" : "minItems";
        String maximum = raw == String.class ? "maxLength" : Map.class.isAssignableFrom(raw) ? "maxProperties" : "maxItems";
        schema.put(minimum, constraint.min());
        if (constraint.max() != Integer.MAX_VALUE) schema.put(maximum, constraint.max());
    }
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 10);
        } catch (NoSuchAlgorithmException missing) { throw new IllegalStateException(missing); }
    }
}
