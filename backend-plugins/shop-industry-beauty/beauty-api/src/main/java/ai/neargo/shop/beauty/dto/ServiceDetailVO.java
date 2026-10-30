package ai.neargo.shop.beauty.dto;

/** Merchant display shares public facts and adds the versioned definition reference for maintenance. */
public record ServiceDetailVO(PublicServiceVO service, String definitionNo, long revision) { }
