package ai.neargo.shop.food.api.stream;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Keeps the FoodKdsStreamTransport business boundary scoped to original restaurant facts and
 * lifecycle authority.
 */
public interface FoodKdsStreamTransport {
    SseEmitter subscribe(
            String entity, String store, String actor, String subscription, long cursor);
}
