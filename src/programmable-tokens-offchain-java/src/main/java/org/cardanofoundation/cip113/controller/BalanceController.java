package org.cardanofoundation.cip113.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.BalanceLogEntity;
import org.cardanofoundation.cip113.service.BalanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("${apiPrefix}/balances")
@RequiredArgsConstructor
@Slf4j
public class BalanceController {

    private final BalanceService balanceService;

    /**
     * Get current balance for all assets at an address
     *
     * @param address the bech32 address
     * @return list of latest balances
     */
    @GetMapping("/current/{address}")
    public ResponseEntity<List<BalanceLogEntity>> getCurrentBalance(@PathVariable String address) {
        log.debug("GET /current/{} - fetching current balances", address);
        List<BalanceLogEntity> balances = balanceService.getAllLatestBalances(address);
        return ResponseEntity.ok(balances);
    }

    /**
     * Get current balance for a specific asset
     *
     * @param address the bech32 address
     * @param policyId the asset policy ID (or "ADA")
     * @param assetName the asset name (optional, null for ADA)
     * @return the latest balance
     */
    @GetMapping("/current/{address}/{policyId}")
    public ResponseEntity<BalanceLogEntity> getCurrentBalanceForAsset(
            @PathVariable String address,
            @PathVariable String policyId,
            @RequestParam(required = false) String assetName) {
        log.debug("GET /current/{}/{} - fetching balance for asset", address, policyId);
        return balanceService.getLatestBalance(address, policyId, assetName)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get current balances by payment script hash
     *
     * @param scriptHash the payment script hash
     * @return list of latest balances
     */
    @GetMapping("/current-by-payment/{scriptHash}")
    public ResponseEntity<List<BalanceLogEntity>> getCurrentBalanceByPaymentScript(
            @PathVariable String scriptHash) {
        log.debug("GET /current-by-payment/{} - fetching balances", scriptHash);
        List<BalanceLogEntity> balances = balanceService.getLatestBalancesByPaymentScript(scriptHash);
        return ResponseEntity.ok(balances);
    }

    /**
     * Get current balances by stake key hash
     *
     * @param stakeHash the stake key hash
     * @return list of latest balances
     */
    @GetMapping("/current-by-stake/{stakeHash}")
    public ResponseEntity<List<BalanceLogEntity>> getCurrentBalanceByStakeKey(
            @PathVariable String stakeHash) {
        log.debug("GET /current-by-stake/{} - fetching balances", stakeHash);
        List<BalanceLogEntity> balances = balanceService.getLatestBalancesByStakeKey(stakeHash);
        return ResponseEntity.ok(balances);
    }

    /**
     * Get current balances by payment script hash and stake key hash
     *
     * @param scriptHash the payment script hash
     * @param stakeHash the stake key hash
     * @return list of latest balances
     */
    @GetMapping("/current-by-payment-and-stake/{scriptHash}/{stakeHash}")
    public ResponseEntity<List<BalanceLogEntity>> getCurrentBalanceByPaymentScriptAndStakeKey(
            @PathVariable String scriptHash,
            @PathVariable String stakeHash) {
        log.debug("GET /current-by-payment-and-stake/{}/{} - fetching balances", scriptHash, stakeHash);
        List<BalanceLogEntity> balances = balanceService.getLatestBalancesByPaymentScriptAndStakeKey(
                scriptHash, stakeHash);
        return ResponseEntity.ok(balances);
    }

    /**
     * Get balance history for all assets at an address
     *
     * @param address the bech32 address
     * @param limit maximum number of entries (default 100)
     * @return list of balance entries
     */
    @GetMapping("/history/{address}")
    public ResponseEntity<List<BalanceLogEntity>> getBalanceHistory(
            @PathVariable String address,
            @RequestParam(defaultValue = "100") int limit) {
        log.debug("GET /history/{} - fetching balance history, limit={}", address, limit);
        List<BalanceLogEntity> history = balanceService.getAllBalanceHistory(address, limit);
        return ResponseEntity.ok(history);
    }

    /**
     * Get balance history for a specific asset
     *
     * @param address the bech32 address
     * @param policyId the asset policy ID
     * @param assetName the asset name (optional)
     * @param limit maximum number of entries (default 100)
     * @return list of balance entries
     */
    @GetMapping("/history/{address}/{policyId}")
    public ResponseEntity<List<BalanceLogEntity>> getBalanceHistoryForAsset(
            @PathVariable String address,
            @PathVariable String policyId,
            @RequestParam(required = false) String assetName,
            @RequestParam(defaultValue = "100") int limit) {
        log.debug("GET /history/{}/{} - fetching balance history for asset, limit={}", address, policyId, limit);
        List<BalanceLogEntity> history = balanceService.getBalanceHistory(address, policyId, assetName, limit);
        return ResponseEntity.ok(history);
    }

    /**
     * Get transaction list with balance diffs
     *
     * @param address the bech32 address
     * @param limit maximum number of entries (default 100)
     * @return list of transactions with balance differences
     */
    @GetMapping("/transactions/{address}")
    public ResponseEntity<List<Map<String, Object>>> getTransactionsWithDiffs(
            @PathVariable String address,
            @RequestParam(defaultValue = "100") int limit) {
        log.debug("GET /transactions/{} - fetching transactions with diffs, limit={}", address, limit);

        List<BalanceLogEntity> history = balanceService.getAllBalanceHistory(address, limit);

        // Calculate diffs between consecutive entries
        List<Map<String, Object>> transactions = history.stream()
                .map(entry -> {
                    Map<String, Object> txData = new HashMap<>();
                    txData.put("entry", entry);

                    // Get previous balance to calculate diff
                    balanceService.getPreviousBalance(entry).ifPresentOrElse(
                            prevEntry -> {
                                BigInteger diff = balanceService.calculateBalanceDiff(entry, prevEntry);
                                txData.put("diff", diff);
                                txData.put("previousQuantity", prevEntry.getQuantity());
                            },
                            () -> {
                                txData.put("diff", entry.getQuantity());
                                txData.put("previousQuantity", BigInteger.ZERO);
                            }
                    );

                    return txData;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(transactions);
    }

    /**
     * Get balance changes for a specific transaction
     *
     * @param txHash the transaction hash
     * @return list of balance entries for this transaction
     */
    @GetMapping("/by-transaction/{txHash}")
    public ResponseEntity<List<BalanceLogEntity>> getBalancesByTransaction(@PathVariable String txHash) {
        log.debug("GET /by-transaction/{} - fetching balance changes", txHash);
        List<BalanceLogEntity> balances = balanceService.getBalancesByTransaction(txHash);
        return ResponseEntity.ok(balances);
    }

    /**
     * Get only programmable token balances for an address
     *
     * @param address the bech32 address
     * @return list of programmable token balances
     */
    @GetMapping("/programmable-only/{address}")
    public ResponseEntity<List<BalanceLogEntity>> getProgrammableTokenBalances(@PathVariable String address) {
        log.debug("GET /programmable-only/{} - fetching programmable token balances", address);
        List<BalanceLogEntity> balances = balanceService.getProgrammableTokenBalances(address);
        return ResponseEntity.ok(balances);
    }
}
