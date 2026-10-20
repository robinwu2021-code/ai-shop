package ai.neargo.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import ai.neargo.shop.common.PageData;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class EntitlementOpenApiExporterTest {
    record Input(@JsonProperty("externalName") @NotBlank String name, @JsonIgnore String secret) { }
    record Output(@JsonProperty("label") String title, Long amountMinor, @JsonIgnore String internalNote) { }
    static class Endpoint {
        public PageData<Output> create(@RequestBody Input input) { return new PageData<>(List.of(), 0, 1, 20); }
    }
    @Test void actualJacksonNamesAndIgnoredFieldsAndPaginationGenericsSurviveExport(@TempDir Path directory)
            throws Exception {
        var mapper = new ObjectMapper();
        var exporter = new EntitlementOpenApiExporter(mapper);
        exporter.add("POST", "/test", new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("create", Input.class)), false);
        Path artifact = directory.resolve("api.json");
        exporter.write(artifact);
        var spec = mapper.readTree(Files.readString(artifact));
        JsonNode input = null, output = null, page = null;
        for (var definition : spec.path("components").path("schemas")) {
            String javaType = definition.path("x-java-type").asText();
            if (javaType.equals(Input.class.getName())) input = definition;
            if (javaType.equals(Output.class.getName())) output = definition;
            if (javaType.startsWith(PageData.class.getName())) page = definition;
        }
        assertThat(input).isNotNull();
        assertThat(input.path("properties").has("externalName")).isTrue();
        assertThat(input.path("properties").has("secret")).isFalse();
        assertThat(input.path("required").toString()).contains("externalName");
        assertThat(output).isNotNull();
        assertThat(output.path("properties").has("label")).isTrue();
        assertThat(output.path("properties").has("internalNote")).isFalse();
        assertThat(output.path("properties").path("amountMinor").path("anyOf").get(0).path("format").asText())
                .isEqualTo("int64");
        assertThat(page).isNotNull();
        assertThat(page.path("properties").path("records").path("anyOf").get(0).path("items").path("$ref").asText())
                .startsWith("#/components/schemas/Output_");
        var envelope = spec.path("paths").path("/test").path("post").path("responses").path("200")
                .path("content").path("application/json").path("schema");
        assertThat(envelope.path("properties").has("msg")).isTrue();
        assertThat(envelope.path("properties").has("message")).isFalse();
    }
}
