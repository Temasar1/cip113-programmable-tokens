/**
 * Token Registration API
 */

import { RegisterTokenRequest, RegisterTokenResponse } from '@/types/api';
import { apiPost } from './client';

/**
 * Register a new programmable token policy
 * Returns policy ID and unsigned transaction CBOR hex
 */
export async function registerToken(
  request: RegisterTokenRequest
): Promise<RegisterTokenResponse> {
  return apiPost<RegisterTokenRequest, RegisterTokenResponse>(
    '/issue-token/register',
    request,
    { timeout: 60000 } // 60 seconds for registration transaction
  );
}
