import test from 'node:test';
import assert from 'node:assert/strict';
import {hasServiceConsumer,calledPaths} from '../check-ops-orphan.mjs';

test('only the actual PAY source-authority read is a service consumer', () => {
  assert.equal(hasServiceConsumer({method:'GET',path:'/ops/customers/operating-entities/{id}'}),true);
  assert.equal(hasServiceConsumer({method:'POST',path:'/ops/customers/operating-entities/{id}'}),false);
  assert.equal(hasServiceConsumer({method:'GET',path:'/ops/customers/operating-entities/{id}/other'}),false);
  assert.equal(hasServiceConsumer({method:'GET',path:'/ops/customers/users'}),false);
});

test('only the fixed PAY OPS recognition POST is a document consumer',()=>{
 assert.equal(hasServiceConsumer({method:'POST',path:'/ops/onboarding-documents/recognize'}),true);
 assert.equal(hasServiceConsumer({method:'GET',path:'/ops/onboarding-documents/recognize'}),false);
 assert.equal(hasServiceConsumer({method:'POST',path:'/ops/onboarding-documents/recognize/other'}),false);
});

test('recognizes an actual host logout call with the existing API base template',()=>{assert.equal(calledPaths().has('/ops/auth/logout'),true);});
