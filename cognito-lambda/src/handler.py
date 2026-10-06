"""The consumer of the events the user service publishes.

The service keeps the source of truth, which is its database, and announces the facts. This function
owns the user pool and is the only thing that writes it. Nothing here reads the database, and nothing
here decides a business rule: the rules arrived decided, in the event.
"""

from __future__ import annotations

import logging
from typing import Any, Mapping

import boto3

from . import events
from .cognito_directory import CognitoDirectory
from .errors import InvalidEvent
from .settings import Settings

logger = logging.getLogger(__name__)

_client: Any = None


def _directory(settings: Settings) -> CognitoDirectory:
    """One client per container, built on the first invocation and reused afterwards."""
    global _client
    if _client is None:
        _client = boto3.client("cognito-idp")
    return CognitoDirectory(_client, settings)


def lambda_handler(event: Mapping[str, Any], context: Any = None) -> dict[str, Any]:
    """Entry point of the function.

    A failure is raised, not swallowed: the invocation is retried and, when the retries run out, the
    event ends in the dead-letter queue. That is how a pool that was momentarily unreachable does not
    leave the two sides out of step.
    """
    settings = Settings.from_environment()
    if not settings.enabled:
        logger.warning("The pool sync is disabled; the event is ignored")
        return {"status": "disabled"}

    return handle(event, _directory(settings))


def handle(event: Mapping[str, Any], directory: CognitoDirectory) -> dict[str, Any]:
    """Applies one event and reports what it did.

    Kept apart from the entry point so it can be exercised without AWS, and so the routing below
    reads as the list of facts the platform publishes.
    """
    fact = events.parse(event)
    actions = _apply(fact, directory)
    logger.info(
        "Event %s for %s applied: %s", fact.detail_type, fact.aws_id, actions or "nothing to do"
    )
    return {"status": "applied", "detailType": fact.detail_type, "actions": actions}


def _apply(fact: events.UserEvent, directory: CognitoDirectory) -> list[str]:
    """The mapping between a fact and what the pool has to do about it."""
    if fact.detail_type == events.VERIFICATION_CHANGED:
        # A half-read event would take the group away from an account that is verified, so it fails
        # instead of being guessed.
        if fact.verification_status is None:
            raise InvalidEvent("The verification event carries no verificationStatus")
        if fact.verification_status == events.VERIFIED_STATUS:
            directory.grant_verified(fact.aws_id)
            return ["grant-verified"]
        # The identity stopped being verified, so the permission that came with it goes away. The
        # professional group is not touched here: the role changes have their own events.
        directory.revoke_verified(fact.aws_id)
        return ["revoke-verified"]

    if fact.detail_type == events.BECAME_PROFESSIONAL:
        directory.grant_professional(fact.aws_id)
        return ["grant-professional"]

    if fact.detail_type == events.BECAME_CLIENT:
        directory.revoke_professional(fact.aws_id)
        return ["revoke-professional"]

    if fact.detail_type == events.ACTIVE_STATUS_CHANGED:
        # Without the flag the safe reading is "broken event", not "deactivated": revoking the access
        # of somebody who is active cannot be undone by a retry.
        if fact.is_active is None:
            raise InvalidEvent("The active status event carries no isActive flag")
        if fact.is_active:
            directory.restore_access(
                fact.aws_id, is_professional=fact.user_role == events.PROFESSIONAL_ROLE
            )
            return ["enable-user", "restore-groups"]
        directory.revoke_access(fact.aws_id)
        return ["disable-user", "sign-out", "clear-groups"]

    if fact.detail_type == events.PRINCIPAL_ADDRESS_CHANGED:
        # Which address represents a professional is not a membership; the pool has nothing to do.
        return []

    logger.warning("The event %s has no handler; it is ignored", fact.detail_type)
    return []


__all__ = ["handle", "lambda_handler", "InvalidEvent"]
