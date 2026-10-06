"""The facts this function understands, as the user service publishes them.

The names below are the contract with ``EventPublisherService``: they are the ``detail-type`` of the
EventBridge event, which is also what the rule filters on. They are repeated here instead of being
imported from anywhere, because the two projects are deployed separately and the string is the only
thing that ties them together.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Mapping

from .errors import InvalidEvent

#: The verification state of an account changed.
VERIFICATION_CHANGED = "USER_VERIFICATION_CHANGED"
#: An account became a professional.
BECAME_PROFESSIONAL = "USER_BECAME_PROFESSIONAL"
#: A professional went back to client.
BECAME_CLIENT = "PROFESSIONAL_BECAME_CLIENT"
#: The access of an account was taken away or given back.
ACTIVE_STATUS_CHANGED = "USER_ACTIVE_STATUS_CHANGED"
#: A professional moved which address represents it. The pool has nothing to do with this one.
PRINCIPAL_ADDRESS_CHANGED = "PROFESSIONAL_PRINCIPAL_ADDRESS_CHANGED"

#: Verification states, as the service names them.
VERIFIED_STATUS = "VERIFIED"
#: Roles, as the service names them.
PROFESSIONAL_ROLE = "PROFESSIONAL"

KNOWN_DETAIL_TYPES = frozenset(
    {
        VERIFICATION_CHANGED,
        BECAME_PROFESSIONAL,
        BECAME_CLIENT,
        ACTIVE_STATUS_CHANGED,
        PRINCIPAL_ADDRESS_CHANGED,
    }
)

_TRUTHY = {"true", "1", "yes", "on"}
_FALSY = {"false", "0", "no", "off"}


@dataclass(frozen=True)
class UserEvent:
    """One fact about one account.

    Only ``detail_type`` and ``aws_id`` are always present: the rest is read from the payload, and
    each route checks the field it needs.
    """

    detail_type: str
    aws_id: str
    user_email: str | None = None
    verification_status: str | None = None
    user_role: str | None = None
    is_active: bool | None = None
    address_alias: str | None = None


def parse(event: Mapping[str, Any]) -> UserEvent:
    """Turns the EventBridge envelope into the fact it carries.

    The ``detail`` arrives as an object, but the same payload travels as text when the event went
    through a queue or a topic, so both shapes are accepted.
    """
    if not isinstance(event, Mapping):
        raise InvalidEvent("The event is not an object")

    detail_type = event.get("detail-type") or event.get("detailType")
    if not isinstance(detail_type, str) or not detail_type.strip():
        raise InvalidEvent("The event carries no detail-type")

    detail = event.get("detail", {})
    if isinstance(detail, str):
        try:
            detail = json.loads(detail)
        except json.JSONDecodeError as broken:
            raise InvalidEvent("The detail is not valid JSON") from broken
    if not isinstance(detail, Mapping):
        raise InvalidEvent("The detail is not an object")

    aws_id = detail.get("awsId")
    if not isinstance(aws_id, str) or not aws_id.strip():
        raise InvalidEvent("The detail carries no awsId")

    return UserEvent(
        detail_type=detail_type.strip(),
        aws_id=aws_id.strip(),
        user_email=_text(detail.get("userEmail")),
        verification_status=_text(detail.get("verificationStatus")),
        user_role=_text(detail.get("userRole")),
        is_active=_flag(detail.get("isActive")),
        address_alias=_text(detail.get("addressAlias")),
    )


def _text(value: Any) -> str | None:
    return value.strip() if isinstance(value, str) and value.strip() else None


def _flag(value: Any) -> bool | None:
    """Reads a boolean, whether it travelled as a boolean or as text."""
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        folded = value.strip().lower()
        if folded in _TRUTHY:
            return True
        if folded in _FALSY:
            return False
    return None
