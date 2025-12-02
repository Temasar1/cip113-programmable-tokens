/**
 * API Types for CIP-113 Backend Integration
 */

// ============================================================================
// Substandards
// ============================================================================

export interface SubstandardValidator {
  title: string;
  script_bytes: string;
  script_hash: string;
}

export interface Substandard {
  id: string;
  validators: SubstandardValidator[];
}

export type SubstandardsResponse = Substandard[];

// ============================================================================
// Token Registration
// ============================================================================

export interface RegisterTokenRequest {
  registrarAddress: string;                    // User's wallet address
  substandardName: string;                     // e.g., "dummy"
  substandardIssueContractName: string;        // Required - validator title
  substandardTransferContractName: string;     // Required - validator title
  substandardThirdPartyContractName: string;   // Third-party validator (can be empty string)
  assetName: string;                           // HEX ENCODED token name
  quantity: string;                            // Amount to register/mint
  recipientAddress: string;                    // Recipient address (can be empty string)
}

export interface RegisterTokenResponse {
  policyId: string;              // Generated policy ID
  unsignedCborTx: string;        // Unsigned transaction CBOR hex
}

// ============================================================================
// Minting
// ============================================================================

export interface MintTokenRequest {
  issuerBaseAddress: string;
  substandardName: string;
  substandardIssueContractName: string;
  recipientAddress?: string;
  assetName: string;      // HEX ENCODED token name
  quantity: string;       // Amount as string to handle large numbers
}

// Backend returns plain text CBOR hex string (not JSON)
export type MintTokenResponse = string;

export interface MintFormData {
  tokenName: string;           // Human-readable name (will be hex encoded)
  quantity: string;            // Amount to mint
  substandardId: string;       // Substandard ID (e.g., "dummy")
  validatorTitle: string;      // Validator contract name
  recipientAddress?: string;   // Optional recipient (defaults to issuer)
}

// ============================================================================
// Protocol Blueprint
// ============================================================================

export interface ProtocolBlueprint {
  validators: Array<{
    title: string;
    redeemer: unknown;
    datum: unknown;
    compiledCode: string;
    hash: string;
  }>;
  preamble: {
    title: string;
    description: string;
    version: string;
  };
}

// ============================================================================
// API Error
// ============================================================================

export interface ApiError {
  message: string;
  status?: number;
  details?: unknown;
}

export class ApiException extends Error {
  constructor(
    message: string,
    public status?: number,
    public details?: unknown
  ) {
    super(message);
    this.name = 'ApiException';
  }
}
