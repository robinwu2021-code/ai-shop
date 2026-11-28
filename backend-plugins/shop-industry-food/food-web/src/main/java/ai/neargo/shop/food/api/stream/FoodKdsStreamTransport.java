package ai.neargo.shop.food.api.stream;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 屏的刷新通知流：只推「来刷新」与它该看的工位（TDD-餐饮域迁移新库 §2.8 D-3）。 */
public interface FoodKdsStreamTransport {
    SseEmitter subscribe(String entity, String store, String actor, String device);
}
