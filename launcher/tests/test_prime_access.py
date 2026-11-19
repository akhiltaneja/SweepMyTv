import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location(
    "prime_access", Path(__file__).parents[1] / "catalog/admin/prime_access.py")
prime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(prime)


class PrimeAccessTest(unittest.TestCase):
    def test_membership_needs_the_entitlement_message(self):
        self.assertEqual("unconfirmed", prime.page_access("<p>Included with Prime</p>"))
        self.assertEqual("paid", prime.page_access(
            '<span data-automation-id="text-component">Rent UHD</span>'))
        self.assertEqual("membership", prime.page_access(
            '<span data-automation-id="entitlement-message">Watch with a Prime membership</span>'))

    def test_india_subscription_must_be_for_base_prime(self):
        base = {"provider_id": 119}
        self.assertFalse(prime.india_membership(
            {"results": {"US": {"flatrate": [base]}, "IN": {"rent": [base]}}}))
        self.assertFalse(prime.india_membership({"results": {"IN": {"flatrate": [{"provider_id": 10}]}}}))
        self.assertTrue(prime.india_membership({"results": {"IN": {"flatrate": [base]}}}))

    def test_paid_link_stays_blocked_even_when_another_offer_has_membership(self):
        rows = [{"provider": "prime", "link": link, "kind": "movie", "_metadata_tmdb_id": 42}
                for link in ("rent", "membership")]
        prime.check_offers(rows, lambda: lambda _: {"results": {"IN": {"flatrate": [{"provider_id": 119}]}}},
                           page_check=lambda link: "paid" if link == "rent" else "membership")
        self.assertEqual(["paid", "included"], [row["_prime_access"] for row in rows])
