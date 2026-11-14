#!/usr/bin/env python3
"""Check the prospective v2 schema/registry. Does not call any server."""
import argparse
import copy
import json
from pathlib import Path
import jsonschema
p=argparse.ArgumentParser()
p.add_argument('--mirror', type=Path)
a=p.parse_args()
root=Path(__file__).resolve().parents[1]
base=root/'docs/api'
schema=json.loads((base/'app-events-v2.schema.json').read_text())
registry=json.loads((base/'app-events-v2-registry.json').read_text())
fixture=json.loads((base/'fixtures/app-events-v2.json').read_text())['payment']
jsonschema.Draft202012Validator.check_schema(schema)
v=jsonschema.Draft202012Validator(schema)
v.validate(fixture)
keys=set()
allowed={'invalidate','verifyDetail','banner','voiceEligible','cancelUnauthorized'}
for rule in registry['rules']:
    key=(rule['eventType'],rule['functionCode'])
    assert key not in keys, key
    keys.add(key)
    assert set(rule['actions'])<=allowed
    event=copy.deepcopy(fixture)
    event.update(eventType=rule['eventType'],sceneCode=rule['sceneCode'],functionCode=rule['functionCode'])
    event['resource']['type']=rule['resourceType']
    v.validate(event)
for label,mutate in [('version',lambda e:e.update(schemaVersion=1)),('money',lambda e:e['payload'].update(paidMinor=1.0)),('resource',lambda e:e['resource'].update(type='BOOKING')),('scope',lambda e:e['scope'].pop('storeNo')),('function',lambda e:e.update(functionCode='ACCESS'))]:
    invalid=copy.deepcopy(fixture);mutate(invalid)
    assert list(v.iter_errors(invalid)), label
if a.mirror:
    for name in ['app-events-v2.schema.json','app-events-v2-registry.json','app-events-v2.openapi.json','fixtures/app-events-v2.json']:
        assert (base/name).read_bytes()==(a.mirror/'docs/api'/name).read_bytes(),name
print(f"PASS: {len(keys)} rules, valid fixture, 5 invalid fixtures, mirror consistency")
