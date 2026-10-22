import unittest
from house_brief import DEFAULT_PROMPT, parse_brief

class HouseBriefTest(unittest.TestCase):
    def test_default(self):
        self.assertEqual(parse_brief(DEFAULT_PROMPT).manifest(),
            dict(floors=2,wall_color="cream",roof_style="flat",garden=True))

    def test_single_floor_gable_no_garden(self):
        self.assertEqual(parse_brief("single-floor blue mini house with gable roof and no garden").manifest(),
            dict(floors=1,wall_color="blue",roof_style="gable",garden=False))

    def test_terracotta_two_storey_and_hinglish(self):
        self.assertEqual(parse_brief("two-storey terracotta ghar with pitched roof").floors, 2)
        self.assertEqual(parse_brief("Ek floor blue ghar with flat roof without garden").floors, 1)

    def test_invalid_conflicts_fail_closed(self):
        for prompt in ("Three floor house", "3 storey home", "one floor two floor house",
                       "blue cream home", "flat roof and pitched roof house"):
            with self.subTest(prompt=prompt), self.assertRaises(ValueError):
                parse_brief(prompt)

    def test_bad_input_is_rejected(self):
        for prompt in ("", "  ", "make a spaceship", "house\nunsafe", "house"*300):
            with self.subTest(prompt=prompt[:15]), self.assertRaises(ValueError):
                parse_brief(prompt)

    def test_deterministic_and_bounded_schema(self):
        prompt = "make a two floor cream house with flat roof and garden"
        self.assertEqual(parse_brief(prompt), parse_brief(prompt))
        self.assertEqual(set(parse_brief(prompt).manifest()),
                         {"floors","wall_color","roof_style","garden"})

if __name__=="__main__":
    unittest.main()
