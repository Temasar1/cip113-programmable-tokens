package org.cardanofoundation.cip113.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;
import java.time.LocalDateTime;

@Entity
@Table(name = "balance_log", indexes = {
    @Index(name = "idx_balance_address", columnList = "address"),
    @Index(name = "idx_balance_payment_script", columnList = "paymentScriptHash"),
    @Index(name = "idx_balance_stake_key", columnList = "stakeKeyHash"),
    @Index(name = "idx_balance_payment_stake", columnList = "paymentScriptHash, stakeKeyHash"),
    @Index(name = "idx_balance_tx_hash", columnList = "txHash"),
    @Index(name = "idx_balance_slot", columnList = "slot"),
    @Index(name = "idx_balance_policy", columnList = "policyId"),
    @Index(name = "idx_balance_addr_asset_slot", columnList = "address, policyId, assetName, slot")
}, uniqueConstraints = {
    @UniqueConstraint(name = "unique_balance_entry", columnNames = {"address", "policyId", "assetName", "txHash"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Address Information
    @Column(nullable = false, length = 200)
    private String address;

    @Column(nullable = false, length = 56)
    private String paymentScriptHash;

    @Column(length = 56)
    private String stakeKeyHash;

    // Transaction Context
    @Column(nullable = false, length = 64)
    private String txHash;

    @Column(nullable = false)
    private Long slot;

    @Column(nullable = false)
    private Long blockHeight;

    // Asset Information
    @Column(nullable = false, length = 56)
    private String policyId; // "ADA" for lovelace

    @Column(length = 128)
    private String assetName; // NULL for ADA

    // Balance State (after this transaction)
    @Column(nullable = false)
    private BigInteger quantity;

    // Asset Classification (from registry lookup)
    @Column(nullable = false)
    private Boolean isProgrammableToken;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
