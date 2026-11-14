package ai.neargo.shop.food.server.api;

import ai.neargo.shop.food.spi.FoodStockSyncInternalApi.Dispatch;
import org.springframework.validation.Errors;
import org.springframework.validation.ValidationUtils;
import org.springframework.validation.Validator;

final class FoodStockSyncDispatchValidator implements Validator {
    @Override public boolean supports(Class<?> type) { return Dispatch.class.isAssignableFrom(type); }
    @Override public void validate(Object target, Errors errors) {
        ValidationUtils.rejectIfEmptyOrWhitespace(errors, "entityNo", "required");
        ValidationUtils.rejectIfEmptyOrWhitespace(errors, "storeNo", "required");
        ValidationUtils.rejectIfEmpty(errors, "envelope", "required");
    }
}
