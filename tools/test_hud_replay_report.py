"""Deterministic synthetic checks for the offline CSV scorer; no real data."""
import unittest
import csv
import io
import tempfile
from contextlib import redirect_stdout
from pathlib import Path
from hud_replay_report import Metrics, evaluate, read_rows, main, LABEL_COLUMNS, PRED_COLUMNS


FIELDS = ("phase", "stage", "level", "gold", "hp")


def labelled(**kwargs):
    row = {"consent_confirmed": "true", "private_data_redacted": "true"}
    for field in FIELDS:
        selected = kwargs.get(field)
        row[field + "_status"] = "READABLE" if selected is not None else "ABSENT"
        row[field + "_value"] = "" if selected is None else selected
    return row


def predicted(**kwargs):
    row = {field: kwargs.get(field, "") for field in FIELDS}
    row["phase_source"] = kwargs.get("phase_source", "NONE")
    return row


class HudReplayMetricsTest(unittest.TestCase):
    def test_confusion_counts_and_injected_phase_not_scored(self):
        labels = {"A": labelled(phase="PLANUNG", stage="3-2", level="5", gold="12"),
                  "B": labelled(phase="KAMPF", hp="80")}
        preds = {"A": predicted(phase="PLANUNG", stage="3-2", level="6", phase_source="INJECTED"),
                 "B": predicted(stage="4-1", gold="30", phase="PLANUNG", phase_source="INDEPENDENT")}
        summary, phase, independent = evaluate(labels, preds)
        self.assertEqual((summary["stage"].tp, summary["stage"].fp, summary["stage"].tn), (1, 1, 0))
        self.assertEqual((summary["level"].fp, summary["level"].fn), (1, 1))
        self.assertEqual((summary["gold"].fp, summary["gold"].fn), (1, 1))
        self.assertEqual((summary["hp"].tn, summary["hp"].fn), (1, 1))
        self.assertEqual((independent, phase.fp, phase.fn), (1, 1, 1))

    def test_no_denominator_is_na(self):
        m = Metrics()
        m.record(None, None)
        self.assertIn("precision=N/A", m.summary())
        self.assertIn("recall=N/A", m.summary())
        self.assertEqual((m.tn, m.rejected), (1, 1))

    def test_no_partial_scoring(self):
        with self.assertRaises(ValueError):
            evaluate({"A": labelled()}, {"B": predicted()})

    def test_refuses_unredacted_data(self):
        x = labelled()
        x["private_data_redacted"] = "false"
        with self.assertRaises(ValueError):
            evaluate({"A": x}, {"A": predicted()})

    def test_refuses_mislabelled_unreadable_truth(self):
        x = labelled(stage="3-2")
        x["stage_status"] = "AMBIGUOUS"
        with self.assertRaises(ValueError):
            evaluate({"A": x}, {"A": predicted()})

    def test_refuses_unknown_phase_provenance(self):
        with self.assertRaises(ValueError):
            evaluate({"A": labelled()}, {"A": predicted(phase_source="cloud")})

    def test_injected_phase_not_recognition(self):
        scores, phase, n = evaluate({"A": labelled(phase="PLANUNG")},
                                    {"A": predicted(phase="PLANUNG", phase_source="INJECTED")})
        self.assertIsNone(phase)
        self.assertEqual(n, 0)
        self.assertEqual(scores["gold"].tn, 1)


    def test_duplicate_case_ids_in_csv_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "labels.csv"
            with path.open("w", newline="", encoding="utf-8") as handle:
                columns = sorted(LABEL_COLUMNS)
                writer = csv.DictWriter(handle, fieldnames=columns)
                writer.writeheader()
                row = {"case_id": "A", **labelled()}
                writer.writerow(row)
                writer.writerow(row)
            with self.assertRaises(ValueError):
                read_rows(path, LABEL_COLUMNS)

    def test_cli_csv_end_to_end_uses_aggregate_only(self):
        with tempfile.TemporaryDirectory() as directory:
            labels_path = Path(directory) / "labels.csv"
            predictions_path = Path(directory) / "predictions.csv"
            with labels_path.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(handle, fieldnames=sorted(LABEL_COLUMNS))
                writer.writeheader()
                writer.writerow({"case_id": "SYNTHETIC-PRIVATE-CASE", **labelled(stage="3-2")})
            with predictions_path.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(handle, fieldnames=sorted(PRED_COLUMNS))
                writer.writeheader()
                writer.writerow({"case_id": "SYNTHETIC-PRIVATE-CASE", **predicted(stage="3-2")})
            capture = io.StringIO()
            with redirect_stdout(capture):
                status = main(["--labels", str(labels_path), "--predictions", str(predictions_path)])
            self.assertEqual(status, 0)
            self.assertIn("STAGE: correct=1", capture.getvalue())
            self.assertIn("PHASE: N/A", capture.getvalue())
            self.assertNotIn("SYNTHETIC-PRIVATE-CASE", capture.getvalue())
            self.assertNotIn(str(directory), capture.getvalue())


if __name__ == "__main__":
    unittest.main()
