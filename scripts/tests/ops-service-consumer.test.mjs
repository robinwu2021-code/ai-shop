import test from 'node:test';
import assert from 'node:assert/strict';
import {hasServiceConsumer} from '../check-ops-orphan.mjs';

test('only the actual PAY source-authority read is a service consumer', () => {
  assert.equal(hasServiceConsumer({method:'GET',path:'/ops/customers/operating-entities/{id}'}),true);
  assert.equal(hasServiceConsumer({method:'POST',path:'/ops/customers/operating-entities/{id}'}),false);
  assert.equal(hasServiceConsumer({method:'GET',path:'/ops/customers/operating-entities/{id}/other'}),false);
  assert.equal(hasServiceConsumer({method:'GET',path:'/ops/customers/users'}),false);
});
