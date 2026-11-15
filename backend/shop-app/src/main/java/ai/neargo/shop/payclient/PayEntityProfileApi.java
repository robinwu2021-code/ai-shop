package ai.neargo.shop.payclient;

import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.*;

/** Trusted PAY entity-profile contract. No public caller can select source scope or actor. */
public interface PayEntityProfileApi {
  String ROOT = "/internal/pay/v2/entity-profiles/{entity}";

  @GetExchange(ROOT + "/profile-template")
  Map<String, Object> template(
      @PathVariable("entity") String entity, @RequestParam("sourceScope") String scope);

  @GetExchange(ROOT + "/profile-draft")
  Map<String, Object> read(
      @PathVariable("entity") String entity, @RequestParam("sourceScope") String scope);

  @PostExchange(ROOT + "/profile-draft")
  Map<String, Object> save(
      @PathVariable("entity") String entity, @RequestBody Map<String, Object> body);

  @PostExchange(ROOT + "/review-submissions")
  Map<String, Object> submit(
      @PathVariable("entity") String entity, @RequestBody Map<String, Object> body);

  @GetExchange(ROOT + "/commands/{request}")
  Map<String, Object> result(
      @PathVariable("entity") String entity,
      @PathVariable("request") String request,
      @RequestParam("sourceScope") String scope,
      @RequestParam("actorRef") String actor);

  @PostExchange(value = ROOT + "/files", contentType = "multipart/form-data")
  Map<String, Object> upload(
      @PathVariable("entity") String entity,
      @RequestParam("sourceScope") String scope,
      @RequestParam("actorRef") String actor,
      @RequestParam("requestNo") String request,
      @RequestParam("purpose") String purpose,
      @RequestPart("file") Resource file);

  @PostExchange(ROOT + "/revisions")
  Map<String, Object> revise(
      @PathVariable("entity") String entity, @RequestBody Map<String, Object> body);

  @PostExchange(ROOT + "/channel-requests")
  Map<String, Object> addChannels(
      @PathVariable("entity") String entity, @RequestBody Map<String, Object> body);

  record SummaryQuery(String sourceScope, List<String> entities) {}

  @PostExchange("/internal/pay/v2/entity-profiles/summaries")
  Map<String, Map<String, Object>> summaries(@RequestBody SummaryQuery body);

  @GetExchange(ROOT + "/approved-registration")
  Map<String, Object> approvedRegistration(
      @PathVariable("entity") String entity, @RequestParam("sourceScope") String scope);
}
