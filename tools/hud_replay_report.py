#!/usr/bin/env python3
"""Compare manual TFT HUD labels with independently exported local OCR predictions.

No image reading, cloud, network, OCR, user identifiers, or uploads. The caller
must establish data provenance; this script does not measure a screenshot itself.
"""
import argparse
import csv
import sys
from dataclasses import dataclass

FIELDS = ("stage", "level", "gold", "hp")
STATUSES = frozenset(("READABLE", "UNREADABLE", "AMBIGUOUS", "ABSENT"))
LABEL_COLUMNS = frozenset({"case_id", "consent_confirmed", "private_data_redacted"} |
                          {f"{f}_{suffix}" for f in ("phase",) + FIELDS
                           for suffix in ("status", "value")})
PRED_COLUMNS = frozenset({"case_id", "stage", "level", "gold", "hp", "phase", "phase_source"})


@dataclass
class Metrics:
    tp: int = 0
    fp: int = 0
    fn: int = 0
    tn: int = 0
    correct: int = 0
    wrong: int = 0
    rejected: int = 0

    def record(self, truth, prediction):
        if truth is None:
            if prediction is None:
                self.tn += 1
                self.rejected += 1
            else:
                self.fp += 1
                self.wrong += 1
        elif prediction is None:
            self.fn += 1
            self.rejected += 1
        elif truth == prediction:
            self.tp += 1
            self.correct += 1
        else:
            self.fp += 1
            self.fn += 1
            self.wrong += 1

    @staticmethod
    def ratio(num, den):
        return "N/A" if den == 0 else f"{num / den:.4f} ({num}/{den})"

    def summary(self):
        return (f"correct={self.correct} wrong={self.wrong} rejected={self.rejected} "
                f"TP={self.tp} FP={self.fp} FN={self.fn} TN={self.tn} "
                f"precision={self.ratio(self.tp, self.tp + self.fp)} "
                f"recall={self.ratio(self.tp, self.tp + self.fn)}")


def read_rows(path, required):
    with open(path, newline="", encoding="utf-8-sig") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None or not required.issubset(reader.fieldnames):
            raise ValueError("CSV schema invalid or missing required columns")
        records = {}
        for index, row in enumerate(reader, 2):
            if None in row or any(row.get(column) is None for column in required):
                raise ValueError(f"Row {index}: invalid CSV columns")
            key = row["case_id"].strip()
            if not key or key in records:
                raise ValueError(f"Row {index}: missing or duplicate case_id")
            records[key] = row
    if not records:
        raise ValueError("No labelled cases: nothing to measure")
    return records


def value(row, field):
    val = row[field].strip()
    return val if val else None


def evaluate(labels, predictions):
    if set(labels) != set(predictions):
        raise ValueError("Case IDs differ between labels and predictions; refusing partial scoring")
    metrics = {field: Metrics() for field in FIELDS}
    phase_metrics = Metrics()
    independent_phase_cases = 0
    for key in sorted(labels):
        label, pred = labels[key], predictions[key]
        if label["consent_confirmed"].strip().lower() != "true" or \
                label["private_data_redacted"].strip().lower() != "true":
            raise ValueError("Consent or private-data redaction not confirmed; analysis refused")
        for field in ("phase",) + FIELDS:
            state = label[f"{field}_status"].strip().upper()
            truth = value(label, f"{field}_value")
            if state not in STATUSES or (state == "READABLE") != (truth is not None):
                raise ValueError(f"Invalid {field} label status or value")
            if field != "phase":
                metrics[field].record(truth, value(pred, field))
        phase_source = pred["phase_source"].strip().upper()
        if phase_source not in ("INDEPENDENT", "INJECTED", "NONE"):
            raise ValueError("phase_source must be INDEPENDENT, INJECTED or NONE")
        if phase_source == "INDEPENDENT":
            independent_phase_cases += 1
            phase_metrics.record(value(label, "phase_value"), value(pred, "phase"))
        elif value(pred, "phase") is not None and phase_source == "NONE":
            raise ValueError("phase is provided but phase_source is NONE")
        # INJECTED phase is never scored as recognition, even if it matches.
    return metrics, phase_metrics if independent_phase_cases else None, independent_phase_cases


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--labels", required=True, help="Local human labels CSV; never publish")
    parser.add_argument("--predictions", required=True, help="Local actual-device OCR export CSV")
    args = parser.parse_args(argv)
    try:
        labels = read_rows(args.labels, LABEL_COLUMNS)
        predictions = read_rows(args.predictions, PRED_COLUMNS)
        metrics, phase, phase_count = evaluate(labels, predictions)
    except (OSError, UnicodeError, csv.Error, ValueError) as exc:
        # Do not include input values, local file paths or case IDs in public logs.
        print(f"Refused to score: {type(exc).__name__}", file=sys.stderr)
        return 2
    print("LOCAL CSV COMPARISON ONLY — source/image provenance not independently verified")
    print(f"Cases={len(labels)}; phase-independent-cases={phase_count}")
    for field in FIELDS:
        print(f"{field.upper()}: {metrics[field].summary()}")
    print("PHASE: " + (phase.summary() if phase else "N/A (no independently detected phase)"))
    print("Not an OCR replay, device validation, or independently verified accuracy score.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
