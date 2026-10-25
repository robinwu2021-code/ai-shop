package ai.neargo.shop.food.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Keeps the FoodKdsStreamService business boundary scoped to original restaurant facts and
 * lifecycle authority.
 */
public interface FoodKdsStreamService {
    SseEmitter subscribe(
            String entity, String store, String actor, String subscription, long cursor);
}
