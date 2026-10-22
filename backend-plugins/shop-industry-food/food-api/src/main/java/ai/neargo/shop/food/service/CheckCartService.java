package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.AuthorizeCartSessionReq;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CartSessionGrantVO;
import ai.neargo.shop.food.dto.CartVersionReq;
import ai.neargo.shop.food.dto.CartVersionVO;
import ai.neargo.shop.food.dto.CreateCartReq;
import ai.neargo.shop.food.dto.CreateCustomerCartReq;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.ReplaceCartLineReq;

/** Draft ownership and CAS are independent of retail order, payment and stock transaction authority. */
public interface CheckCartService {
    CartResultVO createCart(String entity, String store, String actor, CreateCartReq req, String key);
    CartResultVO getCart(String entity, String store, String cart);
    CartVersionVO replaceLine(String entity, String store, String cart, String source, String actor,
            ReplaceCartLineReq req, String key);
    CartVersionVO archiveLine(String entity, String store, String cart, String source, String actor,
            CartVersionReq req, String key);
    OperationResultVO archiveCart(String entity, String store, String cart, String actor,
            CartVersionReq req, String key);
    CartSessionGrantVO authorizeSession(String entity, String store, String check, String actor,
            AuthorizeCartSessionReq req, String key);
    CartResultVO customerCreateCart(String store, CartAccess access, CreateCustomerCartReq req, String key);
    CartResultVO customerGetCart(String cart, CartAccess access);
    CartVersionVO customerReplaceLine(String cart, String source, CartAccess access,
            ReplaceCartLineReq req, String key);
    CartVersionVO customerArchiveLine(String cart, String source, CartAccess access, CartVersionReq req, String key);
}
