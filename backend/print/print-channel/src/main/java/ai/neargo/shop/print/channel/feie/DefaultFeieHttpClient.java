package ai.neargo.shop.print.channel.feie;

import ai.neargo.shop.print.api.PrintFailure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Component
public final class DefaultFeieHttpClient implements FeieHttpClient {
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private final String host;
    private final JsonMapper json = JsonMapper.builder().build();

    public DefaultFeieHttpClient(@Value("${shop.print.feie.region:CN}") String region) {
        if (!Set.of("CN", "OVERSEAS").contains(region))
            throw new IllegalArgumentException("Feie region");
        host = region.equals("CN") ? "api.feieyun.cn" : "api.de.feieyun.com";
    }

    public JsonNode call(
            String operation, Map<String, String> credentials, Map<String, String> parameters) {
        if (!Set.of("printMsg", "queryOrderState", "queryPrinterStatus").contains(operation))
            throw new PrintFailure("print.vendorOperationRejected");
        try {
            String user = credentials.get("user"), secret = credentials.get("ukey");
            if (user == null || secret == null) throw new PrintFailure("print.credentialsRequired");
            String time = Long.toString(Instant.now().getEpochSecond());
            var args = new TreeMap<String, String>();
            args.put("user", user);
            args.put("stime", time);
            args.put(
                    "sig",
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-1")
                                            .digest(
                                                    (user + secret + time)
                                                            .getBytes(StandardCharsets.UTF_8))));
            args.put("apiname", "Open_" + operation);
            args.putAll(parameters);
            String body =
                    args.entrySet().stream()
                            .map(
                                    e ->
                                            URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                                                    + "="
                                                    + URLEncoder.encode(
                                                            e.getValue(), StandardCharsets.UTF_8))
                            .collect(java.util.stream.Collectors.joining("&"));
            var request =
                    HttpRequest.newBuilder(URI.create("https://" + host + "/Api/Open/" + operation))
                            .timeout(Duration.ofSeconds(15))
                            .header(
                                    "Content-Type",
                                    "application/x-www-form-urlencoded; charset=UTF-8")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                byte[] bytes = input.readNBytes(65537);
                if (response.statusCode() != 200 || bytes.length > 65536)
                    throw new PrintFailure("print.vendorResponseUnknown");
                return json.readTree(bytes);
            }
        } catch (PrintFailure e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PrintFailure("print.vendorOutcomeUnknown");
        } catch (Exception e) {
            throw new PrintFailure("print.vendorOutcomeUnknown");
        }
    }
}
