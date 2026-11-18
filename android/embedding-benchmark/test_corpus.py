import unittest

from corpus import Note, Query, chunk_map, coverage, retrieval, split, validate_vectors


class CorpusTest(unittest.TestCase):
    def test_headings_inside_code_fences_do_not_change_sections(self):
        note = Note.from_text("fenced", "---\nkey: hidden\n---\n# Parent\n```sh\n# fake\n```\n正文𠀀\n")
        chunks = list(split(note, "sections", 100, 20))
        self.assertEqual(len(chunks), 1)
        chunk = chunks[0]
        self.assertEqual(note.source[chunk.start:chunk.end], "# Parent\n```sh\n# fake\n```\n正文𠀀\n")
        self.assertTrue(chunk.text.startswith("Parent\n"))

    def test_note_hit_and_evidence_hit_are_separate(self):
        anchor = "evidence at the end"
        note = Note.from_text("late", "背景" * 50 + "\n" + anchor)
        query = Query("test", "zh", "what happened", ("evidence",), {"late": (anchor,)})
        notes = {note.id: note}
        chunks = chunk_map(notes, "front", 32, 0)
        vectors = {"query:test": [1.0], chunks["late"][0].id: [1.0]}
        record, = retrieval(notes, [query], chunks, vectors)
        self.assertEqual(record["semantic"]["recallAt5"], 1)
        self.assertEqual(record["evidenceRecallAt5"], 0)
        self.assertEqual(coverage(notes, [query], chunks)["anchorsCovered"], 0)
        self.assertEqual(coverage(notes, [query], chunk_map(notes, "window", 32, 12))["anchorsCovered"], 1)

    def test_missing_and_invalid_vectors_fail_before_scoring(self):
        inputs = [{"id": "x"}]
        with self.assertRaisesRegex(ValueError, "IDs"):
            validate_vectors(inputs, {}, 2)
        with self.assertRaisesRegex(ValueError, "unit-normalized"):
            validate_vectors(inputs, {"x": [2.0, 2.0]}, 2)


if __name__ == "__main__":
    unittest.main()
