# cognito-lambda

Consumidor de los eventos que publica el servicio de usuarios. Es el dueño del grupo de usuarios de
Cognito: es lo único que lo escribe.

El servicio conserva la fuente de verdad, que es su base de datos, y anuncia lo que cambió. Esta
función escucha y hace la parte del grupo, que es el trabajo que antes hacían `CognitoGroupService` y
`CognitoUserService` dentro del servicio. Una caída de Cognito ya no queda en medio de una petición, y
el servicio dejó de saber que Cognito existe.

## Qué hace

| `detail-type` | `detail` | Qué pasa en el grupo de usuarios |
| --- | --- | --- |
| `USER_VERIFICATION_CHANGED` | `verificationStatus` | `VERIFIED` → se añade al grupo de verificado. Cualquier otro estado → se retira de él |
| `USER_BECAME_PROFESSIONAL` | `userRole` | Se añade al grupo de profesional |
| `PROFESSIONAL_BECAME_CLIENT` | `userRole` | Se retira del grupo de profesional |
| `USER_ACTIVE_STATUS_CHANGED` | `isActive`, `userRole` | `false` → se desactiva, se cierran todas sus sesiones y se vacían sus grupos. `true` → se activa y se conceden otra vez los grupos del rol |
| `PROFESSIONAL_PRINCIPAL_ADDRESS_CHANGED` | `addressAlias` | Nada: qué dirección representa a un profesional no es una pertenencia. La regla ni siquiera lo reenvía |

Todas las llamadas son idempotentes, así que un evento reintentado no hace daño.

El `awsId` del detalle es el `sub` del token y se usa como `Username` de las llamadas de
administración. Eso funciona mientras el grupo esté configurado con el `sub` como nombre de usuario,
que es como está construido este pool; si eso cambia, la función necesita el atributo de nombre de
usuario en su lugar.

## Configuración

| Variable | Por defecto | Qué es |
| --- | --- | --- |
| `USER_POOL_ID` | — | El grupo que escribe esta función. Sin él no se llama a nada |
| `VERIFIED_GROUP` | `VERIFIED` | Grupo de una identidad verificada |
| `PROFESSIONAL_GROUP` | `PROFESSIONAL` | Grupo de una cuenta que ofrece un servicio |
| `COGNITO_SYNC_ENABLED` | `true` | Apagado, la función no hace nada, que es lo que necesita un despliegue sin grupo |
| `COGNITO_SYNC_STRICT` | `true` | Qué significa un rechazo de Cognito: fallar, para que el evento se reintente y acabe en la cola de fallos, o registrarlo y seguir |

`COGNITO_SYNC_STRICT` aquí viene en `true`, al contrario que en el servicio, que registraba y seguía.
En un consumidor asíncrono, tragarse un fallo es como el grupo y la base de datos se separan sin que
nadie se entere.

## Estructura

```
src/
  handler.py            punto de entrada y enrutado: qué hecho significa qué cambio
  cognito_directory.py  las llamadas al grupo, en el orden en que las hacía el servicio
  events.py             el contrato con el publicador y la lectura del evento
  settings.py           el entorno, leído una vez por contenedor
  errors.py             los dos fallos que conoce
tests/                  el enrutado, la lectura y las llamadas, sin AWS
template.yaml           la función, la regla, los permisos y la cola de fallos
```

## Pruebas

Sin AWS y sin credenciales: el cliente es un mock, así que las pruebas describen las llamadas que se
harían.

```bash
cd cognito-lambda
python3 -m unittest discover -s tests -t . -v
```

Con cobertura:

```bash
pip install -r requirements-dev.txt
python3 -m coverage run --source=src -m unittest discover -s tests -t .
python3 -m coverage report -m
```

## Despliegue

```bash
cd cognito-lambda
sam build
sam deploy --guided \
  --parameter-overrides UserPoolId=<tu-pool> EventBusName=<tu-bus>
```

`sam deploy --guided` pregunta una vez y escribe `samconfig.toml`; después basta con `sam deploy`.

Dos cosas que la plantilla no hace porque dependen de cómo se despliegue la plataforma: hay que dar
permiso al servicio de usuarios para `events:PutEvents` en el bus, y el grupo de usuarios tiene que
existir antes de desplegar esta pila.

## Qué no hay aquí

Nada de esta función lee la base de datos y nada de ella decide una regla de negocio: las reglas
llegan decididas, en el evento. Es a propósito, para que los dos lados se desplieguen por separado y
lo único que compartan sea el contrato de `src/events.py`.
