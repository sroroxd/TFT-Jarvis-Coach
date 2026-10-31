package dev.jarvis.core;

/** Local core commands; the host application supplies the actual device actions. */
public final class LocalControlCapability implements JarvisCoreEngine.Capability {
    public interface Controls {
        boolean isMuted();
        void setMuted(boolean muted);
        void cancelCurrentTask();
        String currentStatus();
    }

    private final Controls controls;

    public LocalControlCapability(Controls controls) {
        if (controls == null) throw new IllegalArgumentException("Controls required");
        this.controls = controls;
    }

    @Override public String id() { return "local-control"; }

    @Override public boolean accepts(String command) {
        switch (command) {
            case "hilfe":
            case "jarvis hilfe":
            case "status":
            case "jarvis status":
            case "stumm":
            case "jarvis stumm":
            case "laut":
            case "jarvis laut":
            case "abbrechen":
            case "jarvis abbrechen":
                return true;
            default:
                return false;
        }
    }

    @Override public JarvisCoreEngine.Result execute(String command) {
        if (command.startsWith("jarvis ")) command = command.substring("jarvis ".length());
        switch (command) {
            case "hilfe":
                return done("Verfügbare Befehle: Hilfe, Status, Stumm, Laut, Abbrechen.");
            case "status":
                String status = controls.currentStatus();
                return done(status == null || status.trim().isEmpty()
                        ? "Status nicht verfügbar." : status);
            case "stumm":
                controls.setMuted(true);
                return done("JARVIS ist stumm.");
            case "laut":
                controls.setMuted(false);
                return done("JARVIS-Sprachausgabe ist aktiviert.");
            case "abbrechen":
                controls.cancelCurrentTask();
                return done("Abbruch angefordert.");
            default:
                return JarvisCoreEngine.Result.unsupported("Unbekannter Befehl.");
        }
    }

    private JarvisCoreEngine.Result done(String message) {
        return JarvisCoreEngine.Result.done(id(), message);
    }
}
