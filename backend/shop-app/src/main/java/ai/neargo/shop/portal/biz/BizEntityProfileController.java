package ai.neargo.shop.portal.biz;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.merchant.service.EntityRegistrationProjection;
import ai.neargo.shop.merchant.service.MerchantEntityService;
import ai.neargo.shop.payclient.EntityProfileRemoteCalls;
import ai.neargo.shop.payclient.PayEntityProfileApi;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Account-owned entity facade. Store selection cannot redirect a material write. */
@Profile("api")
@RestController
@Validated
@ConditionalOnProperty(name = "shop.pay.entity-profiles.enabled", havingValue = "true")
@RequestMapping("/biz/entities/{entity}")
@PreAuthorize("@perm.merchantAccount()")
public class BizEntityProfileController {
  private final MerchantEntityService entities;
  private final PayEntityProfileApi pay;
  private final StoreSyncProperties properties;
  private final EntityRegistrationProjection registration;

  public BizEntityProfileController(
      MerchantEntityService entities,
      PayEntityProfileApi pay,
      StoreSyncProperties properties,
      EntityRegistrationProjection registration) {
    this.entities = entities;
    this.pay = pay;
    this.properties = properties;
    this.registration = registration;
  }

  public record Command(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestNo,
      @Min(0) long expectedVersion,
      @NotBlank @Size(max = 32) String schemaVersion,
      @Size(max = 120)
          Map<@NotBlank @Size(max = 64) String, @NotNull @Size(max = 512) String> fields,
      @Size(max = 30)
          Map<@NotBlank @Size(max = 64) String, @NotBlank @Size(max = 128) String> documents,
      @Size(max = 3) List<@NotBlank String> channels,
      @Size(max = 64) String consentVersion,
      Boolean confirmed,
      @Size(max = 3)
          Map<
                  @NotBlank @Pattern(regexp = "NEWEBPAY|LINEPAY|JKOPAY") String,
                  @NotNull @Size(max = 20)
                  Map<@NotBlank @Size(max = 64) String, @NotNull @Size(max = 512) String>>
              channelMaterials) {
    @Override
    public String toString() {
      return "EntityProfileCommand[REDACTED]";
    }
  }

  private String owner(String entity) {
    var user = BizAuthController.personalUser();
    entities.detail(user.userNo(), entity);
    return user.userNo();
  }

  private Map<String, Object> command(String entity, Command c) {
    String actor = owner(entity);
    var body = new LinkedHashMap<String, Object>();
    body.put("sourceScope", properties.sourceScope());
    body.put("actorRef", actor);
    body.put("requestNo", c.requestNo);
    body.put("expectedVersion", c.expectedVersion);
    body.put("schemaVersion", c.schemaVersion);
    if (c.fields != null) body.put("fields", c.fields);
    if (c.documents != null) body.put("documents", c.documents);
    if (c.channels != null) body.put("channels", c.channels);
    if (c.consentVersion != null) body.put("consentVersion", c.consentVersion);
    if (c.channelMaterials != null) body.put("channelMaterials", c.channelMaterials);
    body.put("confirmed", Boolean.TRUE.equals(c.confirmed));
    return body;
  }

  /** The entity contact (V46): saved outside material revisions, in any review state. */
  public record ContactCommand(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestNo,
      @Min(0) long expectedContactVersion,
      @NotBlank @Size(max = 32) String schemaVersion,
      @NotBlank @Pattern(regexp = "ACCOUNT|REPRESENTATIVE|OTHER") String contactSource,
      @Size(max = 64) String contactName,
      @Pattern(regexp = "09[0-9]{8}") String contactMobile,
      @Email @Size(max = 128) String contactEmail,
      @Email @Size(max = 128) String disputeEmail,
      Boolean contactSameAddress,
      @Size(max = 16) String contactCity,
      @Size(max = 16) String contactArea,
      @Size(max = 128) String contactAddress) {
    @Override
    public String toString() {
      return "EntityContactCommand[REDACTED]";
    }
  }

  /** A Taiwan mobile in local form: {@code +886 912-345-678} → {@code 0912345678}; else null. */
  public static String localMobile(String phone) {
    if (phone == null) return null;
    String digits = phone.replaceAll("[\\s-]", "");
    if (digits.matches("09[0-9]{8}")) return digits;
    var intl = java.util.regex.Pattern.compile("^(?:\\+|00)886(9[0-9]{8})$").matcher(digits);
    return intl.matches() ? "0" + intl.group(1) : null;
  }

  @PostMapping("/contact")
  public Map<String, Object> contact(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity,
      @RequestBody @Valid ContactCommand c) {
    var user = BizAuthController.personalUser();
    var account = entities.ownerContact(user.userNo(), entity);
    String name = c.contactName, mobile = c.contactMobile;
    // 「账号本人」: the account is the source of truth, not the client.
    if ("ACCOUNT".equals(c.contactSource)) {
      if (account.name() != null && !account.name().isBlank()) name = account.name();
      String local = localMobile(account.phone());
      if (local != null) mobile = local;
    }
    var body = new LinkedHashMap<String, Object>();
    body.put("sourceScope", properties.sourceScope());
    body.put("actorRef", user.userNo());
    body.put("requestNo", c.requestNo);
    body.put("expectedContactVersion", c.expectedContactVersion);
    body.put("schemaVersion", c.schemaVersion);
    body.put("contactSource", c.contactSource);
    if (name != null) body.put("contactName", name);
    if (mobile != null) body.put("contactMobile", mobile);
    if (c.contactEmail != null) body.put("contactEmail", c.contactEmail);
    if (c.disputeEmail != null) body.put("disputeEmail", c.disputeEmail);
    body.put("contactSameAddress", !Boolean.FALSE.equals(c.contactSameAddress));
    if (c.contactCity != null) body.put("contactCity", c.contactCity);
    if (c.contactArea != null) body.put("contactArea", c.contactArea);
    if (c.contactAddress != null) body.put("contactAddress", c.contactAddress);
    return EntityProfileRemoteCalls.call(() -> pay.contact(entity, body));
  }

  @PostMapping("/revision-discards")
  public Map<String, Object> discardRevision(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity,
      @RequestBody @Valid Command c) {
    return EntityProfileRemoteCalls.call(() -> pay.discardRevision(entity, command(entity, c)));
  }

  /**
   * Owner-only thumbnail; the path carries the file id (the reference's 32 hex characters). A
   * {@link org.springframework.core.io.Resource} body is not wrapped in the API envelope.
   */
  @GetMapping("/files/{file}/preview")
  public ResponseEntity<org.springframework.core.io.Resource> preview(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity,
      @PathVariable @Pattern(regexp = "[a-f0-9]{32}") String file) {
    String actor = owner(entity);
    var served =
        EntityProfileRemoteCalls.call(
            () -> pay.preview(entity, file, properties.sourceScope(), actor));
    byte[] bytes = served == null ? null : served.getBody();
    if (bytes == null || bytes.length == 0) return ResponseEntity.noContent().build();
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_JPEG)
        .header("Content-Disposition", "inline")
        .header("X-Content-Type-Options", "nosniff")
        .body(new ByteArrayResource(bytes));
  }

  @GetMapping("/profile-template")
  public Map<String, Object> template(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity) {
    owner(entity);
    return EntityProfileRemoteCalls.call(() -> pay.template(entity, properties.sourceScope()));
  }

  @GetMapping("/profile-draft")
  public Map<String, Object> read(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity) {
    var user = BizAuthController.personalUser();
    var identity = entities.detail(user.userNo(), entity).entity();
    var result =
        new LinkedHashMap<String, Object>(
            EntityProfileRemoteCalls.call(() -> pay.read(entity, properties.sourceScope())));
    var fields = new LinkedHashMap<String, Object>();
    if (result.get("fields") instanceof Map<?, ?> saved)
      saved.forEach((k, v) -> fields.put((String) k, v));
    fields.putIfAbsent("name", identity.name());
    if (identity.legalForm() != null) fields.putIfAbsent("legalForm", identity.legalForm());
    result.put("fields", fields);
    return result;
  }

  @PostMapping("/profile-draft")
  public Map<String, Object> save(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity,
      @RequestBody @Valid Command c) {
    return EntityProfileRemoteCalls.call(() -> pay.save(entity, command(entity, c)));
  }

  @PostMapping("/review-submissions")
  public Map<String, Object> submit(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity,
      @RequestBody @Valid Command c) {
    var body = command(entity, c);
    registration.observe(
        BizAuthController.personalUser().tenantNo(),
        entity,
        (String) body.get("actorRef"),
        c.requestNo());
    return EntityProfileRemoteCalls.call(() -> pay.submit(entity, body));
  }

  @GetMapping("/commands/{request}")
  public Map<String, Object> result(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity,
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String request) {
    return EntityProfileRemoteCalls.call(
        () -> pay.result(entity, request, properties.sourceScope(), owner(entity)));
  }

  @PostMapping(value = "/files", consumes = "multipart/form-data")
  public Map<String, Object> upload(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity,
      @RequestParam @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestNo,
      @RequestParam @NotBlank @Size(max = 64) String purpose,
      @RequestPart("file") MultipartFile file)
      throws IOException {
    String actor = owner(entity);
    if (file.getSize() > 5242880) throw BizException.of(ErrorCode.BAD_REQUEST);
    byte[] bytes = file.getBytes();
    var resource =
        new ByteArrayResource(bytes) {
          @Override
          public String getFilename() {
            return file.getOriginalFilename();
          }
        };
    try {
      return EntityProfileRemoteCalls.call(
          () -> pay.upload(entity, properties.sourceScope(), actor, requestNo, purpose, resource));
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  @PostMapping("/revisions")
  public Map<String, Object> revise(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity,
      @RequestBody @Valid Command c) {
    return EntityProfileRemoteCalls.call(() -> pay.revise(entity, command(entity, c)));
  }

  @PostMapping("/channel-requests")
  public Map<String, Object> addChannels(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity,
      @RequestBody @Valid Command c) {
    return EntityProfileRemoteCalls.call(() -> pay.addChannels(entity, command(entity, c)));
  }

  @ModelAttribute
  public void privateResponse(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
  }
}
