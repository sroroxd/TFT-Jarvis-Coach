package com.pete.tftjarvis.core;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Small local-first command router. No Android, TFT, network or model dependency.
 * Unknown commands are reported; they are never silently sent to a cloud provider.
 */
public final class JarvisCoreEngine {
    public interface Capability {
        String id();
        boolean accepts(String command);
        Result execute(String command);
    }

    public enum Outcome { DONE, UNSUPPORTED, FAILED }

    public static final class Result {
        public final Outcome outcome;
        public final String capability;
        public final String message;

        private Result(Outcome outcome, String capability, String message) {
            this.outcome = outcome;
            this.capability = capability;
            this.message = message;
        }

        public static Result done(String capability, String message) {
            return new Result(Outcome.DONE, capability, message);
        }

        public static Result unsupported(String message) {
            return new Result(Outcome.UNSUPPORTED, "", message);
        }

        public static Result failed(String capability) {
            return new Result(Outcome.FAILED, capability, "Befehl konnte nicht ausgeführt werden.");
        }
    }

    private final List<Capability> capabilities = new CopyOnWriteArrayList<>();

    public synchronized void register(Capability capability) {
        if (capability == null || capability.id() == null
                || capability.id().trim().isEmpty()) {
            throw new IllegalArgumentException("Capability requires an ID");
        }
        for (Capability registered : capabilities) {
            if (registered.id().equals(capability.id())) {
                throw new IllegalArgumentException("Duplicate capability ID");
            }
        }
        capabilities.add(capability);
    }

    public Result handle(String utterance) {
        if (utterance == null || utterance.trim().isEmpty()) {
            return Result.unsupported("Bitte einen Befehl eingeben.");
        }
        String command = utterance.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        for (Capability capability : capabilities) {
            try {
                if (capability.accepts(command)) {
                    Result result = capability.execute(command);
                    return result != null ? result : Result.failed(capability.id());
                }
            } catch (RuntimeException ignored) {
                // Never return private exception bodies, credentials or provider details.
                return Result.failed(capability.id());
            }
        }
        return Result.unsupported("Noch kein passendes JARVIS-Modul verfügbar.");
    }
}
