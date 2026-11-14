import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const root=new URL('../../',import.meta.url);
const s=JSON.parse(fs.readFileSync(new URL('docs/technical/design/schema/food-system-design.json',root)));
test('recipe publication is implemented as gated config revision without inventing schema or stock readiness',()=>{
 const a=s.apis.find(a=>a.id==='FOOD-API-152');
 assert.match(a.needs,/RECIPE-01C/);
 assert.match(a.guard,/schema3/);
 assert.equal(s.databaseInitialization.targetSchemaVersion,25);
 assert.equal(s.catalogEnhancement.runtimeEnabled,false);
 assert.equal(s.catalogEnhancement.contracts.inventory.implementation,'PENDING');
 assert.match(s.dtos.ConfigRevision,/RECIPE/);
 assert.match(s.tables.find(t=>t.name==='fnb_config_revision').fields.find(f=>f.name==='config_kind').label,/RECIPE/);
});
test('new recipe publication writer defaults off while schema readers remain registered',()=>{
 const auto=fs.readFileSync(new URL('backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/config/FoodAutoConfiguration.java',root),'utf8');
 assert(auto.includes('${shop.food.recipe-publication-enabled:false}'));
 const reader=fs.readFileSync(new URL('backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/PublicationDocuments.java',root),'utf8');
 assert.match(reader,/version == 3 && "RECIPE"/);
 assert(!reader.includes('recipe-publication-enabled'));
});
