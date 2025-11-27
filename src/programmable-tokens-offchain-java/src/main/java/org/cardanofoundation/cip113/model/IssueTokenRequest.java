package org.cardanofoundation.cip113.model;

public record IssueTokenRequest(String issuerBaseAddress,
                                String substandardName,
                                String substandardIssueContractName,
                                String substandardTransferContractName,
                                String substandardThirdPartyContractName,
                                String recipientAddress) {

}
