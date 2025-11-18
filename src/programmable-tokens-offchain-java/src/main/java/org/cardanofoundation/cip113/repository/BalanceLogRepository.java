package org.cardanofoundation.cip113.repository;

import org.cardanofoundation.cip113.entity.BalanceLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BalanceLogRepository extends JpaRepository<BalanceLogEntity, Long> {

    /**
     * Find the latest balance entry for a specific address and asset
     */
    @Query("SELECT b FROM BalanceLogEntity b WHERE b.address = :address " +
           "AND b.policyId = :policyId " +
           "AND (b.assetName = :assetName OR (b.assetName IS NULL AND :assetName IS NULL)) " +
           "ORDER BY b.slot DESC, b.id DESC")
    List<BalanceLogEntity> findLatestByAddressAndAsset(
            @Param("address") String address,
            @Param("policyId") String policyId,
            @Param("assetName") String assetName,
            Pageable pageable
    );

    /**
     * Find all latest balances for an address (one per asset)
     */
    @Query("SELECT b FROM BalanceLogEntity b WHERE b.address = :address " +
           "AND b.id IN (" +
           "  SELECT MAX(b2.id) FROM BalanceLogEntity b2 " +
           "  WHERE b2.address = :address " +
           "  GROUP BY b2.policyId, b2.assetName" +
           ") ORDER BY b.slot DESC")
    List<BalanceLogEntity> findAllLatestByAddress(@Param("address") String address);

    /**
     * Find balance history for a specific address and asset
     */
    @Query("SELECT b FROM BalanceLogEntity b WHERE b.address = :address " +
           "AND b.policyId = :policyId " +
           "AND (b.assetName = :assetName OR (b.assetName IS NULL AND :assetName IS NULL)) " +
           "ORDER BY b.slot DESC")
    List<BalanceLogEntity> findHistoryByAddressAndAsset(
            @Param("address") String address,
            @Param("policyId") String policyId,
            @Param("assetName") String assetName,
            Pageable pageable
    );

    /**
     * Find all balance entries for an address (all assets)
     */
    @Query("SELECT b FROM BalanceLogEntity b WHERE b.address = :address ORDER BY b.slot DESC")
    List<BalanceLogEntity> findAllByAddressOrderBySlotDesc(
            @Param("address") String address,
            Pageable pageable
    );

    /**
     * Find latest balances by payment script hash
     */
    @Query("SELECT b FROM BalanceLogEntity b WHERE b.paymentScriptHash = :paymentScriptHash " +
           "AND b.id IN (" +
           "  SELECT MAX(b2.id) FROM BalanceLogEntity b2 " +
           "  WHERE b2.paymentScriptHash = :paymentScriptHash " +
           "  GROUP BY b2.address, b2.policyId, b2.assetName" +
           ") ORDER BY b.slot DESC")
    List<BalanceLogEntity> findAllLatestByPaymentScriptHash(@Param("paymentScriptHash") String paymentScriptHash);

    /**
     * Find latest balances by stake key hash
     */
    @Query("SELECT b FROM BalanceLogEntity b WHERE b.stakeKeyHash = :stakeKeyHash " +
           "AND b.id IN (" +
           "  SELECT MAX(b2.id) FROM BalanceLogEntity b2 " +
           "  WHERE b2.stakeKeyHash = :stakeKeyHash " +
           "  GROUP BY b2.address, b2.policyId, b2.assetName" +
           ") ORDER BY b.slot DESC")
    List<BalanceLogEntity> findAllLatestByStakeKeyHash(@Param("stakeKeyHash") String stakeKeyHash);

    /**
     * Find latest balances by payment script hash and stake key hash
     */
    @Query("SELECT b FROM BalanceLogEntity b WHERE b.paymentScriptHash = :paymentScriptHash " +
           "AND b.stakeKeyHash = :stakeKeyHash " +
           "AND b.id IN (" +
           "  SELECT MAX(b2.id) FROM BalanceLogEntity b2 " +
           "  WHERE b2.paymentScriptHash = :paymentScriptHash " +
           "  AND b2.stakeKeyHash = :stakeKeyHash " +
           "  GROUP BY b2.address, b2.policyId, b2.assetName" +
           ") ORDER BY b.slot DESC")
    List<BalanceLogEntity> findAllLatestByPaymentScriptHashAndStakeKeyHash(
            @Param("paymentScriptHash") String paymentScriptHash,
            @Param("stakeKeyHash") String stakeKeyHash
    );

    /**
     * Find balance entries by transaction hash
     */
    @Query("SELECT b FROM BalanceLogEntity b WHERE b.txHash = :txHash ORDER BY b.address, b.policyId, b.assetName")
    List<BalanceLogEntity> findAllByTxHash(@Param("txHash") String txHash);

    /**
     * Find only programmable token balances for an address
     */
    @Query("SELECT b FROM BalanceLogEntity b WHERE b.address = :address " +
           "AND b.isProgrammableToken = true " +
           "AND b.id IN (" +
           "  SELECT MAX(b2.id) FROM BalanceLogEntity b2 " +
           "  WHERE b2.address = :address AND b2.isProgrammableToken = true " +
           "  GROUP BY b2.policyId, b2.assetName" +
           ") ORDER BY b.slot DESC")
    List<BalanceLogEntity> findProgrammableTokenBalancesByAddress(@Param("address") String address);

    /**
     * Check if balance entry exists for this combination
     */
    boolean existsByAddressAndPolicyIdAndAssetNameAndTxHash(
            String address, String policyId, String assetName, String txHash
    );
}
