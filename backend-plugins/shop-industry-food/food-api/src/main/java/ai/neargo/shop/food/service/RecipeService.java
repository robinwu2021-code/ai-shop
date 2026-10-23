package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CreateRecipeReq;
import ai.neargo.shop.food.dto.ListRecipesQuery;
import ai.neargo.shop.food.dto.RecipeResultVO;
import ai.neargo.shop.food.dto.RecipeVO;
import ai.neargo.shop.food.dto.ReplaceRecipeDefinitionReq;

/** Store recipes extend original SKUs and inventory materials, without duplicating either authority. */
public interface RecipeService {
    RecipeResultVO createRecipe(String entityNo, String storeNo, String actorNo,
            CreateRecipeReq req, String idempotencyKey);
    RecipeResultVO replaceDefinition(String entityNo, String storeNo, String recipeNo, String actorNo,
            ReplaceRecipeDefinitionReq req, String idempotencyKey);
    RecipeResultVO getRecipe(String entityNo, String storeNo, String recipeNo);
    PageData<RecipeVO> listRecipes(String entityNo, String storeNo, ListRecipesQuery query);
}
