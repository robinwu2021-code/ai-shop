import {ApiError} from '@shared/net/http-client';
/** Only definitive validation/authority/conflict replies. Transport and server failures remain uncertain. */
export function definiteGovernanceRejection(error:unknown):boolean {
 return error instanceof ApiError && [10400,10403,10404,10409].includes(error.code);
}
