# Fatum — cuentas y grupo de usuarios

Aquí viven dos proyectos. Se despliegan por separado y los une un solo contrato: los eventos que
publica el servicio y que consume la función.

```
userservice/      las cuentas: registro, perfil, direcciones y los archivos que les pertenecen
cognito-lambda/   el consumidor de los eventos: es el dueño del grupo de usuarios
```

## userservice

Servicio Spring Boot. Guarda las cuentas en su base de datos y **anuncia** cada cambio de estado; ya
no escribe en el grupo de usuarios de Cognito.

```bash
cd userservice
mvn clean verify                 # pruebas y la regla de cobertura del paquete de servicios
docker build -t fatum-userservice .
```

La regla de cobertura se aplica solo a `fatum.service` (70 % de líneas, 60 % de ramas) y el reporte
HTML queda en `target/site/jacoco/index.html`.

Su documentación está en [userservice/README.md](userservice/README.md) y la API la publica el
servicio en `/swagger-ui.html`.

## cognito-lambda

Función de Python en AWS Lambda. Escucha los eventos del servicio y escribe el grupo de usuarios:
grupos, desactivación y reactivación.

```bash
cd cognito-lambda
python3 -m unittest discover -s tests -t .
sam build && sam deploy --guided --parameter-overrides UserPoolId=<pool> EventBusName=<bus>
```

Su documentación está en [cognito-lambda/README.md](cognito-lambda/README.md).

## El contrato entre los dos

El servicio publica en EventBridge con el origen `fatum.userservice`. Cada hecho viaja con su propio
`detail-type` y la función enruta por él:

| `detail-type` | Qué hace en el grupo de usuarios |
| --- | --- |
| `USER_VERIFICATION_CHANGED` | concede o retira el grupo de verificado |
| `USER_BECAME_PROFESSIONAL` | concede el grupo de profesional |
| `PROFESSIONAL_BECAME_CLIENT` | retira el grupo de profesional |
| `USER_ACTIVE_STATUS_CHANGED` | desactiva, cierra las sesiones y vacía los grupos, o devuelve el acceso |
| `PROFESSIONAL_PRINCIPAL_ADDRESS_CHANGED` | nada: no es una pertenencia |

Como la verificación es condición para ser profesional, perderla devuelve la cuenta a cliente y
el consumidor recibe los dos hechos: retira el grupo verificado y el de profesional.

Los nombres son lo único que comparten los dos proyectos, así que están declarados en ambos lados:
`EventPublisherService` en el servicio y `cognito-lambda/src/events.py` en la función. Cambiar uno
sin el otro es lo que rompe la integración, y falla de forma ruidosa: la función rechaza un evento
que no puede leer en lugar de adivinarlo.
