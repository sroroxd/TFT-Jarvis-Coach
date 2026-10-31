"""Deterministic synthetic checks for the offline CSV scorer; no real data."""
import unittest
from hud_replay_report import Metrics, evaluate


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


if __name__ == "__main__":
    unittest.main()
