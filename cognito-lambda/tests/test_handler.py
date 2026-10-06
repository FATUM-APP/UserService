"""The routing: which fact means which change in the pool."""

import unittest
from unittest.mock import MagicMock

from src import events, handler
from src.errors import InvalidEvent


def envelope(detail_type, **detail):
    return {
        "source": "fatum.userservice",
        "detail-type": detail_type,
        "detail": {"awsId": "aws-1", **detail},
    }


class HandlerTest(unittest.TestCase):

    def setUp(self):
        self.directory = MagicMock()

    def apply(self, detail_type, **detail):
        return handler.handle(envelope(detail_type, **detail), self.directory)

    def test_a_verified_account_gets_the_group(self):
        result = self.apply(events.VERIFICATION_CHANGED, verificationStatus="VERIFIED")

        self.directory.grant_verified.assert_called_once_with("aws-1")
        self.assertEqual(result["actions"], ["grant-verified"])

    def test_an_account_that_stops_being_verified_loses_the_group(self):
        self.apply(events.VERIFICATION_CHANGED, verificationStatus="REJECTED")

        self.directory.revoke_verified.assert_called_once_with("aws-1")
        self.directory.grant_verified.assert_not_called()

    def test_a_verification_event_without_a_status_is_refused(self):
        with self.assertRaises(InvalidEvent):
            self.apply(events.VERIFICATION_CHANGED)

        self.assertEqual(self.directory.method_calls, [])

    def test_becoming_a_professional_grants_the_group(self):
        result = self.apply(events.BECAME_PROFESSIONAL, userRole="PROFESSIONAL")

        self.directory.grant_professional.assert_called_once_with("aws-1")
        self.assertEqual(result["actions"], ["grant-professional"])

    def test_going_back_to_client_takes_the_group_away(self):
        self.apply(events.BECAME_CLIENT, userRole="CLIENT")

        self.directory.revoke_professional.assert_called_once_with("aws-1")

    def test_a_deactivation_revokes_everything(self):
        result = self.apply(events.ACTIVE_STATUS_CHANGED, isActive=False, userRole="CLIENT")

        self.directory.revoke_access.assert_called_once_with("aws-1")
        self.directory.restore_access.assert_not_called()
        self.assertIn("sign-out", result["actions"])

    def test_an_activation_restores_the_groups_of_the_role(self):
        self.apply(events.ACTIVE_STATUS_CHANGED, isActive=True, userRole="PROFESSIONAL")

        self.directory.restore_access.assert_called_once_with("aws-1", is_professional=True)

    def test_an_activated_client_comes_back_as_a_client(self):
        self.apply(events.ACTIVE_STATUS_CHANGED, isActive=True, userRole="CLIENT")

        self.directory.restore_access.assert_called_once_with("aws-1", is_professional=False)

    def test_an_access_event_without_the_flag_is_refused(self):
        with self.assertRaises(InvalidEvent):
            self.apply(events.ACTIVE_STATUS_CHANGED, userRole="CLIENT")

        self.assertEqual(self.directory.method_calls, [])

    def test_the_principal_address_is_not_a_membership(self):
        result = self.apply(events.PRINCIPAL_ADDRESS_CHANGED, addressAlias="casa")

        self.assertEqual(result["actions"], [])
        self.assertEqual(self.directory.method_calls, [])

    def test_an_unknown_event_does_nothing(self):
        result = self.apply("SOMETHING_ELSE", whatever="x")

        self.assertEqual(result["actions"], [])
        self.assertEqual(self.directory.method_calls, [])

    def test_the_answer_names_the_fact_that_was_applied(self):
        result = self.apply(events.BECAME_PROFESSIONAL, userRole="PROFESSIONAL")

        self.assertEqual(result["status"], "applied")
        self.assertEqual(result["detailType"], events.BECAME_PROFESSIONAL)
