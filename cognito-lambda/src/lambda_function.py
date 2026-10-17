"""Lambda que sincroniza el User Pool de Cognito con los eventos que publica el user service.

El user service es dueño de la verdad (su base de datos) y solo anuncia hechos por EventBridge.
Esta funcion escucha esos eventos y escribe en Cognito lo que corresponda: grupos, estado de la
cuenta y cierre de sesion. Nada aqui decide reglas de negocio, solo aplica lo que ya viene decidido
en el evento.

Se asume que la regla de EventBridge ya filtra que "detail-type" llegan a este Lambda, asi que no
se valida de mas: si falta un campo esperado, se deja fallar con un error claro en el log y la
invocacion se reintenta (eventualmente cae en la dead-letter queue si hay una configurada).
"""

from __future__ import annotations

import json
import logging
import os
from typing import Any, Mapping

import boto3
from botocore.exceptions import ClientError

logger = logging.getLogger()
logger.setLevel(logging.INFO)

# ---------------------------------------------------------------------------
# Configuracion (variables de entorno)
# ---------------------------------------------------------------------------

USER_POOL_ID = os.environ.get("USER_POOL_ID", "").strip()
VERIFIED_GROUP = os.environ.get("VERIFIED_GROUP", "VERIFIED").strip()
PROFESSIONAL_GROUP = os.environ.get("PROFESSIONAL_GROUP", "PROFESSIONAL").strip()

# ---------------------------------------------------------------------------
# Nombres de evento, tal como los publica EventPublisherService
# ---------------------------------------------------------------------------

VERIFICATION_CHANGED = "USER_VERIFICATION_CHANGED"
BECAME_PROFESSIONAL = "USER_BECAME_PROFESSIONAL"
BECAME_CLIENT = "PROFESSIONAL_BECAME_CLIENT"
ACTIVE_STATUS_CHANGED = "USER_ACTIVE_STATUS_CHANGED"
PRINCIPAL_ADDRESS_CHANGED = "PROFESSIONAL_PRINCIPAL_ADDRESS_CHANGED"

VERIFIED_STATUS = "VERIFIED"
PROFESSIONAL_ROLE = "PROFESSIONAL"

_client = None


def _cognito():
    """Un solo cliente por contenedor, creado en la primera invocacion."""
    global _client
    if _client is None:
        _client = boto3.client("cognito-idp")
    return _client


# ---------------------------------------------------------------------------
# Llamadas a Cognito
# ---------------------------------------------------------------------------

def _call(action: str, aws_id: str, fn, **kwargs) -> Any:
    try:
        return fn(**kwargs)
    except ClientError as err:
        logger.error("No se pudo %s para %s: %s", action, aws_id, err)
        raise


def add_to_group(aws_id: str, group: str) -> None:
    if not group:
        return
    _call(
        "agregar al grupo",
        aws_id,
        _cognito().admin_add_user_to_group,
        UserPoolId=USER_POOL_ID,
        Username=aws_id,
        GroupName=group,
    )
    logger.info("Usuario %s agregado al grupo %s", aws_id, group)


def remove_from_group(aws_id: str, group: str) -> None:
    if not group:
        return
    _call(
        "quitar del grupo",
        aws_id,
        _cognito().admin_remove_user_from_group,
        UserPoolId=USER_POOL_ID,
        Username=aws_id,
        GroupName=group,
    )
    logger.info("Usuario %s quitado del grupo %s", aws_id, group)


def remove_from_all_groups(aws_id: str) -> None:
    """Limpia todas las membresias del usuario, paginando hasta el final."""
    next_token = None
    while True:
        kwargs: dict[str, Any] = {"UserPoolId": USER_POOL_ID, "Username": aws_id}
        if next_token:
            kwargs["NextToken"] = next_token
        page = _call("listar los grupos del usuario", aws_id, _cognito().admin_list_groups_for_user, **kwargs)
        for group in page.get("Groups", []):
            name = group.get("GroupName")
            if name:
                remove_from_group(aws_id, name)
        next_token = page.get("NextToken")
        if not next_token:
            return


def disable_user(aws_id: str) -> None:
    _call("deshabilitar", aws_id, _cognito().admin_disable_user, UserPoolId=USER_POOL_ID, Username=aws_id)
    _call(
        "cerrar sesion global",
        aws_id,
        _cognito().admin_user_global_sign_out,
        UserPoolId=USER_POOL_ID,
        Username=aws_id,
    )
    logger.info("Usuario %s deshabilitado y con sesiones cerradas", aws_id)


def enable_user(aws_id: str) -> None:
    _call("habilitar", aws_id, _cognito().admin_enable_user, UserPoolId=USER_POOL_ID, Username=aws_id)
    logger.info("Usuario %s habilitado", aws_id)


# ---------------------------------------------------------------------------
# Ruteo: un evento -> que se hace en Cognito
# ---------------------------------------------------------------------------

def lambda_handler(event: Mapping[str, Any], context: Any = None) -> dict[str, Any]:
    detail_type = event.get("detail-type") or event.get("detailType")

    detail = event.get("detail", {})
    if isinstance(detail, str):
        detail = json.loads(detail)

    aws_id = detail.get("awsId")
    logger.info("Evento %s para %s", detail_type, aws_id)

    if detail_type == VERIFICATION_CHANGED:
        verification_status = detail.get("verificationStatus")
        if verification_status is None:
            raise ValueError(f"Evento {detail_type} sin 'verificationStatus' (awsId={aws_id})")
        if verification_status == VERIFIED_STATUS:
            add_to_group(aws_id, VERIFIED_GROUP)
        else:
            remove_from_group(aws_id, VERIFIED_GROUP)

    elif detail_type == BECAME_PROFESSIONAL:
        add_to_group(aws_id, PROFESSIONAL_GROUP)

    elif detail_type == BECAME_CLIENT:
        remove_from_group(aws_id, PROFESSIONAL_GROUP)

    elif detail_type == ACTIVE_STATUS_CHANGED:
        is_active = detail.get("isActive")
        if is_active is None:
            raise ValueError(f"Evento {detail_type} sin 'isActive' (awsId={aws_id})")
        if is_active:
            enable_user(aws_id)
            add_to_group(aws_id, VERIFIED_GROUP)
            if detail.get("userRole") == PROFESSIONAL_ROLE:
                add_to_group(aws_id, PROFESSIONAL_GROUP)
        else:
            disable_user(aws_id)
            remove_from_all_groups(aws_id)

    elif detail_type == PRINCIPAL_ADDRESS_CHANGED:
        pass  # No le corresponde a Cognito.

    else:
        logger.warning("Evento %s sin manejador; se ignora", detail_type)

    return {"status": "ok", "detailType": detail_type}
