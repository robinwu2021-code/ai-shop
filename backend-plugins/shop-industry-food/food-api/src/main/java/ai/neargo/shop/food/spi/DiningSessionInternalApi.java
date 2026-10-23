package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.DiningSessionTableVO;
import ai.neargo.shop.food.dto.DiningSessionVO;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.RevokeTableTokenReq;
import ai.neargo.shop.food.dto.RotateTableTokenReq;
import ai.neargo.shop.food.dto.StartDiningSessionReq;
import ai.neargo.shop.food.dto.TableTokenVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Trusted platform context over the original service-authenticated internal transport. */
public interface DiningSessionInternalApi {
    String ROTATE_PATH="/internal/food/table-tokens/rotate";
    String REVOKE_PATH="/internal/food/table-tokens/revoke";
    String RESOLVE_PATH="/internal/food/dining-sessions/resolve-table";
    String START_PATH="/internal/food/dining-sessions/start";
    String END_PATH="/internal/food/dining-sessions/end";
    @PostExchange(ROTATE_PATH) TableTokenVO rotate(@RequestBody RotateCommand command);
    @PostExchange(REVOKE_PATH) OperationResultVO revoke(@RequestBody RevokeCommand command);
    @PostExchange(RESOLVE_PATH) DiningSessionTableVO resolve(@RequestBody ResolveCommand command);
    @PostExchange(START_PATH) DiningSessionVO start(@RequestBody StartCommand command);
    @PostExchange(END_PATH) OperationResultVO end(@RequestBody EndCommand command);
    record RotateCommand(@NotBlank @Size(max=32) String entityNo,@NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String tableNo,@NotBlank @Size(max=32) String actorNo,
            @NotBlank @Size(max=128) String idempotencyKey,@Valid @NotNull RotateTableTokenReq request) { }
    record RevokeCommand(@NotBlank @Size(max=32) String entityNo,@NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String tableNo,@NotBlank @Size(max=32) String actorNo,
            @NotBlank @Size(max=128) String idempotencyKey,@Valid @NotNull RevokeTableTokenReq request) { }
    record ResolveCommand(@NotBlank @Size(max=128) String tableToken) {
        @Override public String toString() { return "ResolveCommand[REDACTED]"; }
    }
    record StartCommand(@NotBlank @Size(max=32) String entityNo,@NotBlank @Size(max=32) String storeNo,
            @Size(max=32) String buyerNo,@NotBlank @Size(max=128) String idempotencyKey,
            @Valid @NotNull StartDiningSessionReq request) { }
    record EndCommand(@NotBlank @Size(max=32) String sessionNo,@NotBlank @Size(max=128) String accessToken,
            @Size(max=32) String buyerNo,@NotBlank @Size(max=128) String idempotencyKey) {
        @Override public String toString() { return "EndCommand[REDACTED]"; }
    }
}
