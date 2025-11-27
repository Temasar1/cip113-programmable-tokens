package org.cardanofoundation.cip113.model;

public record MintTokenRequest(String issuerBaseAddress,
                               String substandardName,
                               String substandardIssueContractName,
                               String assetName,
                               String quantity,
                               String recipientAddress) {

}
