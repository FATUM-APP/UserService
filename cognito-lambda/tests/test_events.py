"""What the function accepts and what it refuses to read."""

import unittest

from src import events
from src.errors import InvalidEvent


def envelope(detail_type="USER_VERIFICATION_CHANGED", detail=None, **extra):
    event = {"source": "fatum.userservice", "detail": {"awsId": "aws-1", **(detail or {})}}
    if detail_type is not None:
        event["detail-type"] = detail_type
    event.update(extra)
    return event


class ParseEventTest(unittest.TestCase):

    def test_reads_the_verification_fact(self):
        fact = events.parse(
            envelope(detail={"verificationStatus": "VERIFIED", "userEmail": "ana@fatum.co"})
        )

        self.assertEqual(fact.detail_type, events.VERIFICATION_CHANGED)
        self.assertEqual(fact.aws_id, "aws-1")
        self.assertEqual(fact.verification_status, "VERIFIED")
        self.assertEqual(fact.user_email, "ana@fatum.co")

    def test_reads_the_detail_when_it_arrives_as_text(self):
        fact = events.parse(
            {
                "detail-type": "USER_BECAME_PROFESSIONAL",
                "detail": '{"awsId": "aws-1", "userRole": "PROFESSIONAL"}',
            }
        )

        self.assertEqual(fact.user_role, "PROFESSIONAL")

    def test_reads_the_active_flag(self):
        fact = events.parse(envelope(detail={"isActive": False, "userRole": "CLIENT"}))

        self.assertIs(fact.is_active, False)

    def test_reads_the_active_flag_written_as_text(self):
        fact = events.parse(envelope(detail={"isActive": "true"}))

        self.assertIs(fact.is_active, True)

    def test_a_missing_detail_type_is_rejected(self):
        with self.assertRaises(InvalidEvent):
            events.parse({"detail": {"awsId": "aws-1"}})

    def test_a_missing_identifier_is_rejected(self):
        with self.assertRaises(InvalidEvent):
            events.parse({"detail-type": "USER_VERIFICATION_CHANGED", "detail": {}})

    def test_an_empty_identifier_is_rejected(self):
        with self.assertRaises(InvalidEvent):
            events.parse(
                {"detail-type": "USER_VERIFICATION_CHANGED", "detail": {"awsId": "   "}}
            )

    def test_a_detail_that_is_not_json_is_rejected(self):
        with self.assertRaises(InvalidEvent):
            events.parse({"detail-type": "USER_VERIFICATION_CHANGED", "detail": "not json"})

    def test_the_contract_holds_the_five_facts_of_the_service(self):
        self.assertEqual(
            events.KNOWN_DETAIL_TYPES,
            {
                "USER_VERIFICATION_CHANGED",
                "USER_BECAME_PROFESSIONAL",
                "PROFESSIONAL_BECAME_CLIENT",
                "USER_ACTIVE_STATUS_CHANGED",
                "PROFESSIONAL_PRINCIPAL_ADDRESS_CHANGED",
            },
        )
