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
}
