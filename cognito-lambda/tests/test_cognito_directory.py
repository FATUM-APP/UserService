"""The calls to the pool, and what each switch does to them."""

import unittest
from unittest.mock import MagicMock

from botocore.exceptions import ClientError

from src.cognito_directory import CognitoDirectory
from src.errors import CognitoSyncError
from src.settings import Settings

POOL = "us-east-1_test"


def refusal(operation="AdminAddUserToGroup"):
    return ClientError(
        {"Error": {"Code": "AccessDeniedException", "Message": "not authorized"}}, operation
    )


class CognitoDirectoryTest(unittest.TestCase):

    def setUp(self):
        self.client = MagicMock()
        self.directory = CognitoDirectory(self.client, Settings(user_pool_id=POOL))

    def test_grants_the_verified_group(self):
        self.directory.grant_verified("aws-1")

        self.client.admin_add_user_to_group.assert_called_once_with(
            UserPoolId=POOL, Username="aws-1", GroupName="VERIFIED"
        )

    def test_grants_the_professional_group(self):
        self.directory.grant_professional("aws-1")

        self.client.admin_add_user_to_group.assert_called_once_with(
            UserPoolId=POOL, Username="aws-1", GroupName="PROFESSIONAL"
        )

    def test_the_groups_are_configuration_and_not_names_in_the_code(self):
        directory = CognitoDirectory(
            self.client,
            Settings(user_pool_id=POOL, verified_group="IDENTITY_OK", professional_group="PRO"),
        )

        directory.grant_verified("aws-1")
        directory.grant_professional("aws-1")

        called = [call.kwargs["GroupName"] for call in self.client.admin_add_user_to_group.call_args_list]
        self.assertEqual(called, ["IDENTITY_OK", "PRO"])

    def test_removing_from_all_groups_follows_the_pagination(self):
        self.client.admin_list_groups_for_user.side_effect = [
            {"Groups": [{"GroupName": "VERIFIED"}], "NextToken": "page-2"},
            {"Groups": [{"GroupName": "PROFESSIONAL"}, {"GroupName": ""}]},
        ]

        removed = self.directory.remove_from_all_groups("aws-1")

        self.assertEqual(removed, ["VERIFIED", "PROFESSIONAL"])
        self.assertEqual(self.client.admin_list_groups_for_user.call_count, 2)
        self.assertEqual(self.client.admin_remove_user_from_group.call_count, 2)
        self.assertEqual(
            self.client.admin_list_groups_for_user.call_args_list[1].kwargs["NextToken"], "page-2"
        )

    def test_deactivation_disables_signs_out_and_clears_the_groups(self):
        self.client.admin_list_groups_for_user.return_value = {"Groups": [{"GroupName": "VERIFIED"}]}

        self.directory.revoke_access("aws-1")

        self.client.admin_disable_user.assert_called_once_with(UserPoolId=POOL, Username="aws-1")
        self.client.admin_user_global_sign_out.assert_called_once_with(
            UserPoolId=POOL, Username="aws-1"
        )
        self.client.admin_remove_user_from_group.assert_called_once_with(
            UserPoolId=POOL, Username="aws-1", GroupName="VERIFIED"
        )

    def test_restoring_access_enables_and_grants_the_groups_of_the_role(self):
        self.directory.restore_access("aws-1", is_professional=True)

        self.client.admin_enable_user.assert_called_once_with(UserPoolId=POOL, Username="aws-1")
        self.assertEqual(self.client.admin_add_user_to_group.call_count, 2)

    def test_a_client_comes_back_without_the_professional_group(self):
        self.directory.restore_access("aws-1", is_professional=False)

        self.assertEqual(self.client.admin_add_user_to_group.call_count, 1)

    def test_a_disabled_sync_makes_no_calls(self):
        directory = CognitoDirectory(
            self.client, Settings(user_pool_id=POOL, enabled=False)
        )

        directory.revoke_access("aws-1")
        directory.grant_verified("aws-1")

        self.assertEqual(self.client.method_calls, [])

    def test_a_pool_without_identifier_makes_no_calls(self):
        directory = CognitoDirectory(self.client, Settings(user_pool_id=""))

        directory.grant_verified("aws-1")
        directory.revoke_access("aws-1")

        self.assertEqual(self.client.method_calls, [])

    def test_a_refusal_is_logged_and_swallowed_when_the_sync_is_not_strict(self):
        self.client.admin_add_user_to_group.side_effect = refusal()
        directory = CognitoDirectory(self.client, Settings(user_pool_id=POOL, strict=False))

        directory.grant_verified("aws-1")

        self.client.admin_add_user_to_group.assert_called_once()

    def test_a_refusal_raises_when_the_sync_is_strict(self):
        self.client.admin_add_user_to_group.side_effect = refusal()

        with self.assertRaises(CognitoSyncError):
            self.directory.grant_verified("aws-1")

    def test_a_refusal_while_listing_the_groups_stops_the_cleanup(self):
        self.client.admin_list_groups_for_user.side_effect = refusal("AdminListGroupsForUser")

        with self.assertRaises(CognitoSyncError):
            self.directory.remove_from_all_groups("aws-1")

        self.client.admin_remove_user_from_group.assert_not_called()
