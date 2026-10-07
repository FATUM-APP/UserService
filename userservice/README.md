# Fatum UserService — Java 25, Spring Boot y almacenamiento compartido

Microservicio de usuarios de Fatum. Implementado con **Java 25**, **Spring Boot 3.5.3**,
**PostgreSQL**, **Amazon Cognito** (JWT) y un **servicio de archivos compartido** en lugar de
S3 directo.

> Las entidades representan el estado persistente y las invariantes del dominio. Los DTOs definen el
> contrato JSON y concentran la validación de las peticiones.

## Alcance actual

| Aspecto | Estado |
| --- | --- |
| Registro y perfil | Activo. El usuario envía sus datos y su foto de perfil |
| Verificación de identidad | **No activa.** No hay Textract, Rekognition ni prueba de vida |
| Estados de verificación | El enum completo se conserva, pero **toda cuenta nace `VERIFIED`** |
| Documentos de identidad | Endpoints y tabla se conservan, apuntando al servicio de archivos. La tabla queda vacía porque la app no los llama |
| Foto de perfil | Se reemplaza: se sube la nueva y se elimina la anterior |

## Arquitectura

| Capa | Responsabilidad |
| --- | --- |
| `fatum.controller` | Recibe peticiones validadas y devuelve DTOs. No expone entidades JPA. Traduce los errores a HTTP en `GlobalExceptionHandler`. |
| `fatum.dto` | Contratos de entrada/salida y `UserMapper`. |
| `fatum.service` | Reglas de negocio, unicidad, transacciones y publicación de los eventos de dominio. |
| `fatum.model` | Entidades JPA y sus transiciones de estado. |
| `fatum.repository` | Consultas mediante Spring Data JPA. |
| `fatum.storage` | Cliente del servicio de archivos compartido (`FileStorageClient`) y unión de PDFs. |
| `fatum.configuration` | Propiedades tipadas, CORS, seguridad y clientes de AWS. |
| `db/migration` | Versiona el esquema con Flyway. |

## Verificación

`VerificationStatus` describe el estado de identidad de la cuenta:

| Estado | Significado |
| --- | --- |
| `UNVERIFIED` | Nunca verificada, o el último intento quedó sin conclusión |
| `VERIFIED` | El sistema o un administrador confirmó la identidad |
| `REJECTED` | La evidencia contradice la identidad y no hay reintento |
| `MANUAL_REVIEW` | Hace falta que un administrador decida |

Hoy **no hay pipeline de verificación**, así que toda cuenta se crea en `VERIFIED` y el estado sólo se
lee a través de `GET /users/me/authenticated`. El enum y la columna se mantienen para poder reactivar
la funcionalidad sin otra migración.

## Contrato de usuario

El alta requiere email, nombre completo, teléfono, fecha de nacimiento, username, documento, tipo de
documento y género. `document` y `documentType` son obligatorios desde la creación y no forman parte
del DTO de actualización; quedan inmutables en este servicio.

### Creación

```json
{
  "awsId": "us-east-1:0a1b2c3d",
  "email": "user@example.com",
  "name": "Camilo Castaño",
  "phoneNumber": "+573001112233",
  "birthDate": "1998-05-10",
  "username": "ccastano46",
  "document": "1000271422",
  "documentType": "ID",
  "gender": "MALE"
}
```

### Actualización

```json
{
  "username": "nuevoUsername",
  "phoneNumber": "+573009998877",
  "role": "PROFESSIONAL",
  "city": "Bogotá"
}
```

Los campos omitidos no cambian. Un usuario `PROFESSIONAL` exige ciudad, y el cambio de rol publica el evento que añade la
cuenta al grupo `PROFESSIONAL` del pool.

### Respuesta

```json
{
  "email": "user@example.com",
  "name": "CAMILO CASTAÑO",
  "birthDate": "1998-05-10",
  "username": "ccastano46",
  "phoneNumber": "+573001112233",
  "role": "CLIENT",
  "verificationStatus": "VERIFIED",
  "isActive": true,
  "document": "1000271422",
  "gender": "MALE",
  "documentType": "ID",
  "city": null,
  "country": "COLOMBIA"
}
```

## Endpoints

| Método | Ruta | Autoridad | Resultado |
| --- | --- | --- | --- |
| `POST` | `/users/validate-signup` | Pública | Valida el alta sin persistirla |
| `POST` | `/users/register` | Cabecera `Lambda-Secret` | Crea la cuenta y la une al grupo `VERIFIED` |
| `GET` | `/users/me` | JWT válido | Devuelve la cuenta actual |
| `PUT` | `/users/update` | JWT válido | Actualiza los campos mutables |
| `GET` | `/users/me/authenticated` | JWT válido | Devuelve `verificationStatus` y `isVerified` |
| `PUT` | `/users/deactivate` | JWT válido | Desactiva lógicamente la cuenta |
| `PUT` | `/profile-image/update` | JWT válido | Reemplaza la foto de perfil (`multipart`, campo `image`) |
| `GET` | `/profile-image` | JWT válido | Devuelve la foto actual con una URL temporal |
| `POST` | `/documents/upload` | JWT válido | Sube un documento (`multipart`, campos `type`, `front`, `back`) |
| `POST` | `/documents/upload/single` | JWT válido | Sube un PDF ya escaneado (`multipart`, campo `file`) |
| `GET` | `/documents` | JWT válido | Devuelve el documento de la cuenta |

Los errores de dominio se traducen a HTTP en `GlobalExceptionHandler`: `404` si la cuenta o el archivo
no existen, `409` si hay conflicto con un valor único y `403` cuando la acción no está permitida.

## Eventos de dominio
Este servicio **no escribe el pool de Cognito**. Guarda la verdad en su base de datos y publica el
hecho en EventBridge; quien sí toca el pool es la función `cognito-lambda`.

| Evento (`detail-type`) | Cuándo se publica |
| --- | --- |
| `USER_VERIFICATION_CHANGED` | cambia el estado de verificación de la cuenta |
| `USER_BECAME_PROFESSIONAL` | la cuenta pasa a `PROFESSIONAL` |
| `PROFESSIONAL_BECAME_CLIENT` | un profesional vuelve a `CLIENT` |
| `USER_ACTIVE_STATUS_CHANGED` | la cuenta se activa o se desactiva; lleva el rol, porque devolver el acceso significa restaurar los grupos |
| `PROFESSIONAL_PRINCIPAL_ADDRESS_CHANGED` | cambia la dirección principal de un profesional |

Cada hecho viaja con su propio `detail-type` porque es lo que filtra la regla del bus. Si el bus
rechaza una entrada, queda en el log: EventBridge responde 200 aunque la entrada no se haya aceptado.

Perder la verificación arrastra la condición de profesional: la cuenta vuelve a `CLIENT` y se
publican los dos hechos, `USER_VERIFICATION_CHANGED` y `PROFESSIONAL_BECAME_CLIENT`, de modo que el
consumidor retira el grupo verificado y el de profesional. La regla vive en el modelo
(`User.markVerificationStatus`) y no en quien la invoca, para que valga por cualquier camino que
escriba el estado. Volver a estar verificado **no** devuelve la condición de profesional: eso es una
decisión de la cuenta y pasa por `/users/upgrade`.

## Almacenamiento de archivos

Este servicio **no conoce buckets**. Llama al `fatum-file-service` indicando una ruta y ese servicio
decide el bucket, el prefijo y las validaciones:

| Ruta | Uso |
| --- | --- |
| `user-service:profile-image` | Fotos de perfil |
| `user-service:document` | Documentos de identidad (imagen o PDF) |

El servicio de usuarios guarda la clave del objeto; la URL de descarga se firma en el momento de
responder. Si el servicio de archivos rechaza la petición el cliente recibe `400`; si no responde,
`502`.

## Pruebas y cobertura

```bash
mvn test      # ejecuta las pruebas y escribe target/site/jacoco/index.html
mvn verify    # además aplica la regla de cobertura
```

JaCoCo mide **únicamente el paquete `fatum.service`**, que es donde viven las reglas de negocio. DTOs,
entidades, repositorios, adaptadores, configuración y controladores quedan fuera de la medición. La
regla de aceptación es:

| Métrica | Mínimo |
| --- | --- |
| Líneas cubiertas | 70 % |
| Ramas cubiertas | 60 % |

## Persistencia y migraciones

Flyway administra el esquema y Hibernate usa `ddl-auto=validate` por defecto.

| Versión | Contenido |
| --- | --- |
| `V1` | Tabla `users` |
| `V2` | Tabla `profile_images` |
| `V3` | Tabla `document_files` |
| `V4` | Reemplaza `is_authenticated` por `verification_status` y ensancha `gender` a `VARCHAR(6)` |
| `V5` | Ensancha las claves de almacenamiento a `VARCHAR(512)` |

## Variables de entorno

| Variable | Descripción | Ejemplo |
| --- | --- | --- |
| `DB_URL` | URL JDBC de PostgreSQL. | `jdbc:postgresql://localhost:5432/fatum_users` |
| `DB_USERNAME` | Usuario de PostgreSQL. | `postgres` |
| `DB_PASSWORD` | Contraseña de PostgreSQL. | `postgres` |
| `USER_POOL_ID` | Pool de usuarios de Cognito. | `us-east-1_9qUKvpTsS` |
| `LAMBDA_SECRET` | Secreto que protege `/users/register`. | *(secreto)* |
| `AWS_REGION` | Región de AWS. | `us-east-1` |
| `FILE_SERVICE_URL` | URL base del servicio de archivos. | `http://localhost:8081` |
| `FILE_SERVICE_SECRET` | Secreto compartido (`X-Storage-Key`). | *(vacío en local)* |
| `JPA_DDL_AUTO` | Validación de Hibernate. | `validate` |
| `FLYWAY_ENABLED` | Migraciones al arrancar. | `true` |
| `CORS_ALLOWED_ORIGINS` | Orígenes permitidos. | `http://localhost:5173` |
| `PORT` | Puerto HTTP. | `8080` |

## Compilación

```bash
mvn clean verify
```

El `Dockerfile` incluido construye la imagen del servicio para Cloud Run o cualquier contenedor.

## Referencias

[1]: https://jakarta.ee/specifications/bean-validation/3.0/jakarta-bean-validation-spec-3.0.html "Jakarta Bean Validation 3.0"
[2]: https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1 "Jakarta Persistence 3.1"
[3]: https://documentation.red-gate.com/flyway/flyway-concepts/migrations "Flyway migrations"
[4]: https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-user-groups.html "Cognito user pool groups"
| `V6` | Tabla `addresses`, con la pareja (usuario, residencia) como clave única |
| `V7` | Alinea `profile_images` con su entidad: `user_aws_id` pasa a `user_username` |
| `V8` | Columna `position` en `addresses`: el orden de las direcciones vive en la tabla |
