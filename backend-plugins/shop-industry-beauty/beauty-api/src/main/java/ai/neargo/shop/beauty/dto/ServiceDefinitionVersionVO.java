package ai.neargo.shop.beauty.dto;

/** Stable publication identity; physical IDs and mutable product pricing never enter the response. */
public record ServiceDefinitionVersionVO(String definitionNo, String definitionVersionNo, long revision,
        String snapshotHash) { }
