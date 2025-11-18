package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.yaci.store.common.domain.AddressUtxo;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;
import com.bloxbean.cardano.yaci.store.utxo.domain.AddressUtxoEvent;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.BalanceLogEntity;
import org.cardanofoundation.cip113.entity.ProtocolParamsEntity;
import org.cardanofoundation.cip113.util.AddressUtil;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class BalanceEventListener {

    private final BalanceService balanceService;
    private final ProtocolParamsService protocolParamsService;
    private final RegistryService registryService;
    private final UtxoRepository utxoRepository;


    @EventListener
    public void processEvent(AddressUtxoEvent addressUtxoEvent) {
        log.debug("Processing AddressUtxoEvent for balance indexing");

        // Get all protocol params to know all programmableLogicBaseScriptHashes
        List<ProtocolParamsEntity> allProtocolParams = protocolParamsService.getAll();
        if (allProtocolParams.isEmpty()) {
            log.debug("No protocol params loaded yet, skipping balance indexing");
            return;
        }

        // Get all programmable token base script hashes
        Set<String> progLogicScriptHashes = allProtocolParams.stream()
                .map(ProtocolParamsEntity::getProgLogicScriptHash)
                .collect(Collectors.toSet());

        log.debug("Monitoring {} programmable logic script hashes: {}",
                progLogicScriptHashes.size(), String.join(", ", progLogicScriptHashes));

        var slot = addressUtxoEvent.getEventMetadata().getSlot();
        var blockHeight = addressUtxoEvent.getEventMetadata().getBlock();

        // Process each transaction
        addressUtxoEvent.getTxInputOutputs().forEach(txInputOutputs -> {
            String txHash = txInputOutputs.getTxHash();

            // Track balance changes per address per asset
            // Key: address + "|" + policyId + "|" + assetName
            // Value: net change (outputs - inputs)
            Map<String, BalanceChange> balanceChanges = new HashMap<>();

            // Process inputs (subtractions)
            // Inputs don't have addresses directly, need to look up the UTxO being spent
            txInputOutputs.getInputs().forEach(input -> {
                // Look up the UTxO by txHash and outputIndex
                String inputTxHash = input.getTxHash();
                int outputIndex = input.getOutputIndex();

                // Create composite key for UTxO lookup
                var utxoIdOpt = utxoRepository.findById(
                    new com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId(inputTxHash, outputIndex)
                );

                if (utxoIdOpt.isEmpty()) {
                    log.debug("UTxO not found for input: {}:{}", inputTxHash, outputIndex);
                    return;
                }

                var utxo = utxoIdOpt.get();
                String address = utxo.getOwnerAddr();

                AddressUtil.AddressComponents components = AddressUtil.decompose(address);
                if (components != null && progLogicScriptHashes.contains(components.getPaymentScriptHash())) {
                    // Convert UTxO entity to AddressUtxo for processing
                    processUtxo(utxo.getAmounts(), components, balanceChanges, false);
                }
            });

            // Process outputs (additions)
            txInputOutputs.getOutputs().forEach(output -> {
                AddressUtil.AddressComponents components = AddressUtil.decompose(output.getOwnerAddr());
                if (components != null && progLogicScriptHashes.contains(components.getPaymentScriptHash())) {
                    processUtxo(output.getAmounts(), components, balanceChanges, true);
                }
            });

            // Save balance changes to database
            balanceChanges.forEach((key, change) -> {
                // Get previous balance
                BigInteger previousBalance = balanceService.getLatestBalance(
                        change.address,
                        change.policyId,
                        change.assetName
                ).map(BalanceLogEntity::getQuantity).orElse(BigInteger.ZERO);

                // Calculate new balance
                BigInteger newBalance = previousBalance.add(change.netChange);

                // Check if asset is programmable token
                boolean isProgrammableToken = registryService.isTokenRegistered(change.policyId);

                // Create balance log entry
                BalanceLogEntity entry = BalanceLogEntity.builder()
                        .address(change.address)
                        .paymentScriptHash(change.paymentScriptHash)
                        .stakeKeyHash(change.stakeKeyHash)
                        .txHash(txHash)
                        .slot(slot)
                        .blockHeight(blockHeight)
                        .policyId(change.policyId)
                        .assetName(change.assetName)
                        .quantity(newBalance)
                        .isProgrammableToken(isProgrammableToken)
                        .build();

                balanceService.append(entry);

                log.info("Recorded balance change: address={}, asset={}/{}, prev={}, change={}, new={}, tx={}",
                        change.address, change.policyId, change.assetName,
                        previousBalance, change.netChange, newBalance, txHash);
            });
        });
    }

    private void processUtxo(List<Amt> amounts, AddressUtil.AddressComponents components,
                             Map<String, BalanceChange> balanceChanges, boolean isOutput) {
        String address = components.getFullAddress();
        String paymentScriptHash = components.getPaymentScriptHash();
        String stakeKeyHash = components.getStakeKeyHash();

        // Process each asset in the UTxO
        amounts.forEach(amount -> {
            String unit = amount.getUnit();
            BigInteger quantity = amount.getQuantity();

            String policyId;
            String assetName;

            if ("lovelace".equals(unit)) {
                policyId = "ADA";
                assetName = null;
            } else {
                // Native asset: unit format is policyId + assetName
                // PolicyId is 56 hex chars (28 bytes)
                if (unit.length() >= 56) {
                    policyId = unit.substring(0, 56);
                    assetName = unit.length() > 56 ? unit.substring(56) : null;
                } else {
                    log.warn("Invalid asset unit format: {}", unit);
                    return;
                }
            }

            String key = address + "|" + policyId + "|" + assetName;

            BalanceChange change = balanceChanges.computeIfAbsent(key, k ->
                    new BalanceChange(address, paymentScriptHash, stakeKeyHash, policyId, assetName)
            );

            // Add if output, subtract if input
            if (isOutput) {
                change.netChange = change.netChange.add(quantity);
            } else {
                change.netChange = change.netChange.subtract(quantity);
            }
        });
    }

    private static class BalanceChange {
        String address;
        String paymentScriptHash;
        String stakeKeyHash;
        String policyId;
        String assetName;
        BigInteger netChange = BigInteger.ZERO;

        BalanceChange(String address, String paymentScriptHash, String stakeKeyHash,
                      String policyId, String assetName) {
            this.address = address;
            this.paymentScriptHash = paymentScriptHash;
            this.stakeKeyHash = stakeKeyHash;
            this.policyId = policyId;
            this.assetName = assetName;
        }
    }
}
