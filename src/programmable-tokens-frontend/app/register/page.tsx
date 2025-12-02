"use client";

import { useState } from 'react';
import dynamic from 'next/dynamic';
import { PageContainer } from '@/components/layout/page-container';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import { useSubstandards } from '@/hooks/use-substandards';

// Dynamically import wallet-dependent components to prevent SSR
const RegistrationForm = dynamic(
  () => import('@/components/register/registration-form').then(mod => ({ default: mod.RegistrationForm })),
  { ssr: false }
);

const RegistrationPreview = dynamic(
  () => import('@/components/register/registration-preview').then(mod => ({ default: mod.RegistrationPreview })),
  { ssr: false }
);

const RegistrationSuccess = dynamic(
  () => import('@/components/register/registration-success').then(mod => ({ default: mod.RegistrationSuccess })),
  { ssr: false }
);

type RegistrationStep = 'form' | 'preview' | 'success';

interface TransactionData {
  unsignedCborTx: string;
  policyId: string;
  substandardId: string;
  issueContractName: string;
  tokenName: string;
  quantity: string;
  recipientAddress?: string;
}

export default function RegisterPage() {
  const { substandards, isLoading, error } = useSubstandards();
  const [currentStep, setCurrentStep] = useState<RegistrationStep>('form');
  const [transactionData, setTransactionData] = useState<TransactionData | null>(null);
  const [txHash, setTxHash] = useState<string>('');

  const handleTransactionBuilt = (
    unsignedCborTx: string,
    policyId: string,
    substandardId: string,
    issueContractName: string,
    tokenName: string,
    quantity: string,
    recipientAddress?: string
  ) => {
    setTransactionData({
      unsignedCborTx,
      policyId,
      substandardId,
      issueContractName,
      tokenName,
      quantity,
      recipientAddress
    });
    setCurrentStep('preview');
  };

  const handleTransactionSuccess = (hash: string) => {
    setTxHash(hash);
    setCurrentStep('success');
  };

  const handleCancelPreview = () => {
    setTransactionData(null);
    setCurrentStep('form');
  };

  return (
    <PageContainer>
      <div className="max-w-2xl mx-auto">
        {/* Page Header */}
        <div className="mb-8">
          <h1 className="text-3xl font-bold text-white mb-2">
            Register Programmable Token
          </h1>
          <p className="text-dark-300">
            Register a new CIP-113 token policy with validation logic on-chain
          </p>
        </div>

        {/* Loading State */}
        {isLoading && (
          <Card>
            <CardContent className="py-12 text-center">
              <div className="flex justify-center mb-4">
                <div className="w-8 h-8 border-4 border-primary-500 border-t-transparent rounded-full animate-spin" />
              </div>
              <p className="text-dark-400">Loading substandards...</p>
            </CardContent>
          </Card>
        )}

        {/* Error State */}
        {error && (
          <Card>
            <CardHeader>
              <CardTitle>Error</CardTitle>
              <CardDescription>Failed to load substandards</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="p-4 bg-red-500/10 border border-red-500/20 rounded-lg">
                <p className="text-sm text-red-300">{error}</p>
              </div>
            </CardContent>
          </Card>
        )}

        {/* Form Step */}
        {!isLoading && !error && currentStep === 'form' && (
          <Card>
            <CardHeader>
              <CardTitle>Token Policy Details</CardTitle>
              <CardDescription>
                Select the validation logic and contracts for your token
              </CardDescription>
            </CardHeader>
            <CardContent>
              <RegistrationForm
                substandards={substandards}
                onTransactionBuilt={handleTransactionBuilt}
              />
            </CardContent>
          </Card>
        )}

        {/* Preview Step */}
        {currentStep === 'preview' && transactionData && (
          <RegistrationPreview
            unsignedCborTx={transactionData.unsignedCborTx}
            policyId={transactionData.policyId}
            tokenName={transactionData.tokenName}
            quantity={transactionData.quantity}
            recipientAddress={transactionData.recipientAddress}
            onSuccess={handleTransactionSuccess}
            onCancel={handleCancelPreview}
          />
        )}

        {/* Success Step */}
        {currentStep === 'success' && transactionData && (
          <RegistrationSuccess
            txHash={txHash}
            policyId={transactionData.policyId}
            substandardId={transactionData.substandardId}
            issueContractName={transactionData.issueContractName}
            tokenName={transactionData.tokenName}
            quantity={transactionData.quantity}
          />
        )}
      </div>
    </PageContainer>
  );
}
