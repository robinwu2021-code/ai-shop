#!/usr/bin/env python3
"""Structural test fixture for the embedded B07 test, never a production bootstrap.

Reuse the existing migration projection policy (including native-absent owner families)
while retaining MySQL column types and constraints. Beauty runs its real Flyway chain.
"""
from pathlib import Path
import re,runpy,sys
root=Path(sys.argv[1]).resolve()
m=runpy.run_path(str(root/'backend/scripts/gen-test-schema.py'))
m['create_table'].__globals__['_h2_type']=lambda column:column
tables={};order=[];seeds=[];renames={};altered=set()
source=root/'backend/shop-app/src/main/resources/db/migration'
policies=m['load_projection_policy'](source)
for file in sorted(source.glob('V*.sql'),key=lambda p:int(re.match(r'V(\d+)',p.name).group(1))):
    if file.resolve() in policies:
        m['replay_statements'](m['project_migration'](file,policies),tables,order,seeds,renames,altered)
    else:
        m['replay'](file.read_text(),tables,order,seeds,renames,altered)
for extra in [root/'backend/pay/pay-domain/src/main/resources/db/pay',root/'backend/secret/secret-store/src/main/resources/db/secret']:
    for file in sorted(extra.glob('V*.sql'),key=lambda p:int(re.match(r'V(\d+)',p.name).group(1))):
        m['replay'](file.read_text(),tables,order,seeds,renames,altered)
out=['SET FOREIGN_KEY_CHECKS=0;']
for name in order:
    out.append('CREATE TABLE '+name+' (\n'+',\n'.join(tables[name])+'\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;')
for table,snap,stmt in seeds:
    if table in altered:
        cols=m['_column_names'](snap)
        if cols:
            stmt=re.sub(r'^(INSERT\s+INTO\s+\w+)\s+VALUES\b',lambda x:x.group(1)+' ('+', '.join(cols)+') VALUES',
                        stmt,count=1,flags=re.S|re.I)
    if re.match(r'^INSERT\s+INTO\s+mch_admission_policy\b',stmt,re.I):
        stmt=stmt.replace("'MICRO'","'NATURAL_PERSON'")
    for old,new in renames.items():stmt=re.sub(r'\b'+re.escape(old)+r'\b',new,stmt)
    stmt=re.sub(r'^INSERT\s+INTO','INSERT IGNORE INTO',stmt,flags=re.I)
    out.append(stmt+';')
out.append('SET FOREIGN_KEY_CHECKS=1;')
Path(sys.argv[2]).write_text('\n\n'.join(out))
print('Native structural fixture',len(order),'tables')
