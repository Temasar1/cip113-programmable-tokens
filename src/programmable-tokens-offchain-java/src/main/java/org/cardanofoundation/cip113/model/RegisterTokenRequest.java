package org.cardanofoundation.cip113.model;

public record RegisterTokenRequest(String registrarAddress,
                                   String substandardName,
                                   String substandardIssueContractName,
                                   String substandardTransferContractName,
                                   String substandardThirdPartyContractName,
                                   String assetName,
                                   String quantity,
                                   String recipientAddress) {

}
