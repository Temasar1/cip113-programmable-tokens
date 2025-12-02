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
  request: RegisterTokenRequest,
  protocolTxHash?: string
): Promise<RegisterTokenResponse> {
  const endpoint = protocolTxHash
    ? `/issue-token/register?protocolTxHash=${protocolTxHash}`
    : '/issue-token/register';

  return apiPost<RegisterTokenRequest, RegisterTokenResponse>(
    endpoint,
    request,
    { timeout: 60000 } // 60 seconds for registration transaction
  );
}
