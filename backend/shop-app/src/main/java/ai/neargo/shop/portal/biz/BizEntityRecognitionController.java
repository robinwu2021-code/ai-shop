package ai.neargo.shop.portal.biz;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.merchant.service.MerchantEntityService;
import ai.neargo.shop.payclient.EntityProfileRemoteCalls;
import ai.neargo.shop.payclient.PayEntityProfileApi;
import ai.neargo.shop.platform.MarketPhonePolicyService;
import ai.neargo.shop.portal.common.CertDocumentReader;
import ai.neargo.shop.spi.product.CertVisionPort;
import ai.neargo.shop.spi.product.CertVisionPort.DocumentType;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * App 经营实体资料的证件识别（TDD-经营实体证件识别）。只回建议，不保存文件或资料；
 * 商家确认后随 profile-draft 落库。
 */
@Profile("api")
@RestController
@Validated
@ConditionalOnProperty(name = "shop.pay.entity-profiles.enabled", havingValue = "true")
@PreAuthorize("@perm.merchantAccount()")
public class BizEntityRecognitionController {
  static final double LOW_CONFIDENCE = 0.8;
  private static final java.util.regex.Pattern FILE_REF =
      java.util.regex.Pattern.compile("file:entity-profile/([a-f0-9]{32})");
  private static final java.util.regex.Pattern DATE = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

  private final CertVisionPort vision;
  private final MarketPhonePolicyService markets;
  private final MerchantEntityService entities;
  private final PayEntityProfileApi pay;
  private final StoreSyncProperties properties;

  public BizEntityRecognitionController(
      CertVisionPort vision,
      MarketPhonePolicyService markets,
      MerchantEntityService entities,
      PayEntityProfileApi pay,
      StoreSyncProperties properties) {
    this.vision = vision;
    this.markets = markets;
    this.entities = entities;
    this.pay = pay;
    this.properties = properties;
  }

  /** App 的识别类型；与端口类型不同名的只有登记证明（OPS 的 REGISTRATION 地址不拆分）。 */
  public enum EntityRecognitionType {
    REGISTRATION(DocumentType.ENTITY_REGISTRATION),
    CORPORATE_REGISTRATION(DocumentType.CORPORATE_REGISTRATION),
    ID_CARD_FRONT(DocumentType.ID_CARD_FRONT),
    ARC_FRONT(DocumentType.ARC_FRONT),
    ARC_BACK(DocumentType.ARC_BACK),
    PASSPORT(DocumentType.PASSPORT),
    BENEFICIARY_ID_CARD_FRONT(DocumentType.BENEFICIARY_ID_CARD_FRONT),
    BANK_COVER(DocumentType.BANK_COVER);

    final DocumentType port;

    EntityRecognitionType(DocumentType port) {
      this.port = port;
    }
  }

  public record Command(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestNo,
      @NotBlank String fileRef,
      @NotNull EntityRecognitionType docType) {}

  public record Result(
      EntityRecognitionType docType,
      boolean recognized,
      Map<String, String> fields,
      List<String> lowConfidence) {
    static Result none(EntityRecognitionType type) {
      return new Result(type, false, Map.of(), List.of());
    }
  }

  /** 新增经营实体前读登记证明：实体还不存在，文件只读不存。 */
  @PostMapping(value = "/biz/entity-recognitions", consumes = "multipart/form-data")
  public Result beforeCreate(
      @RequestParam("file") MultipartFile file,
      @RequestParam("docType") EntityRecognitionType docType,
      @RequestParam("requestNo") @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestNo,
      HttpServletResponse response)
      throws IOException {
    response.setHeader("Cache-Control", "no-store");
    if (docType != EntityRecognitionType.REGISTRATION) throw BizException.of(ErrorCode.BAD_REQUEST);
    taiwanOnly();
    return recognize(CertDocumentReader.read(file), docType);
  }

  /** 读已上传到该实体的私有文件；文件经 near-pay 按本人与来源回读，不重新上传。 */
  @PostMapping("/biz/entities/{entity}/recognitions")
  public Result uploaded(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity,
      @RequestBody @Valid Command c,
      HttpServletResponse response)
      throws IOException {
    response.setHeader("Cache-Control", "no-store");
    var ref = FILE_REF.matcher(c.fileRef());
    if (!ref.matches()) throw BizException.of(ErrorCode.BAD_REQUEST);
    taiwanOnly();
    String actor = BizAuthController.personalUser().userNo();
    entities.detail(actor, entity);
    var served =
        EntityProfileRemoteCalls.call(
            () -> pay.content(entity, ref.group(1), properties.sourceScope(), actor));
    var type = served.getHeaders().getContentType();
    return recognize(CertDocumentReader.read(served.getBody(), type == null ? null : type.toString()), c.docType());
  }

  private void taiwanOnly() {
    if (!"TW".equals(markets.instancePolicy().market())) throw BizException.of(ErrorCode.BAD_REQUEST);
  }

  private Result recognize(CertDocumentReader.Input input, EntityRecognitionType type) {
    if (!vision.isEnabled() || !vision.supportsDocuments()) return Result.none(type);
    var document = vision.recognizeDocument(input.bytes(), input.contentType(), type.port);
    if (document == null) return Result.none(type);
    var fields = normalize(document.fields());
    if (fields.isEmpty()) return Result.none(type);
    return new Result(
        type,
        true,
        fields,
        document.confidence() < LOW_CONFIDENCE ? List.copyOf(fields.keySet()) : List.of());
  }

  /** 按 App 合同归一取值；不合规的丢弃，不猜。 */
  static Map<String, String> normalize(Map<String, String> raw) {
    var out = new LinkedHashMap<String, String>();
    raw.forEach(
        (key, value) -> {
          String v = RULES.getOrDefault(key, UnaryOperator.identity()).apply(value.trim());
          if (v != null && !v.isEmpty()) out.put(key, v);
        });
    return out;
  }

  private static final UnaryOperator<String> DATE_ONLY = v -> DATE.matcher(v).matches() ? v : null;
  private static final UnaryOperator<String> DIGITS = v -> v.matches("\\d+") ? v : null;
  private static final Map<String, UnaryOperator<String>> RULES =
      Map.of(
          "birthDate", DATE_ONLY,
          "issueDate", DATE_ONLY,
          "expiryDate", DATE_ONLY,
          "incorporationDate", DATE_ONLY,
          "capitalAmount", DIGITS,
          "corporateCapital", DIGITS,
          "legalForm", v -> v.equals("ENTERPRISE") || v.equals("INDIVIDUAL") ? v : null,
          "issuePlace", v -> v.replaceAll("[()（）]", ""),
          "idIssueType",
          v ->
              switch (v) {
                case "初發", "FIRST" -> "FIRST";
                case "補發", "REPLACEMENT" -> "REPLACEMENT";
                case "換發", "RENEWAL" -> "RENEWAL";
                default -> null;
              });
}
