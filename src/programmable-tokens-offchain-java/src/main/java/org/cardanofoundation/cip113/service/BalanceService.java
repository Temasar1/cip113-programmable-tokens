package org.cardanofoundation.cip113.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.BalanceLogEntity;
import org.cardanofoundation.cip113.repository.BalanceLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class BalanceService {

    private final BalanceLogRepository repository;

    /**
     * Append a new balance entry to the log
     *
     * @param entity the balance log entry to append
     * @return the saved entity
     */
    @Transactional
    public BalanceLogEntity append(BalanceLogEntity entity) {
        // Check if entry already exists (idempotency)
        if (repository.existsByAddressAndPolicyIdAndAssetNameAndTxHash(
                entity.getAddress(),
                entity.getPolicyId(),
                entity.getAssetName(),
                entity.getTxHash()
        )) {
            log.debug("Balance entry already exists, skipping: address={}, asset={}/{}, tx={}",
                    entity.getAddress(), entity.getPolicyId(), entity.getAssetName(), entity.getTxHash());
            return entity;
        }

        log.info("Appending balance entry: address={}, asset={}/{}, quantity={}, tx={}",
                entity.getAddress(), entity.getPolicyId(), entity.getAssetName(),
                entity.getQuantity(), entity.getTxHash());

        return repository.save(entity);
    }

    /**
     * Get the latest balance for a specific address and asset
     *
     * @param address the address
     * @param policyId the asset policy ID
     * @param assetName the asset name (null for ADA)
     * @return the latest balance or empty if no history
     */
    public Optional<BalanceLogEntity> getLatestBalance(String address, String policyId, String assetName) {
        return repository.findLatestByAddressAndAsset(address, policyId, assetName, PageRequest.of(0, 1))
                .stream()
                .findFirst();
    }

    /**
     * Get all latest balances for an address (one per asset)
     *
     * @param address the address
     * @return list of latest balances
     */
    public List<BalanceLogEntity> getAllLatestBalances(String address) {
        return repository.findAllLatestByAddress(address);
    }

    /**
     * Get balance history for a specific address and asset
     *
     * @param address the address
     * @param policyId the asset policy ID
     * @param assetName the asset name (null for ADA)
     * @param limit maximum number of entries to return
     * @return list of balance entries (ordered by slot DESC)
     */
    public List<BalanceLogEntity> getBalanceHistory(String address, String policyId, String assetName, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return repository.findHistoryByAddressAndAsset(address, policyId, assetName, pageable);
    }

    /**
     * Get all balance entries for an address (all assets)
     *
     * @param address the address
     * @param limit maximum number of entries to return
     * @return list of balance entries (ordered by slot DESC)
     */
    public List<BalanceLogEntity> getAllBalanceHistory(String address, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return repository.findAllByAddressOrderBySlotDesc(address, pageable);
    }

    /**
     * Get latest balances by payment script hash
     *
     * @param paymentScriptHash the payment script hash
     * @return list of latest balances
     */
    public List<BalanceLogEntity> getLatestBalancesByPaymentScript(String paymentScriptHash) {
        return repository.findAllLatestByPaymentScriptHash(paymentScriptHash);
    }

    /**
     * Get latest balances by stake key hash
     *
     * @param stakeKeyHash the stake key hash
     * @return list of latest balances
     */
    public List<BalanceLogEntity> getLatestBalancesByStakeKey(String stakeKeyHash) {
        return repository.findAllLatestByStakeKeyHash(stakeKeyHash);
    }

    /**
     * Get latest balances by payment script hash and stake key hash
     *
     * @param paymentScriptHash the payment script hash
     * @param stakeKeyHash the stake key hash
     * @return list of latest balances
     */
    public List<BalanceLogEntity> getLatestBalancesByPaymentScriptAndStakeKey(
            String paymentScriptHash, String stakeKeyHash) {
        return repository.findAllLatestByPaymentScriptHashAndStakeKeyHash(paymentScriptHash, stakeKeyHash);
    }

    /**
     * Get all balance entries for a transaction
     *
     * @param txHash the transaction hash
     * @return list of balance entries
     */
    public List<BalanceLogEntity> getBalancesByTransaction(String txHash) {
        return repository.findAllByTxHash(txHash);
    }

    /**
     * Get only programmable token balances for an address
     *
     * @param address the address
     * @return list of programmable token balances
     */
    public List<BalanceLogEntity> getProgrammableTokenBalances(String address) {
        return repository.findProgrammableTokenBalancesByAddress(address);
    }

    /**
     * Calculate balance difference between current and previous entry
     *
     * @param currentEntry the current balance entry
     * @param previousEntry the previous balance entry (or null if first)
     * @return the balance difference (positive or negative)
     */
    public BigInteger calculateBalanceDiff(BalanceLogEntity currentEntry, BalanceLogEntity previousEntry) {
        if (previousEntry == null) {
            return currentEntry.getQuantity();
        }
        return currentEntry.getQuantity().subtract(previousEntry.getQuantity());
    }

    /**
     * Get previous balance entry for a given entry
     *
     * @param entry the current entry
     * @return the previous entry or empty if this is the first
     */
    public Optional<BalanceLogEntity> getPreviousBalance(BalanceLogEntity entry) {
        List<BalanceLogEntity> history = repository.findHistoryByAddressAndAsset(
                entry.getAddress(),
                entry.getPolicyId(),
                entry.getAssetName(),
                PageRequest.of(0, 2)
        );

        // Find the entry before this one
        for (BalanceLogEntity historyEntry : history) {
            if (!historyEntry.getId().equals(entry.getId())) {
                return Optional.of(historyEntry);
            }
        }

        return Optional.empty();
    }
}
