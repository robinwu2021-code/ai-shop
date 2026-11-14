package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.*;

public record ShippingAddress(String recipient, String phone, String countryCode, String region, String city, String addressLine, String postalCode) {}
