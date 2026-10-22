package ai.neargo.shop.food.dto;

/** Trusted consumers interpret historical content from this frozen document only. */
public record ConfigRevisionContentVO(ConfigRevisionVO publication, String definitionSnapshot) { }
