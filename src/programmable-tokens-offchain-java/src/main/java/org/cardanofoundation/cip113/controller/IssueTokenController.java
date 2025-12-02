package org.cardanofoundation.cip113.controller;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.easy1staking.cardano.util.UtxoUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.model.*;
import org.cardanofoundation.cip113.model.onchain.RegistryNode;
import org.cardanofoundation.cip113.model.onchain.RegistryNodeParser;
import org.cardanofoundation.cip113.service.ProtocolBootstrapService;
import org.cardanofoundation.cip113.service.SubstandardService;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("${apiPrefix}/issue-token")
@RequiredArgsConstructor
@Slf4j
public class IssueTokenController {

    private final ObjectMapper objectMapper;

    private final AppConfig.Network network;

    private final UtxoRepository utxoRepository;

    private final RegistryNodeParser registryNodeParser;

    private final ProtocolBootstrapService protocolBootstrapService;

    private final SubstandardService substandardService;

    private final QuickTxBuilder quickTxBuilder;

    @PostMapping("/register")
    public ResponseEntity<?> register(
            @RequestBody RegisterTokenRequest registerTokenRequest,
            @RequestParam(required = false) String protocolTxHash) {

        log.info("registerTokenRequest: {}, protocolTxHash: {}", registerTokenRequest, protocolTxHash);

        try {

            var protocolBootstrapParams = protocolTxHash != null && !protocolTxHash.isEmpty()
                    ? protocolBootstrapService.getProtocolBootstrapParamsByTxHash(protocolTxHash)
                            .orElseThrow(() -> new IllegalArgumentException("Protocol version not found: " + protocolTxHash))
                    : protocolBootstrapService.getProtocolBootstrapParams();

            var protocolParamsScriptHash = protocolBootstrapParams.protocolParams().scriptHash();

            var bootstrapTxHash = protocolBootstrapParams.txHash();

            var protocolParamsUtxoOpt = utxoRepository.findById(UtxoId.builder()
                    .txHash(bootstrapTxHash)
                    .outputIndex(0)
                    .build());
            if (protocolParamsUtxoOpt.isEmpty()) {
                return ResponseEntity.internalServerError().body("could not resolve protocol params");
            }

            var protocolParamsUtxo = protocolParamsUtxoOpt.get();
            log.info("protocolParamsUtxo: {}", protocolParamsUtxo);
            // Registry Contracts Init
            // Directory MINT parameterization
            var utxo1 = protocolBootstrapParams.directoryMintParams().txInput();
            log.info("utxo1: {}", utxo1);
            var issuanceScriptHash = protocolBootstrapParams.directoryMintParams().issuanceScriptHash();
            log.info("issuanceScriptHash: {}", issuanceScriptHash);
            var directoryParameters = ListPlutusData.of(
                    ConstrPlutusData.of(0,
                            BytesPlutusData.of(HexUtil.decodeHexString(utxo1.txHash())),
                            BigIntPlutusData.of(utxo1.outputIndex())),
                    BytesPlutusData.of(HexUtil.decodeHexString(issuanceScriptHash))
            );

            var directoryMintContractOpt = protocolBootstrapService.getProtocolContract("registry_mint.registry_mint.mint");

            var directorySpendContractOpt = protocolBootstrapService.getProtocolContract("registry_spend.registry_spend.spend");

            if (directoryMintContractOpt.isEmpty() || directorySpendContractOpt.isEmpty()) {
                return ResponseEntity.internalServerError().body("could not resolve registry contracts");
            }

            var directoryMintContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(directoryParameters, directoryMintContractOpt.get()), PlutusVersion.v3);
            log.info("directoryMintContract: {}", HexUtil.encodeHexString(directoryMintContract.getScriptHash()));

            // Directory SPEND parameterization
            var directorySpendParameters = ListPlutusData.of(
                    BytesPlutusData.of(HexUtil.decodeHexString(protocolParamsScriptHash))
            );
            var directorySpendContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(directorySpendParameters, directorySpendContractOpt.get()), PlutusVersion.v3);
            log.info("directorySpendContract: {}", HexUtil.encodeHexString(directorySpendContract.getScriptHash()));

            var directorySpendContractAddress = AddressProvider.getEntAddress(directorySpendContract, network.getCardanoNetwork());
            log.info("directorySpendContractAddress: {}", directorySpendContractAddress.getAddress());


            var issuanceUtxoOpt = utxoRepository.findById(UtxoId.builder().txHash(bootstrapTxHash).outputIndex(2).build());
            if (issuanceUtxoOpt.isEmpty()) {
                return ResponseEntity.internalServerError().body("could not resolve issuance params");
            }
            var issuanceUtxo = issuanceUtxoOpt.get();
            log.info("issuanceUtxo: {}", issuanceUtxo);

            var issuanceDatum = issuanceUtxo.getInlineDatum();
            var issuanceData = PlutusData.deserialize(HexUtil.decodeHexString(issuanceDatum));
            var issuance = objectMapper.writeValueAsString(issuanceData);
            log.info("issuance: {}", issuance);

            var programmableLogicBaseScriptHash = protocolBootstrapParams.programmableLogicBaseParams().scriptHash();

            var rigistrarUtxosOpt = utxoRepository.findUnspentByOwnerAddr(registerTokenRequest.registrarAddress(), Pageable.unpaged());
            if (rigistrarUtxosOpt.isEmpty()) {
                return ResponseEntity.badRequest().body("issuer wallet is empty");
            }
            var registrarUtxos = rigistrarUtxosOpt.get().stream().map(UtxoUtil::toUtxo).toList();

            var substandardIssuanceContractOpt = substandardService.getSubstandardValidator(registerTokenRequest.substandardName(), registerTokenRequest.substandardIssueContractName());
            var substandardTransferContractOpt = substandardService.getSubstandardValidator(registerTokenRequest.substandardName(), registerTokenRequest.substandardTransferContractName());

            var thirdPartyScriptHash = Optional.ofNullable(registerTokenRequest.substandardName())
                    .flatMap(substandardName -> substandardService.getSubstandardValidator(registerTokenRequest.substandardName(), substandardName))
                    .map(SubstandardValidator::scriptHash)
                    .orElse("");

            if (substandardIssuanceContractOpt.isEmpty() || substandardTransferContractOpt.isEmpty()) {
                log.warn("substandard issuance or transfer contract are empty");
                return ResponseEntity.badRequest().body("substandard issuance or transfer contract are empty");
            }

            var substandardIssueContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(substandardIssuanceContractOpt.get().scriptBytes(), PlutusVersion.v3);
            log.info("substandardIssueContract: {}", substandardIssueContract.getPolicyId());

            var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract, network.getCardanoNetwork());
            log.info("substandardIssueAddress: {}", substandardIssueAddress.getAddress());

            var substandardTransferContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(substandardTransferContractOpt.get().scriptBytes(), PlutusVersion.v3);


            // Issuance Parameterization
            var issuanceParameters = ListPlutusData.of(
                    ConstrPlutusData.of(1,
                            BytesPlutusData.of(HexUtil.decodeHexString(programmableLogicBaseScriptHash))
                    ),
                    ConstrPlutusData.of(1,
                            BytesPlutusData.of(substandardIssueContract.getScriptHash())
                    )
            );

            var issuanceMintOpt = protocolBootstrapService.getProtocolContract("issuance_mint.issuance_mint.mint");
            if (issuanceMintOpt.isEmpty()) {
                return ResponseEntity.internalServerError().body("could not find issuance mint contract");
            }

            var issuanceContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(issuanceParameters, issuanceMintOpt.get()), PlutusVersion.v3);
            final var progTokenPolicyId = issuanceContract.getPolicyId();
            log.info("issuanceContract: {}", progTokenPolicyId);

            var registryEntries = utxoRepository.findUnspentByOwnerPaymentCredential(directorySpendContract.getPolicyId(), Pageable.unpaged());
            registryEntries.stream()
                    .flatMap(Collection::stream)
                    .forEach(addressUtxoEntity -> {
                        var registryDatum = registryNodeParser.parse(addressUtxoEntity.getInlineDatum());
                        log.info("registryDatum: {}", registryDatum);
                    });

            var registryEntryOpt = registryEntries.stream()
                    .flatMap(Collection::stream)
                    .filter(addressUtxoEntity -> registryNodeParser.parse(addressUtxoEntity.getInlineDatum())
                            .map(registryNode -> registryNode.key().equals(progTokenPolicyId))
                            .orElse(false)
                    )
                    .findAny();

            if (registryEntryOpt.isEmpty()) {

                var nodeToReplaceOpt = registryEntries.stream()
                        .flatMap(Collection::stream)
                        .filter(addressUtxoEntity -> {
                            var registryDatumOpt = registryNodeParser.parse(addressUtxoEntity.getInlineDatum());

                            if (registryDatumOpt.isEmpty()) {
                                log.warn("could not parse registry datum for: {}", addressUtxoEntity.getInlineDatum());
                                return false;
                            }

                            var registryDatum = registryDatumOpt.get();

                            var after = registryDatum.key().compareTo(progTokenPolicyId) < 0;
                            var before = progTokenPolicyId.compareTo(registryDatum.next()) < 0;
                            log.info("after:{}, before: {}", after, before);
                            return after && before;

                        })
                        .findAny();

                if (nodeToReplaceOpt.isEmpty()) {
                    return ResponseEntity.internalServerError().body("could not find node to replace");
                }

                var directoryUtxo = UtxoUtil.toUtxo(nodeToReplaceOpt.get());
                log.info("directoryUtxo: {}", directoryUtxo);
                var existingRegistryNodeDatumOpt = registryNodeParser.parse(directoryUtxo.getInlineDatum());

                if (existingRegistryNodeDatumOpt.isEmpty()) {
                    return ResponseEntity.internalServerError().body("could not parse current registry node");
                }

                var existingRegistryNodeDatum = existingRegistryNodeDatumOpt.get();

                // Directory MINT - NFT, address, datum and value
                var directoryMintRedeemer = ConstrPlutusData.of(1,
                        BytesPlutusData.of(issuanceContract.getScriptHash()),
                        BytesPlutusData.of(substandardIssueContract.getScriptHash())
                );

                var directoryMintNft = Asset.builder()
                        .name("0x" + issuanceContract.getPolicyId())
                        .value(BigInteger.ONE)
                        .build();

                var directorySpendNft = Asset.builder()
                        .name("0x")
                        .value(BigInteger.ONE)
                        .build();

                var directorySpendDatum = existingRegistryNodeDatum.toBuilder()
                        .next(HexUtil.encodeHexString(issuanceContract.getScriptHash()))
                        .build();
                log.info("directorySpendDatum: {}", directorySpendDatum);

                var directoryMintDatum = new RegistryNode(HexUtil.encodeHexString(issuanceContract.getScriptHash()),
                        existingRegistryNodeDatum.next(),
                        HexUtil.encodeHexString(substandardTransferContract.getScriptHash()),
                        thirdPartyScriptHash,
                        "");
                log.info("directoryMintDatum: {}", directoryMintDatum);

                Value directoryMintValue = Value.builder()
                        .coin(Amount.ada(1).getQuantity())
                        .multiAssets(List.of(
                                MultiAsset.builder()
                                        .policyId(directoryMintContract.getPolicyId())
                                        .assets(List.of(directoryMintNft))
                                        .build()
                        ))
                        .build();
                log.info("directoryMintValue: {}", directoryMintValue);

                Value directorySpendValue = Value.builder()
                        .coin(Amount.ada(1).getQuantity())
                        .multiAssets(List.of(
                                MultiAsset.builder()
                                        .policyId(directoryMintContract.getPolicyId())
                                        .assets(List.of(directorySpendNft))
                                        .build()
                        ))
                        .build();
                log.info("directorySpendValue: {}", directorySpendValue);


                var issuanceRedeemer = ConstrPlutusData.of(0, ConstrPlutusData.of(1, BytesPlutusData.of(substandardIssueContract.getScriptHash())));

                // Programmable Token Mint
                var programmableToken = Asset.builder()
                        .name("0x" + registerTokenRequest.assetName())
                        .value(new BigInteger(registerTokenRequest.quantity()))
                        .build();

                Value programmableTokenValue = Value.builder()
                        .coin(Amount.ada(1).getQuantity())
                        .multiAssets(List.of(
                                MultiAsset.builder()
                                        .policyId(issuanceContract.getPolicyId())
                                        .assets(List.of(programmableToken))
                                        .build()
                        ))
                        .build();

                var payee = registerTokenRequest.recipientAddress() == null || registerTokenRequest.recipientAddress().isBlank() ? registerTokenRequest.registrarAddress() : registerTokenRequest.recipientAddress();
                log.info("payee: {}", payee);

                var payeeAddress = new Address(payee);

                var targetAddress = AddressProvider.getBaseAddress(Credential.fromScript(protocolBootstrapParams.programmableLogicBaseParams().scriptHash()),
                        payeeAddress.getDelegationCredential().get(),
                        network.getCardanoNetwork());


                var tx = new ScriptTx()
                        .collectFrom(registrarUtxos)
                        .collectFrom(directoryUtxo, ConstrPlutusData.of(0))
                        .withdraw(substandardIssueAddress.getAddress(), BigInteger.ZERO, BigIntPlutusData.of(100))
                        // Mint Token
                        .mintAsset(issuanceContract, programmableToken, issuanceRedeemer)
                        // Redeemer is DirectoryInit (constr(0))
                        .mintAsset(directoryMintContract, directoryMintNft, directoryMintRedeemer)
                        .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(programmableTokenValue), ConstrPlutusData.of(0))
                        // Directory Params
                        .payToContract(directorySpendContractAddress.getAddress(), ValueUtil.toAmountList(directorySpendValue), directorySpendDatum.toPlutusData())
                        // Directory Params
                        .payToContract(directorySpendContractAddress.getAddress(), ValueUtil.toAmountList(directoryMintValue), directoryMintDatum.toPlutusData())
                        .readFrom(TransactionInput.builder()
                                        .transactionId(protocolParamsUtxo.getTxHash())
                                        .index(protocolParamsUtxo.getOutputIndex())
                                        .build(),
                                TransactionInput.builder()
                                        .transactionId(issuanceUtxo.getTxHash())
                                        .index(issuanceUtxo.getOutputIndex())
                                        .build())
                        .attachSpendingValidator(directorySpendContract)
                        .attachRewardValidator(substandardIssueContract)
                        .withChangeAddress(registerTokenRequest.registrarAddress());

                var transaction = quickTxBuilder.compose(tx)
//                    .withSigner(SignerProviders.signerFrom(adminAccount))
//                    .withTxEvaluator(new AikenTransactionEvaluator(bfBackendService))
                        .feePayer(registerTokenRequest.registrarAddress())
                        .mergeOutputs(false) //<-- this is important! or directory tokens will go to same address
                        .preBalanceTx((txBuilderContext, transaction1) -> {
                            var outputs = transaction1.getBody().getOutputs();
                            if (outputs.getFirst().getAddress().equals(registerTokenRequest.registrarAddress())) {
                                log.info("found dummy input, moving it...");
                                var first = outputs.removeFirst();
                                outputs.addLast(first);
                            }
                            try {
                                log.info("pre tx: {}", objectMapper.writeValueAsString(transaction1));
                            } catch (JsonProcessingException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .postBalanceTx((txBuilderContext, transaction1) -> {
                            try {
                                log.info("post tx: {}", objectMapper.writeValueAsString(transaction1));
                            } catch (JsonProcessingException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .build();

                log.info("tx: {}", transaction.serializeToHex());
                log.info("tx: {}", objectMapper.writeValueAsString(transaction));


                return ResponseEntity.ok(new RegisterTokenResponse(progTokenPolicyId, transaction.serializeToHex()));
            } else {

                return ResponseEntity.badRequest().body(String.format("Token policy %s already registered", progTokenPolicyId));
            }


        } catch (Exception e) {
            log.warn("error", e);
            return ResponseEntity.internalServerError().build();
        }
    }


    @PostMapping("/mint")
    public ResponseEntity<?> mint(
            @RequestBody MintTokenRequest mintTokenRequest,
            @RequestParam(required = false) String protocolTxHash) {

        try {

            var protocolBootstrapParams = protocolTxHash != null && !protocolTxHash.isEmpty()
                    ? protocolBootstrapService.getProtocolBootstrapParamsByTxHash(protocolTxHash)
                            .orElseThrow(() -> new IllegalArgumentException("Protocol version not found: " + protocolTxHash))
                    : protocolBootstrapService.getProtocolBootstrapParams();

            var bootstrapTxHash = protocolBootstrapParams.txHash();

            // TODO: add these utxo to the protocol bootstrap json
            var protocolParamsUtxoOpt = utxoRepository.findById(UtxoId.builder()
                    .txHash(bootstrapTxHash)
                    .outputIndex(0)
                    .build());
            if (protocolParamsUtxoOpt.isEmpty()) {
                return ResponseEntity.internalServerError().body("could not resolve protocol params");
            }

            var protocolParamsUtxo = protocolParamsUtxoOpt.get();
            log.info("protocolParamsUtxo: {}", protocolParamsUtxo);

            var issuanceUtxoOpt = utxoRepository.findById(UtxoId.builder().txHash(bootstrapTxHash).outputIndex(2).build());
            if (issuanceUtxoOpt.isEmpty()) {
                return ResponseEntity.internalServerError().body("could not resolve issuance params");
            }
            var issuanceUtxo = issuanceUtxoOpt.get();
            log.info("issuanceUtxo: {}", issuanceUtxo);

            var issuanceDatum = issuanceUtxo.getInlineDatum();
            var issuanceData = PlutusData.deserialize(HexUtil.decodeHexString(issuanceDatum));
            var issuance = objectMapper.writeValueAsString(issuanceData);
            log.info("issuance: {}", issuance);

            var programmableLogicBaseScriptHash = protocolBootstrapParams.programmableLogicBaseParams().scriptHash();

            var issuerUtxosOpt = utxoRepository.findUnspentByOwnerAddr(mintTokenRequest.issuerBaseAddress(), Pageable.unpaged());
            if (issuerUtxosOpt.isEmpty()) {
                return ResponseEntity.badRequest().body("issuer wallet is empty");
            }
            var issuerUtxos = issuerUtxosOpt.get().stream().map(UtxoUtil::toUtxo).toList();

            var substandardIssuanceContractOpt = substandardService.getSubstandardValidator(mintTokenRequest.substandardName(), mintTokenRequest.substandardIssueContractName());

            var substandardIssueContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(substandardIssuanceContractOpt.get().scriptBytes(), PlutusVersion.v3);
            log.info("substandardIssueContract: {}", substandardIssueContract.getPolicyId());

            var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract, network.getCardanoNetwork());
            log.info("substandardIssueAddress: {}", substandardIssueAddress.getAddress());

            // Issuance Parameterization
            var issuanceParameters = ListPlutusData.of(
                    ConstrPlutusData.of(1,
                            BytesPlutusData.of(HexUtil.decodeHexString(programmableLogicBaseScriptHash))
                    ),
                    ConstrPlutusData.of(1,
                            BytesPlutusData.of(substandardIssueContract.getScriptHash())
                    )
            );

            var issuanceMintOpt = protocolBootstrapService.getProtocolContract("issuance_mint.issuance_mint.mint");
            if (issuanceMintOpt.isEmpty()) {
                return ResponseEntity.internalServerError().body("could not find issuance mint contract");
            }

            var issuanceContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(issuanceParameters, issuanceMintOpt.get()), PlutusVersion.v3);
            log.info("issuanceContract: {}", issuanceContract.getPolicyId());

            var issuanceRedeemer = ConstrPlutusData.of(0, ConstrPlutusData.of(1, BytesPlutusData.of(substandardIssueContract.getScriptHash())));

            // Programmable Token Mint
            var programmableToken = Asset.builder()
                    .name("0x" + mintTokenRequest.assetName())
                    .value(new BigInteger(mintTokenRequest.quantity()))
                    .build();

            Value progammableTokenValue = Value.builder()
                    .coin(Amount.ada(1).getQuantity())
                    .multiAssets(List.of(
                            MultiAsset.builder()
                                    .policyId(issuanceContract.getPolicyId())
                                    .assets(List.of(programmableToken))
                                    .build()
                    ))
                    .build();

            var recipient = Optional.ofNullable(mintTokenRequest.recipientAddress())
                    .orElse(mintTokenRequest.issuerBaseAddress());

            var recipientAddress = new Address(recipient);

            var targetAddress = AddressProvider.getBaseAddress(Credential.fromScript(protocolBootstrapParams.programmableLogicBaseParams().scriptHash()),
                    recipientAddress.getDelegationCredential().get(),
                    network.getCardanoNetwork());

            var tx = new ScriptTx()
                    .collectFrom(issuerUtxos)
                    .withdraw(substandardIssueAddress.getAddress(), BigInteger.ZERO, BigIntPlutusData.of(100))
                    // Redeemer is DirectoryInit (constr(0))
                    .mintAsset(issuanceContract, programmableToken, issuanceRedeemer)
                    .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(progammableTokenValue), ConstrPlutusData.of(0))
                    .attachRewardValidator(substandardIssueContract)
                    .withChangeAddress(mintTokenRequest.issuerBaseAddress());

            var transaction = quickTxBuilder.compose(tx)
                    .feePayer(mintTokenRequest.issuerBaseAddress())
                    .mergeOutputs(false) //<-- this is important! or directory tokens will go to same address
                    .preBalanceTx((txBuilderContext, transaction1) -> {
                        var outputs = transaction1.getBody().getOutputs();
                        if (outputs.getFirst().getAddress().equals(mintTokenRequest.issuerBaseAddress())) {
                            log.info("found dummy input, moving it...");
                            var first = outputs.removeFirst();
                            outputs.addLast(first);
                        }
                        try {
                            log.info("pre tx: {}", objectMapper.writeValueAsString(transaction1));
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .postBalanceTx((txBuilderContext, transaction1) -> {
                        try {
                            log.info("post tx: {}", objectMapper.writeValueAsString(transaction1));
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .build();

            log.info("tx: {}", transaction.serializeToHex());
            log.info("tx: {}", objectMapper.writeValueAsString(transaction));

            return ResponseEntity.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.warn("error", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }


}
