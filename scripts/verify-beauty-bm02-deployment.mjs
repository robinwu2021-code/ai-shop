import {randomUUID} from 'node:crypto';
// Extend the established isolated-artifact harness; keep authentication, migration and impact probes identical.
process.env.BEAUTY_VERIFY_MODULE='BM02';
process.env.BEAUTY_BM01_EVIDENCE_DIR=process.env.BEAUTY_BM02_EVIDENCE_DIR ?? '/tmp/beauty-bm02-deployment-'+randomUUID();
await import('./verify-beauty-bm01-deployment.mjs');
