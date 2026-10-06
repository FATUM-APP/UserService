"""The calls to the user pool, moved out of the user service.

This is the same work ``CognitoGroupService`` and ``CognitoUserService`` did inside the service, in
the same order: grant a group, take it away, empty every membership, disable an account and sign it
out, give the access back. The service no longer writes the pool; it announces the fact and this
function does the pool side.

Every method is idempotent on purpose. Cognito accepts adding a user to a group it already belongs
to and removing one that is not a member, so a retried event does no harm.
"""

from __future__ import annotations

import logging
from typing import Any, Callable, Sequence

from botocore.exceptions import ClientError

from .errors import CognitoSyncError
from .settings import Settings

logger = logging.getLogger(__name__)


class CognitoDirectory:
    """Writes the decisions of the platform into the user pool."""

    def __init__(self, client: Any, settings: Settings) -> None:
        self._client = client
        self._settings = settings

    # ------------------------------------------------------------------- groups

    def grant_verified(self, aws_id: str) -> None:
        """Adds the account to the group that marks a verified identity."""
        self.add_to_group(aws_id, self._settings.verified_group)

    def revoke_verified(self, aws_id: str) -> None:
        """Takes the verified group away when the identity stops being verified."""
        self.remove_from_group(aws_id, self._settings.verified_group)

    def grant_professional(self, aws_id: str) -> None:
        """Adds the account to the group that marks an account offering a service."""
        self.add_to_group(aws_id, self._settings.professional_group)

    def revoke_professional(self, aws_id: str) -> None:
        """Takes the professional group away when the account goes back to client."""
        self.remove_from_group(aws_id, self._settings.professional_group)

    def add_to_group(self, aws_id: str, group: str) -> None:
        if not self._settings.usable or not group:
            logger.debug("Skipping group %s for %s", group, aws_id)
            return
        self._call(
            "add the user to the group",
            aws_id,
            self._client.admin_add_user_to_group,
            UserPoolId=self._settings.user_pool_id,
            Username=aws_id,
            GroupName=group,
        )
        logger.info("User %s added to the Cognito group %s", aws_id, group)

    def remove_from_group(self, aws_id: str, group: str) -> None:
        if not self._settings.usable or not group:
            logger.debug("Skipping removal from group %s for %s", group, aws_id)
            return
        self._call(
            "remove the user from the group",
            aws_id,
            self._client.admin_remove_user_from_group,
            UserPoolId=self._settings.user_pool_id,
            Username=aws_id,
            GroupName=group,
        )
        logger.info("User %s removed from the Cognito group %s", aws_id, group)

    def remove_from_all_groups(self, aws_id: str) -> list[str]:
        """Empties the groups of the account and returns the ones that were removed.

        The listing is paginated, so the loop follows the token until the last page. Stopping at the
        first one would leave access behind for an account with many memberships. The groups are read
        instead of assumed, so a membership created outside the platform is removed as well.
        """
        if not self._settings.usable:
            logger.debug("Skipping the group cleanup for %s", aws_id)
            return []

        removed: list[str] = []
        next_token: str | None = None
        while True:
            request: dict[str, Any] = {
                "UserPoolId": self._settings.user_pool_id,
                "Username": aws_id,
            }
            if next_token:
                request["NextToken"] = next_token
            page = self._call(
                "list the groups of the user",
                aws_id,
                self._client.admin_list_groups_for_user,
                **request,
            )
            if page is None:
                return removed
            for group in page.get("Groups", []):
                name = group.get("GroupName")
                if name:
                    self.remove_from_group(aws_id, name)
                    removed.append(name)
            next_token = page.get("NextToken")
            if not next_token:
                return removed

    # ------------------------------------------------------------------ account

    def disable_user(self, aws_id: str) -> None:
        """Disables the account and kills its sessions.

        Disabling stops the next sign-in; the global sign-out is what ends the sessions that are
        already open, including the refresh tokens. One without the other leaves a way in.
        """
        if not self._settings.usable:
            logger.debug("Skipping disable for %s", aws_id)
            return
        self._call(
            "disable the user",
            aws_id,
            self._client.admin_disable_user,
            UserPoolId=self._settings.user_pool_id,
            Username=aws_id,
        )
        self._call(
            "sign the user out",
            aws_id,
            self._client.admin_user_global_sign_out,
            UserPoolId=self._settings.user_pool_id,
            Username=aws_id,
        )
        logger.info("User %s disabled and signed out of Cognito", aws_id)

    def enable_user(self, aws_id: str) -> None:
        if not self._settings.usable:
            logger.debug("Skipping enable for %s", aws_id)
            return
        self._call(
            "enable the user",
            aws_id,
            self._client.admin_enable_user,
            UserPoolId=self._settings.user_pool_id,
            Username=aws_id,
        )
        logger.info("User %s enabled in Cognito", aws_id)

    def revoke_access(self, aws_id: str) -> None:
        """Everything a deactivation means: no sign-in, no session, no membership."""
        self.disable_user(aws_id)
        self.remove_from_all_groups(aws_id)

    def restore_access(self, aws_id: str, is_professional: bool) -> None:
        """Gives the access back and puts the account in the groups its role implies.

        The role arrives in the event because this function has nothing else to read it from: the
        database belongs to the other service.
        """
        self.enable_user(aws_id)
        self.grant_verified(aws_id)
        if is_professional:
            self.grant_professional(aws_id)

    # ------------------------------------------------------------------ helpers

    def _call(self, action: str, aws_id: str, call: Callable[..., Any], **arguments: Any) -> Any:
        """Runs one call to Cognito and decides what a refusal means."""
        try:
            return call(**arguments)
        except ClientError as refusal:
            logger.error("The user %s could not %s: %s", aws_id, action, refusal)
            if self._settings.strict:
                raise CognitoSyncError(f"Could not {action} for {aws_id}") from refusal
            return None
