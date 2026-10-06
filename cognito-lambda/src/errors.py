"""The two failures this function knows about."""

from __future__ import annotations


class InvalidEvent(Exception):
    """The event does not carry what the handler needs.

    It is raised instead of guessed: acting on a half-read event would write the wrong membership
    into the pool, and no retry would fix it.
    """


class CognitoSyncError(Exception):
    """Cognito refused a call and the configuration asks for the invocation to fail.

    Failing is what makes the event be retried, so a temporary refusal of the pool is not lost.
    """
