import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {renderFoodV1,writeFrozenMigration,assertFrozenFoodV1} from './lib/food-schema-sql.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const s=JSON.parse(fs.readFileSync(path.join(root,'docs/technical/design/schema/food-system-design.json'),'utf8'));
const file=path.join(root,'backend-plugins/shop-industry-food/food-core/src/main/resources/db/industry/food/V1__food_business_tables.sql');
const sql=renderFoodV1(s);
assertFrozenFoodV1(sql);
if(process.argv.includes('--check')){
 if(!fs.existsSync(file)||fs.readFileSync(file,'utf8')!==sql)throw Error('Food schema differs from frozen migration');
}else{fs.mkdirSync(path.dirname(file),{recursive:true});writeFrozenMigration(file,sql);}
console.log('Food schema: '+s.tables.filter(t=>t.name.startsWith('fnb_')).length+' fnb_ tables; shared prn_/rpt_ excluded.');
