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
import org.cardanofoundation.cip113.model.DirectorySetNode;
import org.cardanofoundation.cip113.model.IssueTokenRequest;
import org.cardanofoundation.cip113.model.MintTokenRequest;
import org.cardanofoundation.cip113.model.RegisterTokenRequest;
import org.cardanofoundation.cip113.service.ProtocolBootstrapService;
import org.cardanofoundation.cip113.service.SubstandardService;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
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

    private final ProtocolBootstrapService protocolBootstrapService;

    private final SubstandardService substandardService;

    private final QuickTxBuilder quickTxBuilder;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterTokenRequest registerTokenRequest) {

        try {

            var protocolBootstrapParams = protocolBootstrapService.getProtocolBootstrapParams();

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

            var issuerUtxosOpt = utxoRepository.findUnspentByOwnerAddr(registerTokenRequest.issuerBaseAddress(), Pageable.unpaged());
            if (issuerUtxosOpt.isEmpty()) {
                return ResponseEntity.badRequest().body("issuer wallet is empty");
            }
            var issuerUtxos = issuerUtxosOpt.get().stream().map(UtxoUtil::toUtxo).toList();

            var directoryUtxoOpt = utxoRepository.findById(UtxoId.builder().txHash(bootstrapTxHash).outputIndex(1).build());
            if (directoryUtxoOpt.isEmpty()) {
                return ResponseEntity.internalServerError().body("could not resolve directories");
            }

            var directoryUtxo = UtxoUtil.toUtxo(directoryUtxoOpt.get());
            log.info("directoryUtxo: {}", directoryUtxo);

            var directorySetNode = DirectorySetNode.fromInlineDatum(directoryUtxo.getInlineDatum());
            if (directorySetNode.isEmpty()) {
                log.error("could not deserialise directorySetNode for utxo: {}", directoryUtxo);
                return ResponseEntity.internalServerError().body("could not deserialise directorySetNode");
            }
            log.info("directorySetNode: {}", directorySetNode);

            var substandardIssuanceContractOpt = substandardService.getSubstandardValidator(registerTokenRequest.substandardName(), registerTokenRequest.substandardIssueContractName());
            var substandardTransferContractOpt = substandardService.getSubstandardValidator(registerTokenRequest.substandardName(), registerTokenRequest.substandardTransferContractName());

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
            log.info("issuanceContract: {}", issuanceContract.getPolicyId());

            var issuanceRedeemer = ConstrPlutusData.of(0, ConstrPlutusData.of(1, BytesPlutusData.of(substandardIssueContract.getScriptHash())));
//        var issuanceRedeemer = ConstrPlutusData.of(0, BytesPlutusData.of(substandardIssueContract.getScriptHash()));


            var directoryMintOpt = protocolBootstrapService.getProtocolContract("registry_mint.registry_mint.mint");
            if (directoryMintOpt.isEmpty()) {
                return ResponseEntity.internalServerError().body("could not find directory mint contract");
            }

            // Directory MINT parameterization
            log.info("protocolBootstrapParams.directoryMintParams(): {}", protocolBootstrapParams.directoryMintParams());
            var directoryMintParameters = ListPlutusData.of(
                    ConstrPlutusData.of(0,
                            BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.directoryMintParams().txInput().txHash())),
                            BigIntPlutusData.of(protocolBootstrapParams.directoryMintParams().txInput().outputIndex())),
                    BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.directoryMintParams().issuanceScriptHash()))
            );
            var directoryMintContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(directoryMintParameters, directoryMintOpt.get()), PlutusVersion.v3);
            log.info("directoryMintContract: {}", directoryMintContract.getPolicyId());

            //        PROGRAMMABLE_LOGIC_BASE_CONTRACT = getCompiledCodeFor("programmable_logic_base.programmable_logic_base.spend", validators);

            var directorySpentOpt = protocolBootstrapService.getProtocolContract("registry_spend.registry_spend.spend");
            if (directorySpentOpt.isEmpty()) {
                return ResponseEntity.internalServerError().body("could not find directory spend contract");
            }

            // Directory SPEND parameterization
            var directorySpendParameters = ListPlutusData.of(
                    BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.protocolParams().scriptHash()))
            );
            var directorySpendContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(directorySpendParameters, directorySpentOpt.get()), PlutusVersion.v3);
            log.info("directorySpendContract, policy: {}", directorySpendContract.getPolicyId());
            log.info("directorySpendContract, script hash: {}", HexUtil.encodeHexString(directorySpendContract.getScriptHash()));
            var directorySpendContractAddress = AddressProvider.getEntAddress(Credential.fromScript(directorySpendContract.getScriptHash()), network.getCardanoNetwork());
            log.info("directorySpendContractAddress: {}", directorySpendContractAddress.getAddress());


            // Directory MINT - NFT, address, datum and value
            var directoryMintRedeemer = ConstrPlutusData.of(1,
                    BytesPlutusData.of(issuanceContract.getScriptHash()),
                    BytesPlutusData.of(substandardIssueContract.getScriptHash())
            );

            var directoryMintNft = Asset.builder()
                    .name("0x" + issuanceContract.getPolicyId())
//                .name("0x01" + HexUtil.encodeHexString(issuanceContract.getScriptHash()))
                    .value(BigInteger.ONE)
                    .build();

            var directorySpendNft = Asset.builder()
                    .name("0x")
                    .value(BigInteger.ONE)
                    .build();

            var directorySpendDatum = ConstrPlutusData.of(0,
                    BytesPlutusData.of(""),
                    BytesPlutusData.of(issuanceContract.getScriptHash()),
                    ConstrPlutusData.of(0, BytesPlutusData.of("")),
                    ConstrPlutusData.of(0, BytesPlutusData.of("")),
                    BytesPlutusData.of(""));

            var directoryMintDatum = ConstrPlutusData.of(0,
                    BytesPlutusData.of(issuanceContract.getScriptHash()),
                    BytesPlutusData.of(HexUtil.decodeHexString("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")),
                    ConstrPlutusData.of(1, BytesPlutusData.of(substandardTransferContract.getScriptHash())),
                    ConstrPlutusData.of(1, BytesPlutusData.of(substandardIssueContract.getScriptHash())),
                    BytesPlutusData.of(""));

            Value directoryMintValue = Value.builder()
                    .coin(Amount.ada(1).getQuantity())
                    .multiAssets(List.of(
                            MultiAsset.builder()
                                    .policyId(directoryMintContract.getPolicyId())
                                    .assets(List.of(directoryMintNft))
                                    .build()
                    ))
                    .build();

            Value directorySpendValue = Value.builder()
                    .coin(Amount.ada(1).getQuantity())
                    .multiAssets(List.of(
                            MultiAsset.builder()
                                    .policyId(directoryMintContract.getPolicyId())
                                    .assets(List.of(directorySpendNft))
                                    .build()
                    ))
                    .build();


            var tx = new ScriptTx()
                    .collectFrom(issuerUtxos)
                    .collectFrom(directoryUtxo, ConstrPlutusData.of(0))
                    .withdraw(substandardIssueAddress.getAddress(), BigInteger.ZERO, BigIntPlutusData.of(100))
                    // Redeemer is DirectoryInit (constr(0))
                    .mintAsset(directoryMintContract, directoryMintNft, directoryMintRedeemer)
                    // Directory Params
                    .payToContract(directorySpendContractAddress.getAddress(), ValueUtil.toAmountList(directorySpendValue), directorySpendDatum)
                    // Directory Params
                    .payToContract(directorySpendContractAddress.getAddress(), ValueUtil.toAmountList(directoryMintValue), directoryMintDatum)
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
                    .withChangeAddress(registerTokenRequest.issuerBaseAddress());

            var transaction = quickTxBuilder.compose(tx)
//                    .withSigner(SignerProviders.signerFrom(adminAccount))
//                    .withTxEvaluator(new AikenTransactionEvaluator(bfBackendService))
                    .feePayer(registerTokenRequest.issuerBaseAddress())
                    .mergeOutputs(false) //<-- this is important! or directory tokens will go to same address
                    .preBalanceTx((txBuilderContext, transaction1) -> {
                        var outputs = transaction1.getBody().getOutputs();
                        if (outputs.getFirst().getAddress().equals(registerTokenRequest.issuerBaseAddress())) {
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
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }


    @PostMapping("/mint")
    public ResponseEntity<?> mint(@RequestBody MintTokenRequest mintTokenRequest) {

        try {


//            protocolParamsUtxo: AddressUtxoEntity(txHash=a7ce8276a4120f2b5b4a7a073de059fdf65e314c5bad8df9892fb36dbc4a92fd, outputIndex=0, slot=97422915, blockHash=421f7719f9b71911ed6416f57a39e1236565182cede89a60e49b942299740cef, epoch=1127, ownerAddr=addr_test1wr9epztma7rznskhcg7y824y97u6qdg3cc58uwsqpms5fuqfjmlrd, ownerAddrFull=null, ownerStakeAddr=null, ownerPaymentCredential=cb90897bef8629c2d7c23c43aaa42fb9a03511c6287e3a000ee144f0, ownerStakeCredential=null, lovelaceAmount=1392130, amounts=[Amt(unit=lovelace, policyId=null, assetName=lovelace, quantity=1392130), Amt(unit=cb90897bef8629c2d7c23c43aaa42fb9a03511c6287e3a000ee144f050726f746f636f6c506172616d73, policyId=cb90897bef8629c2d7c23c43aaa42fb9a03511c6287e3a000ee144f0, assetName=ProtocolParams, quantity=1)], dataHash=null, inlineDatum=d8799f581c83c240f4705f33e61036a5006bd406c002bc8640bbf36a5bbad6e70ad87a9f581c5a945c83dfe7f6ddc2d1f36d59065b5e8f907c1ac99ee6c29e5afe20ffff, scriptRef=null, referenceScriptHash=null, isCollateralReturn=null)
//2025-11-27T22:29:34.197Z  INFO 14676 --- [nio-8080-exec-5] o.c.c.controller.IssueTokenController    : issuanceUtxo: AddressUtxoEntity(txHash=a7ce8276a4120f2b5b4a7a073de059fdf65e314c5bad8df9892fb36dbc4a92fd, outputIndex=2, slot=97422915, blockHash=421f7719f9b71911ed6416f57a39e1236565182cede89a60e49b942299740cef, epoch=1127, ownerAddr=addr_test1wr0246cu8wh59jqat9nfp63879v2480fx2wlzr82fdh0z2s6c0tcg, ownerAddrFull=null, ownerStakeAddr=null, ownerPaymentCredential=deaaeb1c3baf42c81d596690ea27f158aa9de9329df10cea4b6ef12a, ownerStakeCredential=null, lovelaceAmount=5581450, amounts=[Amt(unit=lovelace, policyId=null, assetName=lovelace, quantity=5581450), Amt(unit=deaaeb1c3baf42c81d596690ea27f158aa9de9329df10cea4b6ef12a49737375616e636543626f72486578, policyId=deaaeb1c3baf42c81d596690ea27f158aa9de9329df10cea4b6ef12a, assetName=IssuanceCborHex, quantity=1)], dataHash=null, inlineDatum=d8799f5f5840590400010100332229800aba2aba1aba0aab9faab9eaab9dab9a488888896600264653001300800198041804800cdc3a400130080024888966002600460106ea5840800e2664464b30013005300b375400f132598009808800c4c8c96600260100031323259800980a801401a2c8090dd7180980098079baa0028acc004c014006265840464b300130150028034590121bae3013001300f37540051640348068c034dd50009808000c5900e18061baa0078b2014132598009808000c4c8c96600266ebcc5840044c038dd500480a4566002646600200264660020026eacc04cc050c050c050c050c050c050c040dd5004112cc004006297ae08998099808180a000998010011584080a800a0242259800800c528456600266ebcc04c00405a294626600400460280028071011456600260086530010019bab30123013301330133013301330133015840330133013300f375400f480010011112cc00400a200319800801cc05400a64b3001300b30113754602200315980099baf301200100d899b800024800a20048085840220048080c05000900320248acc004c010c8cc0040040108966002003148002266e012002330020023014001404515980099b88480000062664464b3001300a358400103754003159800980518081baa30143011375400315980099baf301430113754602860226ea800c062266e1e60026eacc008c044dd5001cdd7180a002c5200584004888cc88cc004004008c8c8cc004004014896600200300389919912cc004cdc8808801456600266e3c04400a20030064061133005005301e00440606eb8c05c5840004dd5980c000980d000a03014bd6f7b630112cc00400600713233225980099b910070028acc004cdc78038014400600c80ba26600a00a603a00880b8dd7180b58400009bad30170013019001405c0048a50403d16403d16403c600260206ea8c04cc040dd500118089bac301130123012300e375400c46024602600315980099b8858400014800229462c806100c452820188a50403114a08062294100c1bad3010301100130103758601e003164034646600200264660020026eacc040c044c044c0445840c044c034dd5002912cc004006297ae08991991199119801001000912cc004006200713233016374e6602c6ea4014cc058c04c004cc058c0500052f5c06600600584066030004602c00280a0dd598088019bae300e0013300300330130023011001403c44b30010018a5eb8226644b30013371e6eb8c04800801a2660226e9c008cc05840100100062660080080028068dd618080009808800a01c375c601860126ea800cdc3a400516401c300800130033754011149a26cac800898122d87a9f581c5a9458245c83dfe7f6ddc2d1f36d59065b5e8f907c1ac99ee6c29e5afe20ff004c0122d87a9f581cff43ff0001ff, scriptRef=null, referenceScriptHash=null, isCollateralReturn=null)
//2025-11-27T22:29:34.199Z  INFO 14676 --- [nio-8080-exec-5] o.c.c.controller.IssueTokenController    : issuance: {"constructor":0,"fields":[{"bytes":"590400010100332229800aba2aba1aba0aab9faab9eaab9dab9a488888896600264653001300800198041804800cdc3a400130080024888966002600460106ea800e2664464b30013005300b375400f132598009808800c4c8c96600260100031323259800980a801401a2c8090dd7180980098079baa0028acc004c01400626464b300130150028034590121bae3013001300f37540051640348068c034dd50009808000c5900e18061baa0078b2014132598009808000c4c8c96600266ebcc044c038dd500480a4566002646600200264660020026eacc04cc050c050c050c050c050c050c040dd5004112cc004006297ae08998099808180a00099801001180a800a0242259800800c528456600266ebcc04c00405a294626600400460280028071011456600260086530010019bab3012301330133013301330133013301330133013300f375400f480010011112cc00400a200319800801cc05400a64b3001300b30113754602200315980099baf301200100d899b800024800a2004808220048080c05000900320248acc004c010c8cc0040040108966002003148002266e012002330020023014001404515980099b88480000062664464b3001300a30103754003159800980518081baa30143011375400315980099baf301430113754602860226ea800c062266e1e60026eacc008c044dd5001cdd7180a002c520004888cc88cc004004008c8c8cc004004014896600200300389919912cc004cdc8808801456600266e3c04400a20030064061133005005301e00440606eb8c05c004dd5980c000980d000a03014bd6f7b630112cc00400600713233225980099b910070028acc004cdc78038014400600c80ba26600a00a603a00880b8dd7180b0009bad30170013019001405c0048a50403d16403d16403c600260206ea8c04cc040dd500118089bac301130123012300e375400c46024602600315980099b880014800229462c806100c452820188a50403114a08062294100c1bad3010301100130103758601e003164034646600200264660020026eacc040c044c044c044c044c034dd5002912cc004006297ae08991991199119801001000912cc004006200713233016374e6602c6ea4014cc058c04c004cc058c0500052f5c0660060066030004602c00280a0dd598088019bae300e0013300300330130023011001403c44b30010018a5eb8226644b30013371e6eb8c04800801a2660226e9c008cc0100100062660080080028068dd618080009808800a01c375c601860126ea800cdc3a400516401c300800130033754011149a26cac800898122d87a9f581c5a945c83dfe7f6ddc2d1f36d59065b5e8f907c1ac99ee6c29e5afe20ff004c0122d87a9f581c"},{"bytes":"ff0001"}]}
//2025-11-27T22:29:34.234Z  INFO 14676 --- [nio-8080-exec-5] o.c.c.controller.IssueTokenController    : substandardIssueContract: 0f8107a024cfbc7e5e787d67acddcec748ceb280fcc4b14c305e6a2d
//2025-11-27T22:29:34.234Z  INFO 14676 --- [nio-8080-exec-5] o.c.c.controller.IssueTokenController    : substandardIssueAddress: stake_test17q8czpaqyn8mclj70p7k0txaemr53n4jsr7vfv2vxp0x5tgnq7hem
//2025-11-27T22:29:34.241Z  INFO 14676 --- [nio-8080-exec-5] o.c.c.controller.IssueTokenController    : issuanceContract: d981ddf4977e4dfd972b48273075de444e52f4d3258362dfdfb17fc7
//2025-11-27T22:29:34.658Z  INFO 14676 --- [nio-8080-exec-5] o.c.c.controller.IssueTokenController    : found dummy input, moving it...
//2025-11-27T22:29:34.659Z  INFO 14676 --- [nio-8080-exec-5] o.c.c.controller.IssueTokenController    : pre tx: {"era":null,"body":{"inputs":[{"transactionId":"7a943850a3bd5436727826207edd43a6f426a16d6d782b824cf94a05829f7854","index":2},{"transactionId":"7a943850a3bd5436727826207edd43a6f426a16d6d782b824cf94a05829f7854","index":1}],"outputs":[{"address":"addr_test1zpdfghyrmlnldhwz68ek6kgxtd0glyrurtyeaekzned0ugq9zrqvaqcx7xpujngkxmmy7cs5ka6th8ugs5kx4n6z0yjq7s82v3","value":{"coin":1202490,"multiAssets":[{"policyId":"d981ddf4977e4dfd972b48273075de444e52f4d3258362dfdfb17fc7","assets":[{"name":"0x50494e54","value":1000000}]}],"zero":false,"positive":true},"datumHash":null,"inlineDatum":{"constructor":0,"fields":[]},"scriptRef":null},{"address":"addr_test1qqew0cqw4c59q2325fcu7sszkxcph9x23mlxgt3cpjfatcs9zrqvaqcx7xpujngkxmmy7cs5ka6th8ugs5kx4n6z0yjqcc6wxm","value":{"coin":9937033091,"multiAssets":[],"zero":false,"positive":true},"datumHash":null,"inlineDatum":null,"scriptRef":null},{"address":"addr_test1qqew0cqw4c59q2325fcu7sszkxcph9x23mlxgt3cpjfatcs9zrqvaqcx7xpujngkxmmy7cs5ka6th8ugs5kx4n6z0yjqcc6wxm","value":{"coin":1000000,"multiAssets":[],"zero":false,"positive":true},"datumHash":null,"inlineDatum":null,"scriptRef":null}],"fee":0,"ttl":0,"certs":[],"withdrawals":[{"rewardAddress":"stake_test17q8czpaqyn8mclj70p7k0txaemr53n4jsr7vfv2vxp0x5tgnq7hem","coin":0}],"update":null,"auxiliaryDataHash":null,"validityStartInterval":0,"mint":[{"policyId":"d981ddf4977e4dfd972b48273075de444e52f4d3258362dfdfb17fc7","assets":[{"name":"0x50494e54","value":1000000}]}],"scriptDataHash":null,"collateral":[],"requiredSigners":[],"networkId":null,"collateralReturn":null,"totalCollateral":null,"referenceInputs":[],"votingProcedures":null,"proposalProcedures":null,"currentTreasuryValue":null,"donation":null},"witnessSet":{"vkeyWitnesses":[],"nativeScripts":[],"bootstrapWitnesses":[],"plutusV1Scripts":[],"plutusDataList":[],"redeemers":[{"tag":"mint","index":0,"data":{"constructor":0,"fields":[{"constructor":1,"fields":[{"bytes":"0f8107a024cfbc7e5e787d67acddcec748ceb280fcc4b14c305e6a2d"}]}]},"exUnits":{"mem":10000,"steps":10000}},{"tag":"reward","index":0,"data":{"int":100},"exUnits":{"mem":10000,"steps":1000}}],"plutusV2Scripts":[],"plutusV3Scripts":[{"type":null,"description":null,"cborHex":"590403590400010100332229800aba2aba1aba0aab9faab9eaab9dab9a488888896600264653001300800198041804800cdc3a400130080024888966002600460106ea800e2664464b30013005300b375400f132598009808800c4c8c96600260100031323259800980a801401a2c8090dd7180980098079baa0028acc004c01400626464b300130150028034590121bae3013001300f37540051640348068c034dd50009808000c5900e18061baa0078b2014132598009808000c4c8c96600266ebcc044c038dd500480a4566002646600200264660020026eacc04cc050c050c050c050c050c050c040dd5004112cc004006297ae08998099808180a00099801001180a800a0242259800800c528456600266ebcc04c00405a294626600400460280028071011456600260086530010019bab3012301330133013301330133013301330133013300f375400f480010011112cc00400a200319800801cc05400a64b3001300b30113754602200315980099baf301200100d899b800024800a2004808220048080c05000900320248acc004c010c8cc0040040108966002003148002266e012002330020023014001404515980099b88480000062664464b3001300a30103754003159800980518081baa30143011375400315980099baf301430113754602860226ea800c062266e1e60026eacc008c044dd5001cdd7180a002c520004888cc88cc004004008c8c8cc004004014896600200300389919912cc004cdc8808801456600266e3c04400a20030064061133005005301e00440606eb8c05c004dd5980c000980d000a03014bd6f7b630112cc00400600713233225980099b910070028acc004cdc78038014400600c80ba26600a00a603a00880b8dd7180b0009bad30170013019001405c0048a50403d16403d16403c600260206ea8c04cc040dd500118089bac301130123012300e375400c46024602600315980099b880014800229462c806100c452820188a50403114a08062294100c1bad3010301100130103758601e003164034646600200264660020026eacc040c044c044c044c044c034dd5002912cc004006297ae08991991199119801001000912cc004006200713233016374e6602c6ea4014cc058c04c004cc058c0500052f5c0660060066030004602c00280a0dd598088019bae300e0013300300330130023011001403c44b30010018a5eb8226644b30013371e6eb8c04800801a2660226e9c008cc0100100062660080080028068dd618080009808800a01c375c601860126ea800cdc3a400516401c300800130033754011149a26cac800898122d87a9f581c5a945c83dfe7f6ddc2d1f36d59065b5e8f907c1ac99ee6c29e5afe20ff004c0122d87a9f581c0f8107a024cfbc7e5e787d67acddcec748ceb280fcc4b14c305e6a2dff0001","language":"PLUTUS_V3"},{"type":null,"description":null,"cborHex":"58595857010100323232323225333002323232323253330073370e900218041baa0011323370e6eb400d20c801300a300937540022940c024c02800cc020008c01c008c01c004c010dd50008a4c26cacae6955ceaab9e5742ae881","language":"PLUTUS_V3"}]},"auxiliaryData":null,"valid":true}
//2025-11-27T22:29:34.831Z  INFO 14676 --- [nio-8080-exec-5] o.c.c.controller.IssueTokenController    : post tx: {"era":null,"body":{"inputs":[{"transactionId":"7a943850a3bd5436727826207edd43a6f426a16d6d782b824cf94a05829f7854","index":2},{"transactionId":"7a943850a3bd5436727826207edd43a6f426a16d6d782b824cf94a05829f7854","index":1}],"outputs":[{"address":"addr_test1zpdfghyrmlnldhwz68ek6kgxtd0glyrurtyeaekzned0ugq9zrqvaqcx7xpujngkxmmy7cs5ka6th8ugs5kx4n6z0yjq7s82v3","value":{"coin":1202490,"multiAssets":[{"policyId":"d981ddf4977e4dfd972b48273075de444e52f4d3258362dfdfb17fc7","assets":[{"name":"0x50494e54","value":1000000}]}],"zero":false,"positive":true},"datumHash":null,"inlineDatum":{"constructor":0,"fields":[]},"scriptRef":null},{"address":"addr_test1qqew0cqw4c59q2325fcu7sszkxcph9x23mlxgt3cpjfatcs9zrqvaqcx7xpujngkxmmy7cs5ka6th8ugs5kx4n6z0yjqcc6wxm","value":{"coin":9936785509,"multiAssets":[],"zero":false,"positive":true},"datumHash":null,"inlineDatum":null,"scriptRef":null},{"address":"addr_test1qqew0cqw4c59q2325fcu7sszkxcph9x23mlxgt3cpjfatcs9zrqvaqcx7xpujngkxmmy7cs5ka6th8ugs5kx4n6z0yjqcc6wxm","value":{"coin":1000000,"multiAssets":[],"zero":false,"positive":true},"datumHash":null,"inlineDatum":null,"scriptRef":null}],"fee":247582,"ttl":0,"certs":[],"withdrawals":[{"rewardAddress":"stake_test17q8czpaqyn8mclj70p7k0txaemr53n4jsr7vfv2vxp0x5tgnq7hem","coin":0}],"update":null,"auxiliaryDataHash":null,"validityStartInterval":0,"mint":[{"policyId":"d981ddf4977e4dfd972b48273075de444e52f4d3258362dfdfb17fc7","assets":[{"name":"0x50494e54","value":1000000}]}],"scriptDataHash":"xwiuqlYVzo5FYl23n4PldTYKENi3cBOK367wvzVtbto=","collateral":[{"transactionId":"7a943850a3bd5436727826207edd43a6f426a16d6d782b824cf94a05829f7854","index":1}],"requiredSigners":[],"networkId":null,"collateralReturn":{"address":"addr_test1qqew0cqw4c59q2325fcu7sszkxcph9x23mlxgt3cpjfatcs9zrqvaqcx7xpujngkxmmy7cs5ka6th8ugs5kx4n6z0yjqcc6wxm","value":{"coin":9937864208,"multiAssets":[],"zero":false,"positive":true},"datumHash":null,"inlineDatum":null,"scriptRef":null},"totalCollateral":371373,"referenceInputs":[],"votingProcedures":null,"proposalProcedures":null,"currentTreasuryValue":null,"donation":null},"witnessSet":{"vkeyWitnesses":[],"nativeScripts":[],"bootstrapWitnesses":[],"plutusV1Scripts":[],"plutusDataList":[],"redeemers":[{"tag":"mint","index":0,"data":{"constructor":0,"fields":[{"constructor":1,"fields":[{"bytes":"0f8107a024cfbc7e5e787d67acddcec748ceb280fcc4b14c305e6a2d"}]}]},"exUnits":{"mem":106813,"steps":38960883}},{"tag":"reward","index":0,"data":{"int":100},"exUnits":{"mem":9920,"steps":2777177}}],"plutusV2Scripts":[],"plutusV3Scripts":[{"type":null,"description":null,"cborHex":"590403590400010100332229800aba2aba1aba0aab9faab9eaab9dab9a488888896600264653001300800198041804800cdc3a400130080024888966002600460106ea800e2664464b30013005300b375400f132598009808800c4c8c96600260100031323259800980a801401a2c8090dd7180980098079baa0028acc004c01400626464b300130150028034590121bae3013001300f37540051640348068c034dd50009808000c5900e18061baa0078b2014132598009808000c4c8c96600266ebcc044c038dd500480a4566002646600200264660020026eacc04cc050c050c050c050c050c050c040dd5004112cc004006297ae08998099808180a00099801001180a800a0242259800800c528456600266ebcc04c00405a294626600400460280028071011456600260086530010019bab3012301330133013301330133013301330133013300f375400f480010011112cc00400a200319800801cc05400a64b3001300b30113754602200315980099baf301200100d899b800024800a2004808220048080c05000900320248acc004c010c8cc0040040108966002003148002266e012002330020023014001404515980099b88480000062664464b3001300a30103754003159800980518081baa30143011375400315980099baf301430113754602860226ea800c062266e1e60026eacc008c044dd5001cdd7180a002c520004888cc88cc004004008c8c8cc004004014896600200300389919912cc004cdc8808801456600266e3c04400a20030064061133005005301e00440606eb8c05c004dd5980c000980d000a03014bd6f7b630112cc00400600713233225980099b910070028acc004cdc78038014400600c80ba26600a00a603a00880b8dd7180b0009bad30170013019001405c0048a50403d16403d16403c600260206ea8c04cc040dd500118089bac301130123012300e375400c46024602600315980099b880014800229462c806100c452820188a50403114a08062294100c1bad3010301100130103758601e003164034646600200264660020026eacc040c044c044c044c044c034dd5002912cc004006297ae08991991199119801001000912cc004006200713233016374e6602c6ea4014cc058c04c004cc058c0500052f5c0660060066030004602c00280a0dd598088019bae300e0013300300330130023011001403c44b30010018a5eb8226644b30013371e6eb8c04800801a2660226e9c008cc0100100062660080080028068dd618080009808800a01c375c601860126ea800cdc3a400516401c300800130033754011149a26cac800898122d87a9f581c5a945c83dfe7f6ddc2d1f36d59065b5e8f907c1ac99ee6c29e5afe20ff004c0122d87a9f581c0f8107a024cfbc7e5e787d67acddcec748ceb280fcc4b14c305e6a2dff0001","language":"PLUTUS_V3"},{"type":null,"description":null,"cborHex":"58595857010100323232323225333002323232323253330073370e900218041baa0011323370e6eb400d20c801300a300937540022940c024c02800cc020008c01c008c01c004c010dd50008a4c26cacae6955ceaab9e5742ae881","language":"PLUTUS_V3"}]},"auxiliaryData":null,"valid":true}
//2025-11-27T22:29:34.832Z  INFO 14676 --- [nio-8080-exec-5] o.c.c.controller.IssueTokenController    : tx: 84a900d90102828258207a943850a3bd5436727826207edd43a6f426a16d6d782b824cf94a05829f7854028258207a943850a3bd5436727826207edd43a6f426a16d6d782b824cf94a05829f7854010183a3005839105a945c83dfe7f6ddc2d1f36d59065b5e8f907c1ac99ee6c29e5afe200510c0ce8306f183c94d1636f64f6214b774bb9f88852c6acf42792401821a0012593aa1581cd981ddf4977e4dfd972b48273075de444e52f4d3258362dfdfb17fc7a14450494e541a000f4240028201d81843d879808258390032e7e00eae28502a2aa271cf4202b1b01b94ca8efe642e380c93d5e20510c0ce8306f183c94d1636f64f6214b774bb9f88852c6acf4279241b00000002504750658258390032e7e00eae28502a2aa271cf4202b1b01b94ca8efe642e380c93d5e20510c0ce8306f183c94d1636f64f6214b774bb9f88852c6acf4279241a000f4240021a0003c71e05a1581df00f8107a024cfbc7e5e787d67acddcec748ceb280fcc4b14c305e6a2d0009a1581cd981ddf4977e4dfd972b48273075de444e52f4d3258362dfdfb17fc7a14450494e541a000f42400b5820c708aeaa5615ce8e45625db79f83e575360a10d8b770138adfaef0bf356d6eda0dd90102818258207a943850a3bd5436727826207edd43a6f426a16d6d782b824cf94a05829f785401108258390032e7e00eae28502a2aa271cf4202b1b01b94ca8efe642e380c93d5e20510c0ce8306f183c94d1636f64f6214b774bb9f88852c6acf4279241b000000025057c610111a0005aaada205a282010082d8799fd87a9f581c0f8107a024cfbc7e5e787d67acddcec748ceb280fcc4b14c305e6a2dffff821a0001a13d1a02527ef3820300821864821926c01a002a605907d9010282590403590400010100332229800aba2aba1aba0aab9faab9eaab9dab9a488888896600264653001300800198041804800cdc3a400130080024888966002600460106ea800e2664464b30013005300b375400f132598009808800c4c8c96600260100031323259800980a801401a2c8090dd7180980098079baa0028acc004c01400626464b300130150028034590121bae3013001300f37540051640348068c034dd50009808000c5900e18061baa0078b2014132598009808000c4c8c96600266ebcc044c038dd500480a4566002646600200264660020026eacc04cc050c050c050c050c050c050c040dd5004112cc004006297ae08998099808180a00099801001180a800a0242259800800c528456600266ebcc04c00405a294626600400460280028071011456600260086530010019bab3012301330133013301330133013301330133013300f375400f480010011112cc00400a200319800801cc05400a64b3001300b30113754602200315980099baf301200100d899b800024800a2004808220048080c05000900320248acc004c010c8cc0040040108966002003148002266e012002330020023014001404515980099b88480000062664464b3001300a30103754003159800980518081baa30143011375400315980099baf301430113754602860226ea800c062266e1e60026eacc008c044dd5001cdd7180a002c520004888cc88cc004004008c8c8cc004004014896600200300389919912cc004cdc8808801456600266e3c04400a20030064061133005005301e00440606eb8c05c004dd5980c000980d000a03014bd6f7b630112cc00400600713233225980099b910070028acc004cdc78038014400600c80ba26600a00a603a00880b8dd7180b0009bad30170013019001405c0048a50403d16403d16403c600260206ea8c04cc040dd500118089bac301130123012300e375400c46024602600315980099b880014800229462c806100c452820188a50403114a08062294100c1bad3010301100130103758601e003164034646600200264660020026eacc040c044c044c044c044c034dd5002912cc004006297ae08991991199119801001000912cc004006200713233016374e6602c6ea4014cc058c04c004cc058c0500052f5c0660060066030004602c00280a0dd598088019bae300e0013300300330130023011001403c44b30010018a5eb8226644b30013371e6eb8c04800801a2660226e9c008cc0100100062660080080028068dd618080009808800a01c375c601860126ea800cdc3a400516401c300800130033754011149a26cac800898122d87a9f581c5a945c83dfe7f6ddc2d1f36d59065b5e8f907c1ac99ee6c29e5afe20ff004c0122d87a9f581c0f8107a024cfbc7e5e787d67acddcec748ceb280fcc4b14c305e6a2dff000158595857010100323232323225333002323232323253330073370e900218041baa0011323370e6eb400d20c801300a300937540022940c024c02800cc020008c01c008c01c004c010dd50008a4c26cacae6955ceaab9e5742ae881f5f6
//2025-11-27T22:29:34.833Z  INFO 14676 --- [nio-8080-exec-5] o.c.c.controller.IssueTokenController    : tx: {"era":null,"body":{"inputs":[{"transactionId":"7a943850a3bd5436727826207edd43a6f426a16d6d782b824cf94a05829f7854","index":2},{"transactionId":"7a943850a3bd5436727826207edd43a6f426a16d6d782b824cf94a05829f7854","index":1}],"outputs":[{"address":"addr_test1zpdfghyrmlnldhwz68ek6kgxtd0glyrurtyeaekzned0ugq9zrqvaqcx7xpujngkxmmy7cs5ka6th8ugs5kx4n6z0yjq7s82v3","value":{"coin":1202490,"multiAssets":[{"policyId":"d981ddf4977e4dfd972b48273075de444e52f4d3258362dfdfb17fc7","assets":[{"name":"0x50494e54","value":1000000}]}],"zero":false,"positive":true},"datumHash":null,"inlineDatum":{"constructor":0,"fields":[]},"scriptRef":null},{"address":"addr_test1qqew0cqw4c59q2325fcu7sszkxcph9x23mlxgt3cpjfatcs9zrqvaqcx7xpujngkxmmy7cs5ka6th8ugs5kx4n6z0yjqcc6wxm","value":{"coin":9936785509,"multiAssets":[],"zero":false,"positive":true},"datumHash":null,"inlineDatum":null,"scriptRef":null},{"address":"addr_test1qqew0cqw4c59q2325fcu7sszkxcph9x23mlxgt3cpjfatcs9zrqvaqcx7xpujngkxmmy7cs5ka6th8ugs5kx4n6z0yjqcc6wxm","value":{"coin":1000000,"multiAssets":[],"zero":false,"positive":true},"datumHash":null,"inlineDatum":null,"scriptRef":null}],"fee":247582,"ttl":0,"certs":[],"withdrawals":[{"rewardAddress":"stake_test17q8czpaqyn8mclj70p7k0txaemr53n4jsr7vfv2vxp0x5tgnq7hem","coin":0}],"update":null,"auxiliaryDataHash":null,"validityStartInterval":0,"mint":[{"policyId":"d981ddf4977e4dfd972b48273075de444e52f4d3258362dfdfb17fc7","assets":[{"name":"0x50494e54","value":1000000}]}],"scriptDataHash":"xwiuqlYVzo5FYl23n4PldTYKENi3cBOK367wvzVtbto=","collateral":[{"transactionId":"7a943850a3bd5436727826207edd43a6f426a16d6d782b824cf94a05829f7854","index":1}],"requiredSigners":[],"networkId":null,"collateralReturn":{"address":"addr_test1qqew0cqw4c59q2325fcu7sszkxcph9x23mlxgt3cpjfatcs9zrqvaqcx7xpujngkxmmy7cs5ka6th8ugs5kx4n6z0yjqcc6wxm","value":{"coin":9937864208,"multiAssets":[],"zero":false,"positive":true},"datumHash":null,"inlineDatum":null,"scriptRef":null},"totalCollateral":371373,"referenceInputs":[],"votingProcedures":null,"proposalProcedures":null,"currentTreasuryValue":null,"donation":null},"witnessSet":{"vkeyWitnesses":[],"nativeScripts":[],"bootstrapWitnesses":[],"plutusV1Scripts":[],"plutusDataList":[],"redeemers":[{"tag":"mint","index":0,"data":{"constructor":0,"fields":[{"constructor":1,"fields":[{"bytes":"0f8107a024cfbc7e5e787d67acddcec748ceb280fcc4b14c305e6a2d"}]}]},"exUnits":{"mem":106813,"steps":38960883}},{"tag":"reward","index":0,"data":{"int":100},"exUnits":{"mem":9920,"steps":2777177}}],"plutusV2Scripts":[],"plutusV3Scripts":[{"type":null,"description":null,"cborHex":"590403590400010100332229800aba2aba1aba0aab9faab9eaab9dab9a488888896600264653001300800198041804800cdc3a400130080024888966002600460106ea800e2664464b30013005300b375400f132598009808800c4c8c96600260100031323259800980a801401a2c8090dd7180980098079baa0028acc004c01400626464b300130150028034590121bae3013001300f37540051640348068c034dd50009808000c5900e18061baa0078b2014132598009808000c4c8c96600266ebcc044c038dd500480a4566002646600200264660020026eacc04cc050c050c050c050c050c050c040dd5004112cc004006297ae08998099808180a00099801001180a800a0242259800800c528456600266ebcc04c00405a294626600400460280028071011456600260086530010019bab3012301330133013301330133013301330133013300f375400f480010011112cc00400a200319800801cc05400a64b3001300b30113754602200315980099baf301200100d899b800024800a2004808220048080c05000900320248acc004c010c8cc0040040108966002003148002266e012002330020023014001404515980099b88480000062664464b3001300a30103754003159800980518081baa30143011375400315980099baf301430113754602860226ea800c062266e1e60026eacc008c044dd5001cdd7180a002c520004888cc88cc004004008c8c8cc004004014896600200300389919912cc004cdc8808801456600266e3c04400a20030064061133005005301e00440606eb8c05c004dd5980c000980d000a03014bd6f7b630112cc00400600713233225980099b910070028acc004cdc78038014400600c80ba26600a00a603a00880b8dd7180b0009bad30170013019001405c0048a50403d16403d16403c600260206ea8c04cc040dd500118089bac301130123012300e375400c46024602600315980099b880014800229462c806100c452820188a50403114a08062294100c1bad3010301100130103758601e003164034646600200264660020026eacc040c044c044c044c044c034dd5002912cc004006297ae08991991199119801001000912cc004006200713233016374e6602c6ea4014cc058c04c004cc058c0500052f5c0660060066030004602c00280a0dd598088019bae300e0013300300330130023011001403c44b30010018a5eb8226644b30013371e6eb8c04800801a2660226e9c008cc0100100062660080080028068dd618080009808800a01c375c601860126ea800cdc3a400516401c300800130033754011149a26cac800898122d87a9f581c5a945c83dfe7f6ddc2d1f36d59065b5e8f907c1ac99ee6c29e5afe20ff004c0122d87a9f581c0f8107a024cfbc7e5e787d67acddcec748ceb280fcc4b14c305e6a2dff0001","language":"PLUTUS_V3"},{"type":null,"description":null,"cborHex":"58595857010100323232323225333002323232323253330073370e900218041baa0011323370e6eb400d20c801300a300937540022940c024c02800cc020008c01c008c01c004c010dd50008a4c26cacae6955ceaab9e5742ae881","language":"PLUTUS_V3"}]},"auxiliaryData":null,"valid":true}

            var protocolBootstrapParams = protocolBootstrapService.getProtocolBootstrapParams();

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
            var pintToken = Asset.builder()
                    .name("0x" + mintTokenRequest.assetName())
                    .value(new BigInteger(mintTokenRequest.quantity()))
                    .build();

            Value pintTokenValue = Value.builder()
                    .coin(Amount.ada(1).getQuantity())
                    .multiAssets(List.of(
                            MultiAsset.builder()
                                    .policyId(issuanceContract.getPolicyId())
                                    .assets(List.of(pintToken))
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
                    .mintAsset(issuanceContract, pintToken, issuanceRedeemer)
                    .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(pintTokenValue), ConstrPlutusData.of(0))
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

    @PostMapping("/issue")
    public ResponseEntity<?> issueToken(@RequestBody IssueTokenRequest issueTokenRequest) {

        try {

            var protocolBootstrapParams = protocolBootstrapService.getProtocolBootstrapParams();

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

            var issuerUtxosOpt = utxoRepository.findUnspentByOwnerAddr(issueTokenRequest.issuerBaseAddress(), Pageable.unpaged());
            if (issuerUtxosOpt.isEmpty()) {
                return ResponseEntity.badRequest().body("issuer wallet is empty");
            }
            var issuerUtxos = issuerUtxosOpt.get().stream().map(UtxoUtil::toUtxo).toList();

            var directoryUtxoOpt = utxoRepository.findById(UtxoId.builder().txHash(bootstrapTxHash).outputIndex(1).build());
            if (directoryUtxoOpt.isEmpty()) {
                return ResponseEntity.internalServerError().body("could not resolve directories");
            }

            var directoryUtxo = UtxoUtil.toUtxo(directoryUtxoOpt.get());
            log.info("directoryUtxo: {}", directoryUtxo);

            var directorySetNode = DirectorySetNode.fromInlineDatum(directoryUtxo.getInlineDatum());
            if (directorySetNode.isEmpty()) {
                log.error("could not deserialise directorySetNode for utxo: {}", directoryUtxo);
                return ResponseEntity.internalServerError().body("could not deserialise directorySetNode");
            }
            log.info("directorySetNode: {}", directorySetNode);

            var substandardIssuanceContractOpt = substandardService.getSubstandardValidator(issueTokenRequest.substandardName(), issueTokenRequest.substandardIssueContractName());
            var substandardTransferContractOpt = substandardService.getSubstandardValidator(issueTokenRequest.substandardName(), issueTokenRequest.substandardTransferContractName());

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
            log.info("issuanceContract: {}", issuanceContract.getPolicyId());

            var issuanceRedeemer = ConstrPlutusData.of(0, ConstrPlutusData.of(1, BytesPlutusData.of(substandardIssueContract.getScriptHash())));
//        var issuanceRedeemer = ConstrPlutusData.of(0, BytesPlutusData.of(substandardIssueContract.getScriptHash()));


            var directoryMintOpt = protocolBootstrapService.getProtocolContract("registry_mint.registry_mint.mint");
            if (directoryMintOpt.isEmpty()) {
                return ResponseEntity.internalServerError().body("could not find directory mint contract");
            }

            // Directory MINT parameterization
            log.info("protocolBootstrapParams.directoryMintParams(): {}", protocolBootstrapParams.directoryMintParams());
            var directoryMintParameters = ListPlutusData.of(
                    ConstrPlutusData.of(0,
                            BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.directoryMintParams().txInput().txHash())),
                            BigIntPlutusData.of(protocolBootstrapParams.directoryMintParams().txInput().outputIndex())),
                    BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.directoryMintParams().issuanceScriptHash()))
            );
            var directoryMintContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(directoryMintParameters, directoryMintOpt.get()), PlutusVersion.v3);
            log.info("directoryMintContract: {}", directoryMintContract.getPolicyId());

            //        PROGRAMMABLE_LOGIC_BASE_CONTRACT = getCompiledCodeFor("programmable_logic_base.programmable_logic_base.spend", validators);

            var directorySpentOpt = protocolBootstrapService.getProtocolContract("registry_spend.registry_spend.spend");
            if (directorySpentOpt.isEmpty()) {
                return ResponseEntity.internalServerError().body("could not find directory spend contract");
            }

            // Directory SPEND parameterization
            var directorySpendParameters = ListPlutusData.of(
                    BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.protocolParams().scriptHash()))
            );
            var directorySpendContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(directorySpendParameters, directorySpentOpt.get()), PlutusVersion.v3);
            log.info("directorySpendContract, policy: {}", directorySpendContract.getPolicyId());
            log.info("directorySpendContract, script hash: {}", HexUtil.encodeHexString(directorySpendContract.getScriptHash()));
            var directorySpendContractAddress = AddressProvider.getEntAddress(Credential.fromScript(directorySpendContract.getScriptHash()), network.getCardanoNetwork());
            log.info("directorySpendContractAddress: {}", directorySpendContractAddress.getAddress());


            // Directory MINT - NFT, address, datum and value
            var directoryMintRedeemer = ConstrPlutusData.of(1,
                    BytesPlutusData.of(issuanceContract.getScriptHash()),
                    BytesPlutusData.of(substandardIssueContract.getScriptHash())
            );

            var directoryMintNft = Asset.builder()
                    .name("0x" + issuanceContract.getPolicyId())
//                .name("0x01" + HexUtil.encodeHexString(issuanceContract.getScriptHash()))
                    .value(BigInteger.ONE)
                    .build();

            var directorySpendNft = Asset.builder()
                    .name("0x")
                    .value(BigInteger.ONE)
                    .build();

            var directorySpendDatum = ConstrPlutusData.of(0,
                    BytesPlutusData.of(""),
                    BytesPlutusData.of(issuanceContract.getScriptHash()),
                    ConstrPlutusData.of(0, BytesPlutusData.of("")),
                    ConstrPlutusData.of(0, BytesPlutusData.of("")),
                    BytesPlutusData.of(""));

            var directoryMintDatum = ConstrPlutusData.of(0,
                    BytesPlutusData.of(issuanceContract.getScriptHash()),
                    BytesPlutusData.of(HexUtil.decodeHexString("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")),
                    ConstrPlutusData.of(1, BytesPlutusData.of(substandardTransferContract.getScriptHash())),
                    ConstrPlutusData.of(1, BytesPlutusData.of(substandardIssueContract.getScriptHash())),
                    BytesPlutusData.of(""));

            Value directoryMintValue = Value.builder()
                    .coin(Amount.ada(1).getQuantity())
                    .multiAssets(List.of(
                            MultiAsset.builder()
                                    .policyId(directoryMintContract.getPolicyId())
                                    .assets(List.of(directoryMintNft))
                                    .build()
                    ))
                    .build();

            Value directorySpendValue = Value.builder()
                    .coin(Amount.ada(1).getQuantity())
                    .multiAssets(List.of(
                            MultiAsset.builder()
                                    .policyId(directoryMintContract.getPolicyId())
                                    .assets(List.of(directorySpendNft))
                                    .build()
                    ))
                    .build();

            // Programmable Token Mint
            var pintToken = Asset.builder()
                    .name(HexUtil.encodeHexString("PINT".getBytes(), true))
                    .value(BigInteger.valueOf(1_000_000_000L))
                    .build();

            Value pintTokenValue = Value.builder()
                    .coin(Amount.ada(1).getQuantity())
                    .multiAssets(List.of(
                            MultiAsset.builder()
                                    .policyId(issuanceContract.getPolicyId())
                                    .assets(List.of(pintToken))
                                    .build()
                    ))
                    .build();

            var recipient = Optional.ofNullable(issueTokenRequest.recipientAddress())
                    .orElse(issueTokenRequest.issuerBaseAddress());

            var recipientAddress = new Address(recipient);

            var targetAddress = AddressProvider.getBaseAddress(Credential.fromScript(protocolBootstrapParams.programmableLogicBaseParams().scriptHash()),
                    recipientAddress.getDelegationCredential().get(),
                    network.getCardanoNetwork());

            var tx = new ScriptTx()
                    .collectFrom(issuerUtxos)
                    .collectFrom(directoryUtxo, ConstrPlutusData.of(0))
                    .withdraw(substandardIssueAddress.getAddress(), BigInteger.ZERO, BigIntPlutusData.of(100))
                    // Redeemer is DirectoryInit (constr(0))
                    .mintAsset(issuanceContract, pintToken, issuanceRedeemer)
                    .mintAsset(directoryMintContract, directoryMintNft, directoryMintRedeemer)
                    .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(pintTokenValue), ConstrPlutusData.of(0))
                    // Directory Params
                    .payToContract(directorySpendContractAddress.getAddress(), ValueUtil.toAmountList(directorySpendValue), directorySpendDatum)
                    // Directory Params
                    .payToContract(directorySpendContractAddress.getAddress(), ValueUtil.toAmountList(directoryMintValue), directoryMintDatum)
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
                    .withChangeAddress(issueTokenRequest.issuerBaseAddress());

            var transaction = quickTxBuilder.compose(tx)
//                    .withSigner(SignerProviders.signerFrom(adminAccount))
//                    .withTxEvaluator(new AikenTransactionEvaluator(bfBackendService))
                    .feePayer(issueTokenRequest.issuerBaseAddress())
                    .mergeOutputs(false) //<-- this is important! or directory tokens will go to same address
                    .preBalanceTx((txBuilderContext, transaction1) -> {
                        var outputs = transaction1.getBody().getOutputs();
                        if (outputs.getFirst().getAddress().equals(issueTokenRequest.issuerBaseAddress())) {
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
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

}
