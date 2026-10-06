"""Everything this function reads from its environment, in one place.

The names mirror the properties the user service used to have before the pool was moved out of it,
so a deployment that already knew them does not have to learn new ones.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

#: Group the platform grants to an account whose identity is verified.
DEFAULT_VERIFIED_GROUP = "VERIFIED"
#: Group the platform grants to an account that offers a service.
DEFAULT_PROFESSIONAL_GROUP = "PROFESSIONAL"


def _boolean(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    """Configuration of the synchronisation with the user pool.

    ``enabled`` is the switch the service used to have: with it off, the function does nothing at all
    instead of failing, which is what a deployment without a pool needs.

    ``strict`` decides what happens when Cognito refuses a call. Raising fails the invocation, so the
    event is retried and eventually lands in the dead-letter queue; logging and continuing leaves the
    pool behind the database without anybody noticing. This function defaults to raising, because an
    asynchronous consumer that swallows a failure is how the two sides drift apart.
    """

    user_pool_id: str
    verified_group: str = DEFAULT_VERIFIED_GROUP
    professional_group: str = DEFAULT_PROFESSIONAL_GROUP
    enabled: bool = True
    strict: bool = True

    @classmethod
    def from_environment(cls) -> "Settings":
        return cls(
            user_pool_id=os.environ.get("USER_POOL_ID", "").strip(),
            verified_group=os.environ.get("VERIFIED_GROUP", DEFAULT_VERIFIED_GROUP).strip(),
            professional_group=os.environ.get(
                "PROFESSIONAL_GROUP", DEFAULT_PROFESSIONAL_GROUP
            ).strip(),
            enabled=_boolean("COGNITO_SYNC_ENABLED", True),
            strict=_boolean("COGNITO_SYNC_STRICT", True),
        )

    @property
    def usable(self) -> bool:
        """Whether the pool can be reached at all: the switch is on and the pool is named."""
        return self.enabled and bool(self.user_pool_id)
