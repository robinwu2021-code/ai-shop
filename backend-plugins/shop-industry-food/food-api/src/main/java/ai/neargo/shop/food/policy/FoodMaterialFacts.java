package ai.neargo.shop.food.policy;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort.MaterialFact;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Explicit restaurant exact-unit facts; historical inventory fact hashes keep their original contract. */
public final class FoodMaterialFacts {
    private FoodMaterialFacts(){}
    public static boolean validFact(MaterialFact fact) {
        if(fact==null)return false;
        if(!"FOOD_LOCAL".equals(fact.dataSource()))return MaterialCatalogFacts.validFact(fact);
        return MaterialCatalogFacts.validKey(fact.itemId(),32)&&MaterialCatalogFacts.validKey(fact.entityNo(),32)
                &&fact.name()!=null&&!fact.name().isBlank()&&fact.name().length()<=128
                &&MaterialCatalogFacts.validKey(fact.baseUom(),16)
                &&(Integer.valueOf(0).equals(fact.weighed())||Integer.valueOf(1).equals(fact.weighed()))
                &&"BASE_ONLY".equals(fact.unitSupport())&&"EXACT_DECIMAL_6".equals(fact.postingSupport())
                &&hash(fact.itemId(),fact.entityNo(),fact.name(),fact.baseUom(),fact.weighed()).equals(fact.definitionHash());
    }
    public static String hash(String item,String entity,String name,String unit,Integer weighed) {
        if(item==null||entity==null||name==null||unit==null||weighed==null)throw new IllegalArgumentException("Incomplete local material identity");
        try{var hash=MessageDigest.getInstance("SHA-256");for(var value:new String[]{"FOOD_LOCAL_MATERIAL_V1",entity,item,name,unit,weighed.toString(),"FOOD_LOCAL","BASE_ONLY","EXACT_DECIMAL_6"}){
            var bytes=value.getBytes(StandardCharsets.UTF_8);hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);
        }return HexFormat.of().formatHex(hash.digest());}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
