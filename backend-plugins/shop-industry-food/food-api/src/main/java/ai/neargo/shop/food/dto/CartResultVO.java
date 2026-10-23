package ai.neargo.shop.food.dto;


/** Typed cart boundary keeps draft identities separate from authoritative quotes and orders. */
public record CartResultVO(
        CartVO cart) {
}
