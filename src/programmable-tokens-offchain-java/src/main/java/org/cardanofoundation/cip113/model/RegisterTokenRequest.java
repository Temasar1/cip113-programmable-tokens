package org.cardanofoundation.cip113.model;

public record RegisterTokenRequest(String issuerBaseAddress,
                                   String substandardName,
                                   String substandardIssueContractName,
                                   String substandardTransferContractName,
                                   String substandardThirdPartyContractName) {

}
