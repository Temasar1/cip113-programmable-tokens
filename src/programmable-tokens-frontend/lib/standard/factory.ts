/**
 * Factory for substandard handlers
 * Supports register, transfer, and mint transaction building
 * Currently supports "dummy" substandard
 */

import type { IWallet } from '@meshsdk/core';
import type { ProtocolBootstrapParams, ProtocolBlueprint, SubstandardBlueprint } from '@/types/protocol';
import { buildRegistrationTransaction } from '../transactions/register';
import { transfer_programmable_token } from '../transactions/transfer';
import { mint_programmable_tokens } from '../transactions/mint';

export type SubstandardId = 'dummy' | 'bafin';

// ============================================================================
// Transaction Parameter Types
// ============================================================================

/**
 * Parameters for minting programmable tokens
 */
export interface MintTransactionParams {
  assetName: string;                    // HEX ENCODED token name
  quantity: string;                     // Amount to mint
  issuerBaseAddress: string;            // Issuer's wallet address
  recipientAddress?: string;            // Optional recipient (defaults to issuer)
  substandardName: string;              // Substandard ID (e.g., "dummy")
  substandardIssueContractName: string; // Validator contract name
}

/**
 * Parameters for registering a new programmable token
 */
export interface RegisterTransactionParams {
  assetName: string;                           // HEX ENCODED token name
  quantity: string;                            // Amount to register/mint initially
  registrarAddress: string;                    // Registrar's wallet address
  recipientAddress?: string;                   // Optional recipient address
  substandardName: string;                     // Substandard ID (e.g., "dummy")
  substandardIssueContractName: string;        // Issue validator contract name
  substandardTransferContractName: string;     // Transfer validator contract name
  substandardThirdPartyContractName?: string;  // Optional third-party validator
  networkId?: 0 | 1;                          // Network ID (0=mainnet, 1=testnet, defaults to 1)
}

/**
 * Parameters for transferring programmable tokens
 */
export interface TransferTransactionParams {
  unit: string;              // Full unit (policyId + assetName hex)
  quantity: string;          // Amount to transfer
  senderAddress: string;     // Sender's wallet address
  recipientAddress: string;  // Recipient's address
  networkId?: 0 | 1;         // Network ID (0=mainnet, 1=testnet, defaults to 1)
}

// ============================================================================
// Handler Interface
// ============================================================================

/**
 * Substandard handler interface supporting all transaction types
 */
export interface SubstandardHandler {
  /**
   * Build a mint transaction for programmable tokens
   */
  buildMintTransaction(
    params: MintTransactionParams,
    protocolParams: ProtocolBootstrapParams,
    protocolBlueprint: ProtocolBlueprint,
    substandardBlueprint: SubstandardBlueprint,
    wallet: IWallet
  ): Promise<string>;

  /**
   * Build a registration transaction for a new programmable token
   */
  buildRegisterTransaction(
    params: RegisterTransactionParams,
    protocolParams: ProtocolBootstrapParams,
    protocolBlueprint: ProtocolBlueprint,
    substandardBlueprint: SubstandardBlueprint,
    wallet: IWallet
  ): Promise<{unsignedTx: string, policy_Id: string}>;

  /**
   * Build a transfer transaction for programmable tokens
   */
  buildTransferTransaction(
    params: TransferTransactionParams,
    protocolParams: ProtocolBootstrapParams,
    protocolBlueprint: ProtocolBlueprint,
    substandardBlueprint: SubstandardBlueprint,
    wallet: IWallet
  ): Promise<string>;
}

// ============================================================================
// Dummy Substandard Handler Implementation
// ============================================================================

/**
 * Dummy substandard handler implementing all transaction types
 */
const dummyHandler: SubstandardHandler = {
  /**
   * Build mint transaction using the transaction builder
   */
  async buildMintTransaction(
    params: MintTransactionParams,
    protocolParams: ProtocolBootstrapParams,
    _protocolBlueprint: ProtocolBlueprint,
    _substandardBlueprint: SubstandardBlueprint,
    wallet: IWallet
  ): Promise<string> {
    // Use the existing mint transaction builder
    // Note: This uses the old function signature, adapting parameters
    const networkId = 0; // Preview/testnet (adjust based on your needs)
    
    return mint_programmable_tokens(
      protocolParams,
      params.assetName,
      params.quantity,
      networkId,
      wallet,
      params.recipientAddress || null
    );
  },

  /**
   * Build registration transaction
   */
  async buildRegisterTransaction(
    params: RegisterTransactionParams,
    protocolParams: ProtocolBootstrapParams,
    _protocolBlueprint: ProtocolBlueprint,
    _substandardBlueprint: SubstandardBlueprint,
    wallet: IWallet
  ): Promise<{unsignedTx: string, policy_Id: string}> {
    // Determine substandard type for registration
    // "issuance" for issue contracts, "transfer" for transfer contracts
    const subStandardName = params.substandardIssueContractName.includes('issue') 
      ? 'issuance' as const 
      : 'transfer' as const;
    
    const networkId = params.networkId ?? 1; // Default to testnet
    
    // Note: buildRegistrationTransaction signature is:
    // (assetName, quantity, params, subStandardName, Network_id, wallet, recipientAddress)
    const {unsignedTx, policy_Id} = await buildRegistrationTransaction(
      params.assetName,
      params.quantity,
      protocolParams,
      subStandardName,
      networkId,
      wallet,
      params.recipientAddress || null
    );
    return {unsignedTx, policy_Id};
  },

  /**
   * Build transfer transaction
   */
  async buildTransferTransaction(
    params: TransferTransactionParams,
    protocolParams: ProtocolBootstrapParams,
    _protocolBlueprint: ProtocolBlueprint,
    _substandardBlueprint: SubstandardBlueprint,
    wallet: IWallet
  ): Promise<string> {
    const networkId = params.networkId ?? 0; // Default to testnet
    
    // Note: transfer_programmable_token signature is:
    // (unit, quantity, recipientAddress, params, Network_id, wallet)
    return transfer_programmable_token(
      params.unit,
      params.quantity,
      params.recipientAddress,
      protocolParams,
      networkId,
      wallet
    );
  },
};

// ============================================================================
// Bafin Substandard Handler (Stub)
// ============================================================================

/**
 * Bafin substandard handler (stub - not yet implemented)
 */
const bafinHandler: SubstandardHandler = {
  async buildMintTransaction() {
    throw new Error('Bafin substandard not yet implemented for client-side transaction building');
  },
  
  async buildRegisterTransaction() {
    throw new Error('Bafin substandard not yet implemented for client-side transaction building');
  },
  
  async buildTransferTransaction() {
    throw new Error('Bafin substandard not yet implemented for client-side transaction building');
  },
};

// ============================================================================
// Handler Registry
// ============================================================================

/**
 * Registry of substandard handlers
 */
const handlers: Record<string, SubstandardHandler> = {
  dummy: dummyHandler,
  bafin: bafinHandler,
};

// ============================================================================
// Public API
// ============================================================================

/**
 * Get a substandard handler by ID
 *
 * @param substandardId - The substandard identifier
 * @returns The handler for the substandard
 * @throws Error if substandard not found
 */
export function getSubstandardHandler(substandardId: SubstandardId): SubstandardHandler {
  const handler = handlers[substandardId.toLowerCase()];

  if (!handler) {
    throw new Error(`Substandard not found: ${substandardId}`);
  }

  return handler;
}

/**
 * Check if a substandard is supported for client-side transaction building
 *
 * @param substandardId - The substandard identifier
 * @returns true if supported, false otherwise
 */
export function isSubstandardSupported(substandardId: string): boolean {
  return substandardId.toLowerCase() in handlers;
}

/**
 * Get all supported substandard IDs
 *
 * @returns Array of supported substandard IDs
 */
export function getSupportedSubstandards(): SubstandardId[] {
  return Object.keys(handlers) as SubstandardId[];
}
