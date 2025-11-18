package org.cardanofoundation.cip113.service;

import org.cardanofoundation.cip113.entity.BalanceLogEntity;
import org.cardanofoundation.cip113.repository.BalanceLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class BalanceServiceTest {

    @Autowired
    private BalanceLogRepository repository;

    private BalanceService service;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        service = new BalanceService(repository);
    }

    @Test
    void testAppendBalanceEntry() {
        // Given
        BalanceLogEntity entry = createBalanceEntry(
                "addr1test123",
                "ADA",
                null,
                BigInteger.valueOf(1000000),
                "tx1",
                100L
        );

        // When
        BalanceLogEntity saved = service.append(entry);

        // Then
        assertNotNull(saved.getId());
        assertEquals(BigInteger.valueOf(1000000), saved.getQuantity());
    }

    @Test
    void testAppendDuplicateIsIdempotent() {
        // Given
        BalanceLogEntity entry1 = createBalanceEntry(
                "addr1test123",
                "ADA",
                null,
                BigInteger.valueOf(1000000),
                "tx1",
                100L
        );

        // When
        service.append(entry1);
        service.append(entry1); // Same entry

        // Then - should only have one entry
        assertEquals(1, repository.count());
    }

    @Test
    void testGetLatestBalance() {
        // Given - create balance history
        String address = "addr1test123";
        service.append(createBalanceEntry(address, "ADA", null, BigInteger.valueOf(1000), "tx1", 100L));
        service.append(createBalanceEntry(address, "ADA", null, BigInteger.valueOf(2000), "tx2", 200L));
        service.append(createBalanceEntry(address, "ADA", null, BigInteger.valueOf(3000), "tx3", 300L));

        // When
        BalanceLogEntity latest = service.getLatestBalance(address, "ADA", null).orElseThrow();

        // Then - should return most recent
        assertEquals(BigInteger.valueOf(3000), latest.getQuantity());
        assertEquals("tx3", latest.getTxHash());
        assertEquals(300L, latest.getSlot());
    }

    @Test
    void testGetAllLatestBalances() {
        // Given - multiple assets
        String address = "addr1test123";
        service.append(createBalanceEntry(address, "ADA", null, BigInteger.valueOf(1000), "tx1", 100L));
        service.append(createBalanceEntry(address, "token1", "asset1", BigInteger.valueOf(100), "tx2", 200L));
        service.append(createBalanceEntry(address, "token2", "asset2", BigInteger.valueOf(50), "tx3", 300L));

        // Update ADA balance
        service.append(createBalanceEntry(address, "ADA", null, BigInteger.valueOf(2000), "tx4", 400L));

        // When
        List<BalanceLogEntity> latestBalances = service.getAllLatestBalances(address);

        // Then - should have latest entry for each asset
        assertEquals(3, latestBalances.size());

        BalanceLogEntity adaBalance = latestBalances.stream()
                .filter(b -> b.getPolicyId().equals("ADA"))
                .findFirst()
                .orElseThrow();
        assertEquals(BigInteger.valueOf(2000), adaBalance.getQuantity());
    }

    @Test
    void testGetBalanceHistory() {
        // Given
        String address = "addr1test123";
        service.append(createBalanceEntry(address, "ADA", null, BigInteger.valueOf(1000), "tx1", 100L));
        service.append(createBalanceEntry(address, "ADA", null, BigInteger.valueOf(2000), "tx2", 200L));
        service.append(createBalanceEntry(address, "ADA", null, BigInteger.valueOf(3000), "tx3", 300L));
        service.append(createBalanceEntry(address, "ADA", null, BigInteger.valueOf(4000), "tx4", 400L));

        // When
        List<BalanceLogEntity> history = service.getBalanceHistory(address, "ADA", null, 3);

        // Then - should return last 3 entries in DESC order
        assertEquals(3, history.size());
        assertEquals(BigInteger.valueOf(4000), history.get(0).getQuantity());
        assertEquals(BigInteger.valueOf(3000), history.get(1).getQuantity());
        assertEquals(BigInteger.valueOf(2000), history.get(2).getQuantity());
    }

    @Test
    void testCalculateBalanceDiff() {
        // Given
        BalanceLogEntity prev = createBalanceEntry("addr1", "ADA", null, BigInteger.valueOf(1000), "tx1", 100L);
        BalanceLogEntity current = createBalanceEntry("addr1", "ADA", null, BigInteger.valueOf(1500), "tx2", 200L);

        // When
        BigInteger diff = service.calculateBalanceDiff(current, prev);

        // Then
        assertEquals(BigInteger.valueOf(500), diff);
    }

    @Test
    void testCalculateBalanceDiffFirstEntry() {
        // Given
        BalanceLogEntity current = createBalanceEntry("addr1", "ADA", null, BigInteger.valueOf(1000), "tx1", 100L);

        // When - no previous entry
        BigInteger diff = service.calculateBalanceDiff(current, null);

        // Then - diff should equal current balance
        assertEquals(BigInteger.valueOf(1000), diff);
    }

    @Test
    void testGetPreviousBalance() {
        // Given
        String address = "addr1test123";
        BalanceLogEntity entry1 = service.append(createBalanceEntry(address, "ADA", null, BigInteger.valueOf(1000), "tx1", 100L));
        BalanceLogEntity entry2 = service.append(createBalanceEntry(address, "ADA", null, BigInteger.valueOf(2000), "tx2", 200L));
        BalanceLogEntity entry3 = service.append(createBalanceEntry(address, "ADA", null, BigInteger.valueOf(3000), "tx3", 300L));

        // When - get previous of entry3
        BalanceLogEntity previous = service.getPreviousBalance(entry3).orElseThrow();

        // Then
        assertEquals(entry2.getId(), previous.getId());
        assertEquals(BigInteger.valueOf(2000), previous.getQuantity());
    }

    @Test
    void testGetPreviousBalanceForFirstEntry() {
        // Given
        String address = "addr1test123";
        BalanceLogEntity entry1 = service.append(createBalanceEntry(address, "ADA", null, BigInteger.valueOf(1000), "tx1", 100L));

        // When - get previous of first entry
        var previous = service.getPreviousBalance(entry1);

        // Then - should be empty
        assertTrue(previous.isEmpty());
    }

    @Test
    void testGetLatestBalancesByPaymentScript() {
        // Given
        String paymentScript = "testScriptHash123";
        service.append(createBalanceEntryWithPayment("addr1", paymentScript, null, "ADA", null, BigInteger.valueOf(1000), "tx1", 100L));
        service.append(createBalanceEntryWithPayment("addr2", paymentScript, null, "ADA", null, BigInteger.valueOf(2000), "tx2", 200L));
        service.append(createBalanceEntryWithPayment("addr3", "otherScript", null, "ADA", null, BigInteger.valueOf(3000), "tx3", 300L));

        // When
        List<BalanceLogEntity> balances = service.getLatestBalancesByPaymentScript(paymentScript);

        // Then - should only return balances for specified payment script
        assertEquals(2, balances.size());
        assertTrue(balances.stream().allMatch(b -> b.getPaymentScriptHash().equals(paymentScript)));
    }

    @Test
    void testGetProgrammableTokenBalances() {
        // Given
        String address = "addr1test123";
        service.append(createBalanceEntryProgrammable(address, "ADA", null, BigInteger.valueOf(1000), false, "tx1", 100L));
        service.append(createBalanceEntryProgrammable(address, "token1", "asset1", BigInteger.valueOf(100), true, "tx2", 200L));
        service.append(createBalanceEntryProgrammable(address, "token2", "asset2", BigInteger.valueOf(50), false, "tx3", 300L));

        // When
        List<BalanceLogEntity> progTokens = service.getProgrammableTokenBalances(address);

        // Then - should only return programmable tokens
        assertEquals(1, progTokens.size());
        assertEquals("token1", progTokens.get(0).getPolicyId());
        assertTrue(progTokens.get(0).getIsProgrammableToken());
    }

    private BalanceLogEntity createBalanceEntry(String address, String policyId, String assetName,
                                                 BigInteger quantity, String txHash, Long slot) {
        return createBalanceEntryWithPayment(address, "paymentScript123", "stakeKey456",
                policyId, assetName, quantity, txHash, slot);
    }

    private BalanceLogEntity createBalanceEntryWithPayment(String address, String paymentScript, String stakeKey,
                                                            String policyId, String assetName,
                                                            BigInteger quantity, String txHash, Long slot) {
        return BalanceLogEntity.builder()
                .address(address)
                .paymentScriptHash(paymentScript)
                .stakeKeyHash(stakeKey)
                .policyId(policyId)
                .assetName(assetName)
                .quantity(quantity)
                .txHash(txHash)
                .slot(slot)
                .blockHeight(1000L)
                .isProgrammableToken(false)
                .build();
    }

    private BalanceLogEntity createBalanceEntryProgrammable(String address, String policyId, String assetName,
                                                             BigInteger quantity, boolean isProgrammable,
                                                             String txHash, Long slot) {
        return BalanceLogEntity.builder()
                .address(address)
                .paymentScriptHash("paymentScript123")
                .stakeKeyHash("stakeKey456")
                .policyId(policyId)
                .assetName(assetName)
                .quantity(quantity)
                .txHash(txHash)
                .slot(slot)
                .blockHeight(1000L)
                .isProgrammableToken(isProgrammable)
                .build();
    }
}
