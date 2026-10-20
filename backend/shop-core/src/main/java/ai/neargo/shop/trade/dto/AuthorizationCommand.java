package ai.neargo.shop.trade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Min;

/** Consumer confirmation references a previously staged trusted quote; no amount or identity is accepted. */
public record AuthorizationCommand(@Min(0) long orderRevision,
                                    @NotBlank @Pattern(regexp = "[A-Za-z0-9:_-]{1,64}") String quoteRef,
                                    @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String selectionDigest,
                                    @NotBlank @Pattern(regexp = "[A-Za-z0-9:_-]{1,64}") String requestKey) { }
