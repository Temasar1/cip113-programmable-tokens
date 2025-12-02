package org.cardanofoundation.cip113.controller;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.easy1staking.cardano.model.AssetType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.model.TransferTokenRequest;
import org.cardanofoundation.cip113.model.onchain.RegistryNodeParser;
import org.cardanofoundation.cip113.service.ProtocolBootstrapService;
import org.cardanofoundation.cip113.service.SubstandardService;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

@RestController
@RequestMapping("${apiPrefix}/transfer-token")
@RequiredArgsConstructor
@Slf4j
public class TransferTokenController {

    private final ObjectMapper objectMapper;

    private final AppConfig.Network network;

    private final UtxoRepository utxoRepository;

    private final RegistryNodeParser registryNodeParser;

    private final ProtocolBootstrapService protocolBootstrapService;

    private final SubstandardService substandardService;

    private final QuickTxBuilder quickTxBuilder;

    @PostMapping("/transfer")
    public ResponseEntity<?> transfer(@RequestBody TransferTokenRequest transferTokenRequest) {
        log.info("transferTokenRequest: {}", transferTokenRequest);

        var assetType = AssetType.fromUnit(transferTokenRequest.unit());
        log.info("prog token to transfer: {}.{}", assetType.policyId(), assetType.unsafeHumanAssetName());

        try {

            var protocolBootstrapParams = protocolBootstrapService.getProtocolBootstrapParams();

            var protocolParamsScriptHash = protocolBootstrapParams.protocolParams().scriptHash();

            var bootstrapTxHash = protocolBootstrapParams.txHash();

            var progToken = AssetType.fromUnit(transferTokenRequest.unit());

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

            var registryEntries = utxoRepository.findUnspentByOwnerPaymentCredential(directorySpendContract.getPolicyId(), Pageable.unpaged());
            registryEntries.stream()
                    .flatMap(Collection::stream)
                    .forEach(foo -> {
                        var registryDatum = registryNodeParser.parse(foo.getInlineDatum());
                        log.info("registryDatum: {}", registryDatum);
                    });


//            var protocolParamsUtxoOpt = bfBackendService.getUtxoService().getTxOutput(bootstrapTxHash, 0);
//            if (!protocolParamsUtxoOpt.isSuccessful()) {
//                Assertions.fail("could not fetch protocol params utxo");
//            }
//            var protocolParamsUtxo = protocolParamsUtxoOpt.getValue();
//            log.info("protocolParamsUtxo: {}", protocolParamsUtxo);
//
//            var utxosOpt = bfBackendService.getUtxoService().getUtxos(aliceAccount.baseAddress(), 100, 1);
//            if (!utxosOpt.isSuccessful() || utxosOpt.getValue().isEmpty()) {
//                Assertions.fail("no utxos available");
//            }
//            var walletUtxos = utxosOpt.getValue();
//
//            var substandardIssueContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(SUBSTANDARD_ISSUE_CONTRACT, PlutusVersion.v3);
//            log.info("substandardIssueContract: {}", substandardIssueContract.getPolicyId());
//            var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract, network);
//            log.info("substandardIssueAddress: {}", substandardIssueAddress.getAddress());
//
//            var substandardTransferContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(SUBSTANDARD_TRANSFER_CONTRACT, PlutusVersion.v3);
//            log.info("substandardTransferContract: {}", substandardTransferContract.getPolicyId());
//            var substandardTransferAddress = AddressProvider.getRewardAddress(substandardTransferContract, network);
//            log.info("substandardTransferAddress: {}", substandardTransferAddress.getAddress());
//
//
//            // Programmable Logic Global parameterization
//            var programmableLogicGlobalParameters = ListPlutusData.of(BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.protocolParams().scriptHash())));
//            var programmableLogicGlobalContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(programmableLogicGlobalParameters, PROGRAMMABLE_LOGIC_GLOBAL_CONTRACT), PlutusVersion.v3);
//            log.info("programmableLogicGlobalContract policy: {}", programmableLogicGlobalContract.getPolicyId());
//            var programmableLogicGlobalAddress = AddressProvider.getRewardAddress(programmableLogicGlobalContract, network);
//            log.info("programmableLogicGlobalAddress policy: {}", programmableLogicGlobalAddress.getAddress());
////
////        var registerAddressTx = new Tx()
////                .from(adminAccount.baseAddress())
////                .registerStakeAddress(programmableLogicGlobalAddress.getAddress())
////                .withChangeAddress(adminAccount.baseAddress());
////
////        quickTxBuilder.compose(registerAddressTx)
////                .feePayer(adminAccount.baseAddress())
////                .withSigner(SignerProviders.signerFrom(adminAccount))
////                .completeAndWait();
//
//            // Programmable Logic Base parameterization
//            var programmableLogicBaseParameters = ListPlutusData.of(ConstrPlutusData.of(1, BytesPlutusData.of(programmableLogicGlobalContract.getScriptHash())));
//            var programmableLogicBaseContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(programmableLogicBaseParameters, PROGRAMMABLE_LOGIC_BASE_CONTRACT), PlutusVersion.v3);
//            log.info("programmableLogicBaseContract policy: {}", programmableLogicBaseContract.getPolicyId());
//
//
//            // Directory SPEND parameterization
//            var directorySpendParameters = ListPlutusData.of(
//                    BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.protocolParams().scriptHash()))
//            );
//            var directorySpendContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(directorySpendParameters, DIRECTORY_SPEND_CONTRACT), PlutusVersion.v3);
//            var directorySpendContractAddress = AddressProvider.getEntAddress(Credential.fromScript(directorySpendContract.getScriptHash()), network);
//
//
//            var directoryUtxosOpt = bfBackendService.getUtxoService().getUtxos(directorySpendContractAddress.getAddress(), 100, 1);
//            if (!directoryUtxosOpt.isSuccessful()) {
//                Assertions.fail("no directories");
//            }
//            var directoryUtxos = directoryUtxosOpt.getValue();
//            directoryUtxos.forEach(utxo -> log.info("directory utxo: {}", utxo));
//
//            var directoryUtxoOpt = directoryUtxos.stream().filter(utxo -> utxo.getAmount().stream().anyMatch(amount -> directoryNftUnit.equals(amount.getUnit()))).findAny();
//            if (directoryUtxoOpt.isEmpty()) {
//                Assertions.fail("no directory utxo for unit: " + directoryNftUnit);
//            }
//            var directoryUtxo = directoryUtxoOpt.get();
//            log.info("directoryUtxo: {}", directoryUtxo);
//
//
//            var aliceAddress = AddressProvider.getBaseAddress(Credential.fromScript(protocolBootstrapParams.programmableLogicBaseParams().scriptHash()),
//                    aliceAccount.getBaseAddress().getDelegationCredential().get(),
//                    network);
//            log.info("aliceAddress: {}", aliceAddress);
//            var progBaseAddressUtxosOpt = bfBackendService.getUtxoService().getUtxos(aliceAddress.getAddress(), 100, 1);
//            if (!progBaseAddressUtxosOpt.isSuccessful() || progBaseAddressUtxosOpt.getValue().isEmpty()) {
//                Assertions.fail("not progBaseAddresses");
//            }
//
//            var bobAddress = AddressProvider.getBaseAddress(Credential.fromScript(protocolBootstrapParams.programmableLogicBaseParams().scriptHash()),
//                    bobAccount.getBaseAddress().getDelegationCredential().get(),
//                    network);
//            log.info("bobAddress: {}", bobAddress);
//
//            var progBaseAddressUtxos = progBaseAddressUtxosOpt.getValue();
//            progBaseAddressUtxos.forEach(utxo -> log.info("prog tokens utxo: {}", utxo));
//            var progTokenUtxoOpt = progBaseAddressUtxos.stream().filter(utxo -> utxo.getAmount().stream().anyMatch(amount -> progToken.toUnit().equals(amount.getUnit()))).findAny();
//            if (progTokenUtxoOpt.isEmpty()) {
//                Assertions.fail("no prog token utxo for unit: " + progToken);
//            }
//            var progTokenUtxo = progTokenUtxoOpt.get();
//            log.info("progTokenUtxo: {}", progTokenUtxo);
//
//            var initialValue = progTokenUtxo.toValue();
//            var tokenAmount = initialValue.amountOf(progToken.policyId(), "0x" + progToken.assetName());
//            var amount1 = tokenAmount.divide(BigInteger.TWO);
//            var amount2 = tokenAmount.subtract(amount1);
//
//            // Programmable Token Mint
//            var tokenAsset1 = Asset.builder()
//                    .name(HexUtil.encodeHexString("PINT".getBytes(), true))
//                    .value(amount1)
//                    .build();
//
//            var tokenAsset2 = Asset.builder()
//                    .name(HexUtil.encodeHexString("PINT".getBytes(), true))
//                    .value(amount1)
//                    .build();
//
//            Value tokenValue1 = Value.builder()
//                    .coin(Amount.ada(1).getQuantity())
//                    .multiAssets(List.of(
//                            MultiAsset.builder()
//                                    .policyId(progToken.policyId())
//                                    .assets(List.of(tokenAsset1))
//                                    .build()
//                    ))
//                    .build();
//
//            Value tokenValue2 = Value.builder()
//                    .coin(Amount.ada(1).getQuantity())
//                    .multiAssets(List.of(
//                            MultiAsset.builder()
//                                    .policyId(progToken.policyId())
//                                    .assets(List.of(tokenAsset2))
//                                    .build()
//                    ))
//                    .build();
//
//
////        /// Redeemer for the global programmable logic stake validator
////pub type ProgrammableLogicGlobalRedeemer {
////  /// Transfer action with proofs for each token type
////  TransferAct { proofs: List<TokenProof> }
////  /// Seize action to confiscate tokens from blacklisted address
////  SeizeAct {
////    seize_input_idx: Int,
////    seize_output_idx: Int,
////    directory_node_idx: Int,
////  }
////}
//
//            var programmableGlobalRedeemer = ConstrPlutusData.of(0,
//                    // only one prop and it's a list
//                    ListPlutusData.of(ConstrPlutusData.of(0, BigIntPlutusData.of(1)))
//            );
//
//            log.info("protocolBootstrapParams.programmableGlobalRefInput(): {}", protocolBootstrapParams.programmableGlobalRefInput());
//
//            var tx = new ScriptTx()
//                    .collectFrom(walletUtxos)
//                    .collectFrom(progTokenUtxo, ConstrPlutusData.of(0))
//                    // must be first Provide proofs
//                    .withdraw(substandardTransferAddress.getAddress(), BigInteger.ZERO, BigIntPlutusData.of(200))
//                    .withdraw(programmableLogicGlobalAddress.getAddress(), BigInteger.ZERO, programmableGlobalRedeemer)
//                    .payToContract(aliceAddress.getAddress(), ValueUtil.toAmountList(tokenValue1), ConstrPlutusData.of(0))
//                    .payToContract(bobAddress.getAddress(), ValueUtil.toAmountList(tokenValue2), ConstrPlutusData.of(0))
//                    .payToAddress(aliceAccount.baseAddress(), Amount.ada(5))
//                    .payToAddress(aliceAccount.baseAddress(), Amount.ada(5))
//                    .readFrom(TransactionInput.builder()
//                            .transactionId(protocolParamsUtxo.getTxHash())
//                            .index(protocolParamsUtxo.getOutputIndex())
//                            .build(), TransactionInput.builder()
//                            .transactionId(directoryUtxo.getTxHash())
//                            .index(directoryUtxo.getOutputIndex())
//                            .build())
//                    .attachRewardValidator(programmableLogicGlobalContract) // global
//                    .attachRewardValidator(substandardTransferContract)
//                    .attachSpendingValidator(programmableLogicBaseContract) // base
//                    .withChangeAddress(aliceAccount.baseAddress());
//
//            var transaction = quickTxBuilder.compose(tx)
//                    .withSigner(SignerProviders.signerFrom(aliceAccount))
//                    .withSigner(SignerProviders.stakeKeySignerFrom(aliceAccount))
//                    .withTxEvaluator(new AikenTransactionEvaluator(bfBackendService))
//                    .withRequiredSigners(aliceAccount.getBaseAddress().getDelegationCredentialHash().get())
//                    .feePayer(aliceAccount.baseAddress())
//                    .mergeOutputs(false)
//                    .build();
//
//
//            log.info("tx: {}", transaction.serializeToHex());
//            log.info("tx: {}", objectMapper.writeValueAsString(transaction));
//
//            return ResponseEntity.ok(transaction.serializeToHex());

            return ResponseEntity.ok().build();

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }


}
