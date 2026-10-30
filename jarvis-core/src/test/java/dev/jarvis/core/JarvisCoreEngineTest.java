package dev.jarvis.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class JarvisCoreEngineTest {
    private static final class FakeControls implements LocalControlCapability.Controls {
        boolean muted;
        int cancellations;
        @Override public boolean isMuted() { return muted; }
        @Override public void setMuted(boolean value) { muted = value; }
        @Override public void cancelCurrentTask() { cancellations++; }
        @Override public String currentStatus() { return "JARVIS bereit"; }
    }

    private JarvisCoreEngine engine(FakeControls controls) {
        JarvisCoreEngine engine = new JarvisCoreEngine();
        engine.register(new LocalControlCapability(controls));
        return engine;
    }

    @Test public void statusDoesNotRequireTftOrCloud() {
        FakeControls c = new FakeControls();
        JarvisCoreEngine.Result result = engine(c).handle("  JARVIS   STATUS ");
        assertEquals(JarvisCoreEngine.Outcome.DONE, result.outcome);
        assertEquals("local-control", result.capability);
        assertEquals("JARVIS bereit", result.message);
    }

    @Test public void muteUnmuteAndCancelAreLocal() {
        FakeControls c = new FakeControls();
        JarvisCoreEngine core = engine(c);
        assertEquals(JarvisCoreEngine.Outcome.DONE, core.handle("STUMM").outcome);
        assertTrue(c.muted);
        core.handle("laut");
        assertFalse(c.muted);
        core.handle("Abbrechen");
        assertEquals(1, c.cancellations);
    }

    @Test public void unknownAndBlankCommandsRemainOfflineAndExplicit() {
        JarvisCoreEngine core = engine(new FakeControls());
        assertEquals(JarvisCoreEngine.Outcome.UNSUPPORTED, core.handle("TFT Shop lesen").outcome);
        assertEquals(JarvisCoreEngine.Outcome.UNSUPPORTED, core.handle(" ").outcome);
        assertEquals(JarvisCoreEngine.Outcome.UNSUPPORTED, core.handle(null).outcome);
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateCapabilityIdsRejected() {
        JarvisCoreEngine core = engine(new FakeControls());
        core.register(new LocalControlCapability(new FakeControls()));
    }

    @Test public void failuresAreRedacted() {
        JarvisCoreEngine core = new JarvisCoreEngine();
        core.register(new JarvisCoreEngine.Capability() {
            @Override public String id() { return "failing"; }
            @Override public boolean accepts(String input) { return true; }
            @Override public JarvisCoreEngine.Result execute(String input) {
                throw new IllegalStateException("secret-token-test");
            }
        });
        JarvisCoreEngine.Result result = core.handle("help");
        assertEquals(JarvisCoreEngine.Outcome.FAILED, result.outcome);
        assertFalse(result.message.contains("secret-token-test"));
    }
}
