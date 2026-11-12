// P3/P4 examples using Node.js built-in crypto. Public test material only.
import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';

function keyBytes(encryptKey) {
  const key = Buffer.from(encryptKey, 'utf8');
  if (key.length !== 32) throw new Error('EncryptKey must have exactly 32 UTF-8 bytes');
  return key;
}

export function encryptField(encryptKey, appId, fieldPath, plaintext) {
  const iv = randomBytes(12); // New IV for every field, request and retry.
  const cipher = createCipheriv('aes-256-gcm', keyBytes(encryptKey), iv);
  cipher.setAAD(Buffer.from(`${appId}|${fieldPath}`, 'utf8'));
  const ciphertext = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
  return Buffer.concat([iv, ciphertext, cipher.getAuthTag()]).toString('base64');
}

export function decryptResource(encryptKey, event) {
  const r = event.resource;
  const expectedAad = `${event.eventType}|${event.eventId}`;
  if (!r || r.algorithm !== 'AEAD_AES_256_GCM' || r.associatedData !== expectedAad) {
    throw new Error('invalid resource algorithm or AAD');
  }
  const iv = Buffer.from(r.nonce, 'base64');
  const packed = Buffer.from(r.ciphertext, 'base64');
  if (iv.length !== 12 || packed.length < 16) throw new Error('invalid nonce or ciphertext');
  const decipher = createDecipheriv('aes-256-gcm', keyBytes(encryptKey), iv);
  decipher.setAAD(Buffer.from(expectedAad, 'utf8'));
  decipher.setAuthTag(packed.subarray(-16));
  return Buffer.concat([decipher.update(packed.subarray(0, -16)), decipher.final()]).toString('utf8');
}

function selfTest() {
  const v = JSON.parse(readFileSync(new URL('./test-vectors.json', import.meta.url), 'utf8'));
  const p = v.p3;
  const iv = Buffer.from(p.ivHex, 'hex');
  const cipher = createCipheriv('aes-256-gcm', keyBytes(v.encryptKey), iv);
  cipher.setAAD(Buffer.from(p.aad));
  const ct = Buffer.concat([cipher.update(p.plaintext, 'utf8'), cipher.final()]);
  assert.equal(Buffer.concat([iv, ct, cipher.getAuthTag()]).toString('base64'), p.packedBase64);
  const event = v.p4;
  assert.equal(decryptResource(v.encryptKey, event), event.plaintext);
  assert.throws(() => decryptResource(v.encryptKey, { ...event, eventId: 'tampered' }));
  const damaged = Buffer.from(event.resource.ciphertext, 'base64');
  damaged[0] ^= 1;
  assert.throws(() => decryptResource(v.encryptKey, { ...event, resource: { ...event.resource, ciphertext: damaged.toString('base64') } }));
  const one = encryptField(v.encryptKey, v.appId, 'payer.phone', '13800138000');
  const two = encryptField(v.encryptKey, v.appId, 'payer.phone', '13800138000');
  assert.notEqual(one, two);
  for (const packed of [one, two]) {
    const bytes = Buffer.from(packed, 'base64');
    const decipher = createDecipheriv('aes-256-gcm', keyBytes(v.encryptKey), bytes.subarray(0, 12));
    decipher.setAAD(Buffer.from(`${v.appId}|payer.phone`));
    decipher.setAuthTag(bytes.subarray(-16));
    assert.equal(Buffer.concat([decipher.update(bytes.subarray(12, -16)), decipher.final()]).toString(), '13800138000');
  }
  console.log('PASS: P3/P4 vectors, random IV round trips, altered AAD and ciphertext rejection');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) selfTest();
