package com.pete.tftjarvis;

import org.junit.Test;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

public class EvidenceLedgerTest {
    private void record(EvidenceLedger ledger, String value, EvidenceLedger.Source source, long at) throws Exception {
        Method m = EvidenceLedger.class.getDeclaredMethod("upsert", String.class, Object.class,
                EvidenceLedger.Source.class, String.class, long.class);
        m.setAccessible(true);
        m.invoke(ledger, "gold", value, source, "MITTEL", at);
    }

    @Test public void repeatedLocalReadingsAreNotIndependentVerification() throws Exception {
        EvidenceLedger ledger = new EvidenceLedger();
        long now = System.currentTimeMillis();
        record(ledger, "20", EvidenceLedger.Source.LOCAL_OCR, now);
        record(ledger, "20", EvidenceLedger.Source.LOCAL_OCR, now + 100);
        assertTrue(ledger.debugSummary().contains("0 bestätigt"));
    }

    @Test public void independentAgreementIsVerified() throws Exception {
        EvidenceLedger ledger = new EvidenceLedger();
        long now = System.currentTimeMillis();
        record(ledger, "20", EvidenceLedger.Source.LOCAL_OCR, now);
        record(ledger, "20", EvidenceLedger.Source.CLOUD_VISION, now + 100);
        assertTrue(ledger.debugSummary().contains("1 bestätigt"));
    }

    @Test public void conflictingCloudValueIsRejected() throws Exception {
        EvidenceLedger ledger = new EvidenceLedger();
        long now = System.currentTimeMillis();
        record(ledger, "20", EvidenceLedger.Source.LOCAL_OCR, now);
        record(ledger, "70", EvidenceLedger.Source.CLOUD_VISION, now + 100);
        assertTrue(ledger.hasFreshConflict());
        assertTrue(ledger.toPromptContext().contains("gold=20"));
    }

    @Test public void cloudAndLocalConflictIsDetectedInEitherOrder() throws Exception {
        EvidenceLedger ledger = new EvidenceLedger();
        long now = System.currentTimeMillis();
        record(ledger, "55", EvidenceLedger.Source.CLOUD_VISION, now);
        record(ledger, "25", EvidenceLedger.Source.LOCAL_OCR, now);
        assertTrue(ledger.hasFreshConflict());
        assertTrue(ledger.toPromptContext().contains("gold=25"));
        assertFalse(ledger.toPromptContext().contains("gold=55"));
    }

    @Test public void lateCloudResponseRetainsFrameCaptureTime() throws Exception {
        EvidenceLedger ledger = new EvidenceLedger();
        long frameAt = System.currentTimeMillis() - 12_000L;
        LocalHudPerception.Observation local = new LocalHudPerception.Observation(
                null, new LocalHudPerception.Field<>(30, "MITTEL"),
                null, null, frameAt);
        ledger.recordLocal(local);
        ledger.recordCloud(cloudWithGold(30), frameAt);
        assertTrue(ledger.debugSummary().contains("1 bestätigt"));
        assertTrue(ledger.toPromptContext().contains("12s"));
    }

    @Test public void delayedOldCloudCannotOverwriteNewLocalReading() throws Exception {
        EvidenceLedger ledger = new EvidenceLedger();
        long now = System.currentTimeMillis();
        record(ledger, "30", EvidenceLedger.Source.LOCAL_OCR, now);
        record(ledger, "80", EvidenceLedger.Source.CLOUD_VISION, now - 12_000L);
        assertFalse(ledger.hasFreshConflict());
        assertTrue(ledger.toPromptContext().contains("gold=30"));
        assertFalse(ledger.toPromptContext().contains("gold=80"));
    }

    @Test public void cloudContradictionAfterSlowReplyIsStillDetected() {
        EvidenceLedger ledger = new EvidenceLedger();
        long frameAt = System.currentTimeMillis() - 12_000L;
        LocalHudPerception.Observation local = new LocalHudPerception.Observation(
                null, new LocalHudPerception.Field<>(30, "MITTEL"),
                null, null, frameAt);
        ledger.recordLocal(local);
        ledger.recordCloud(cloudWithGold(80), frameAt);
        assertTrue(ledger.hasFreshConflict());
        assertTrue(ledger.toPromptContext().contains("gold=30"));
    }

    private static OpenAIClient.CoachResult cloudWithGold(int gold) {
        return new OpenAIClient.CoachResult(
                "PLANUNG", "", "", "", false, "INFO", "MITTEL",
                -1, gold, -1,
                null, null, null, null, null, null, null,
                "", "", "", "", "", "NIEDRIG", false, ""
        );
    }
}
