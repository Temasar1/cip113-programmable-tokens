"use client";

import { useState } from 'react';
import dynamic from 'next/dynamic';
import { PageContainer } from '@/components/layout/page-container';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';

// Dynamically import wallet-dependent components to prevent SSR
const TransferForm = dynamic(
  () => import('@/components/transfer/transfer-form').then(mod => ({ default: mod.TransferForm })),
  { ssr: false }
);

const TransferPreview = dynamic(
  () => import('@/components/transfer/transfer-preview').then(mod => ({ default: mod.TransferPreview })),
  { ssr: false }
);

const TransferSuccess = dynamic(
  () => import('@/components/transfer/transfer-success').then(mod => ({ default: mod.TransferSuccess })),
  { ssr: false }
);

type TransferStep = 'form' | 'preview' | 'success';

interface TransactionData {
  unsignedCborTx: string;
  unit: string;
  quantity: string;
  recipientAddress: string;
}

export default function TransferPage() {
  const [currentStep, setCurrentStep] = useState<TransferStep>('form');
  const [transactionData, setTransactionData] = useState<TransactionData | null>(null);
  const [txHash, setTxHash] = useState<string>('');

  const handleTransactionBuilt = (
    unsignedCborTx: string,
    unit: string,
    quantity: string,
    recipientAddress: string
  ) => {
    setTransactionData({
      unsignedCborTx,
      unit,
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
            Transfer Programmable Tokens
          </h1>
          <p className="text-dark-300">
            Transfer CIP-113 programmable tokens with on-chain validation
          </p>
        </div>

        {/* Form Step */}
        {currentStep === 'form' && (
          <Card>
            <CardHeader>
              <CardTitle>Transfer Details</CardTitle>
              <CardDescription>
                Enter the token details and recipient address
              </CardDescription>
            </CardHeader>
            <CardContent>
              <TransferForm onTransactionBuilt={handleTransactionBuilt} />
            </CardContent>
          </Card>
        )}

        {/* Preview Step */}
        {currentStep === 'preview' && transactionData && (
          <TransferPreview
            unsignedCborTx={transactionData.unsignedCborTx}
            unit={transactionData.unit}
            quantity={transactionData.quantity}
            recipientAddress={transactionData.recipientAddress}
            onSuccess={handleTransactionSuccess}
            onCancel={handleCancelPreview}
          />
        )}

        {/* Success Step */}
        {currentStep === 'success' && transactionData && (
          <TransferSuccess
            txHash={txHash}
            unit={transactionData.unit}
            quantity={transactionData.quantity}
            recipientAddress={transactionData.recipientAddress}
          />
        )}
      </div>
    </PageContainer>
  );
}
