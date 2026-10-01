# Fatum UserService — Java 25, Spring Boot y verificación de identidad

Microservicio de usuarios de Fatum. Gestiona el ciclo de vida de la cuenta (alta, actualización,
desactivación), la imagen de perfil, la evidencia de vida (liveness), los documentos de identidad y el
proceso de **verificación de identidad** con AWS.

Desde esta versión el servicio **no habla con S3**: los archivos se delegan al microservicio de
almacenamiento compartido (`fatum-file-service`), que decide a qué bucket va cada cosa según una *ruta*.

> Las entidades representan el estado persistente y las invariantes del dominio. Los DTOs definen el
> contrato JSON y concentran la validación de las peticiones. Las reglas de negocio viven en los
> servicios, que son el único lugar donde se lanza `FatumUserException`.

## Arquitectura

| Capa | Responsabilidad |
| --- | --- |
| `fatum.controller` | Recibe peticiones validadas y devuelve DTOs. No expone entidades JPA. |
| `fatum.dto` | Contratos de entrada/salida y `UserMapper`. |
| `fatum.service` | Reglas de negocio, transacciones y sincronización con Cognito. |
| `fatum.storage` | Cliente HTTP del servicio de archivos (`FileStorageClient`). |
| `fatum.verification` | Orquestador, política, retención de evidencia y analizadores AWS. |
| `fatum.model` | Entidades JPA y constantes del dominio. |
| `fatum.repository` | Consultas con Spring Data JPA. |
| `db/migration` | Esquema versionado con Flyway (V1…V13). |

## Flujo de verificación de identidad

La verificación tiene **dos fases**, y el orden no es casual: la primera es gratis y la segunda cuesta
dinero en cada intento. La prueba de vida solo se pide cuando la fase gratuita ya pasó el umbral, de
modo que nadie puede gastar Rekognition Face Liveness subiendo fotos.

```
1. POST /documents/upload                  fotos del documento (frente y, si aplica, reverso)
2. PUT  /profile-image/update              foto de perfil (la que verán los demás usuarios)
3. POST /verification/submit               fase gratuita; devuelve AWAITING_LIVENESS si hay que seguir
4. POST /liveness/sessions                abre la prueba de vida (solo si el paso 3 la pidió)
   (la app ejecuta FaceLivenessDetector y transmite la cámara directamente a Rekognition)
5. POST /liveness/sessions/{id}/complete   pide el veredicto y cierra el intento
```

Fase 1 — comprobaciones gratuitas:

| Paso | Servicio AWS | Qué aporta | Peso en el puntaje |
| --- | --- | --- | --- |
| Lectura del documento | Textract (`AnalyzeID` → `AnalyzeDocument(FORMS)` → `DetectDocumentText`) | número, tipo, nombre y fecha de nacimiento | — |
| Coincidencia de datos | Reglas propias | % de campos que concuerdan con lo registrado | 35 % |
| Documento ↔ foto de perfil | Rekognition `CompareFaces` | ¿el documento muestra a quien dice el perfil? | 30 % |
| Autenticidad | Bedrock (Claude) | riesgo de falsificación | 15 % |

Fase 2 — prueba de vida (`FaceLivenessClient`, Rekognition Face Liveness):

| Paso | Servicio AWS | Qué aporta | Peso en el puntaje |
| --- | --- | --- | --- |
| Vida real frente a la cámara | Rekognition Face Liveness | confianza de que hay una persona viva | 20 % |
| Referencia ↔ documento | Rekognition `CompareFaces` | ¿la persona viva es la dueña del documento? | decisivo |

La última comparación es la que cierra el hueco de seguridad: la fase 1 admite fotos que un atacante
podría tener (la cédula robada y una selfie de redes sociales) y la prueba de vida demuestra que hay
alguien vivo, pero no que sea el dueño del documento. Rekognition devuelve además una imagen de
referencia del rostro que pasó la prueba, y esa imagen se compara con el documento.

Los pesos y umbrales son configuración (`fatum.verification.*`), no constantes.

### Bandas y decisión

| Puntaje | Banda | Efecto |
| --- | --- | --- |
| ≥ 80 % | `VERIFIED` | se pide la prueba de vida (`AWAITING_LIVENESS`); aún no verifica |
| 30 % – 80 % | `MANUAL` | el usuario conserva sus dos reintentos |
| < 30 % | `REJECTED` | el caso pasa a revisión manual |

La decisión final la toma `VerificationPolicy`, que además de la banda mira el historial:

* un documento falsificado o que no corresponde a la foto de perfil (`< 20 %`) fuerza `REJECTED`;
* tres intentos sin resolverse → `MANUAL_REVIEW`;
* dos intentos consecutivos por debajo del 20 % → `REJECTED` definitivo;
* cualquier otro rechazo aislado → `MANUAL_REVIEW`.

Y la prueba de vida decide sola, con una regla explícita: **si falla, el caso va a revisión manual y no
hay segundo intento**. El razonamiento es el del negocio: si el usuario pasó todas las demás pruebas y
falló justo la de vida, lo que procede es que un humano lo mire, no volver a gastar dinero. Se verifica
solo si Rekognition confirma la vida (`≥ min-confidence`) **y** la referencia coincide con el documento.
Un fallo técnico de Rekognition o del almacenamiento no cierra nada: deja el intento abierto para
reintentar, porque un problema de infraestructura no es un rechazo.

### Retención de la evidencia

El material de identidad no se acumula:

| Resultado | Documento | Liveness | Foto de perfil |
| --- | --- | --- | --- |
| `VERIFIED` | se borra | se conserva (referencia del rostro) | se conserva |
| `AWAITING_LIVENESS` | se conserva (la prueba lo necesita) | se crea la referencia | se conserva |
| `PENDING` (reintento) | se borra | se borra | se borra |
| `MANUAL_REVIEW` / `REJECTED` | se conserva para el revisor | se conserva si la produjo Rekognition | se borra |

Cada borrado o conservación queda registrado en `storage_events` con su motivo, lo que permite responder
"¿por qué este documento ya no está en el bucket?".

### Verificación manual

El administrador (grupo `ADMIN` de Cognito) consulta la cola y decide:

```
GET  /verification/admin/pending
POST /verification/admin/review    (multipart: userAwsId, verified, notes, photo)
```

Si confirma la identidad debe adjuntar la foto validada: pasa a ser **la foto de perfil y la referencia
de liveness** de la cuenta, de modo que el sistema conserva una única imagen de confianza.

### Prueba de vida

El servicio **nunca recibe el vídeo ni un frame subido a mano**: el cliente crea una sesión, la app
ejecuta `FaceLivenessDetector` y transmite la cámara directamente a Rekognition, y al terminar se pide
el veredicto. `POST /liveness/upload` ya no existe: una foto tomada antes no prueba quién está frente a
la pantalla. Solo la imagen de referencia que Rekognition escribe en el bucket, o una que adopte un
administrador durante una revisión, puede convertirse en la referencia de confianza de una cuenta.

Una sesión solo se abre si hay un intento esperándola (`AWAITING_LIVENESS`), se reutiliza la que siga
abierta si el cliente reintentó por un fallo de red, y hay un tope de sesiones por intento
(`fatum.verification.liveness.max-sessions-per-attempt`).

### Cambio de foto de perfil

Antes de existir una verificación no hay nada con qué comparar y el cambio es libre. Una vez verificada
la cuenta, el cambio **nunca queda "en verificación" ni va a un humano**: se acepta o se descarta.

```
PUT /profile-image/update   →  {image, pendingImage, pendingVerification: true, lastChangeOutcome}
GET /profile-image          →  se consulta hasta que pendingVerification pase a false
```

La imagen nueva se guarda como `PENDING` (nadie la ve), la anterior sigue siendo la visible, y la
comparación con la referencia corre en segundo plano (`PendingProfilePhotoVerifier`, publicada tras el
commit del upload). Si la similitud alcanza el umbral configurado
(`fatum.verification.profile-photo-change-threshold`, 80 % por defecto), la nueva sustituye a la
anterior y el objeto viejo se borra; si no, se descarta y se borra del bucket. Si la comparación no se
puede evaluar, **se descarta**: publicar a ciegas permitiría cambiar la cara de una cuenta verificada
cada vez que Rekognition fallara. Hay un tope de cambios por día
(`fatum.verification.max-pending-photo-changes-per-day`) que es un límite de coste, no de seguridad.

## Contrato de usuario

### Alta

```json
{
  "awsId": "sub-de-cognito",
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

### Respuesta pública

```json
{
  "email": "user@example.com",
  "name": "CAMILO CASTAÑO",
  "birthDate": "1998-05-10",
  "username": "ccastano46",
  "phoneNumber": "+573001112233",
  "role": "CLIENT",
  "verificationStatus": "UNVERIFIED",
  "isActive": true,
  "document": "1000271422",
  "gender": "MALE",
  "documentType": "ID",
  "city": null,
  "country": "COLOMBIA"
}
```

`verificationStatus` reemplaza al antiguo booleano `isAuthenticated`, que no sabía expresar "el sistema
no pudo decidir, hace falta una persona": `UNVERIFIED`, `VERIFIED`, `MANUAL_REVIEW`, `REJECTED`.

## Documentos de identidad

Se guardan como **fotografías**, una por cara. El PDF fusionado desapareció: Textract y Rekognition
necesitan la imagen, no un contenedor.

| Tipo | Frente | Reverso |
| --- | --- | --- |
| `PASSPORT` | obligatorio | no aplica |
| `ID` | obligatorio | obligatorio |
| `DRIVING_LICENSE` | obligatorio | obligatorio |

## Persistencia y migraciones

Flyway administra el esquema y Hibernate usa `ddl-auto=validate`.

| Versión | Contenido |
| --- | --- |
| `V1` | tabla `users` |
| `V2` | tabla `profile_images` |
| `V3` | tabla `document_files` |
| `V4` | `verification_status` reemplaza a `is_authenticated`; corrige `gender` a `VARCHAR(6)` |
| `V5` | el documento pasa a ser `front_*` / `back_*` |
| `V6` | tabla `liveness_files` |
| `V7` | amplía `profile_images.image_key` |
| `V8` | tabla `verification_attempts` |
| `V9` | tabla `storage_events` (auditoría de retención) |
| `V10` | tabla `liveness_checks` (sesiones de Rekognition Face Liveness) |
| `V11` | tipo de intento y las dos comparaciones de rostro + `decided_at` |
| `V12` | `profile_images.status`: la foto visible y la que se está comprobando |
| `V13` | origen de la referencia de vida (`ADMIN` / `REKOGNITION`) y su bucket |

## Variables de entorno

| Variable | Descripción | Ejemplo |
| --- | --- | --- |
| `DB_URL` | URL JDBC de PostgreSQL. | `jdbc:postgresql://localhost:5432/fatum_users` |
| `DB_USERNAME` / `DB_PASSWORD` | Credenciales de PostgreSQL. | `postgres` / `postgres` |
| `USER_POOL_ID` | Pool de Cognito (emisor del JWT). | `us-east-1_9qUKvpTsS` |
| `AWS_REGION` | Región de AWS. | `us-east-1` |
| `LAMBDA_SECRET` | Secreto compartido del endpoint de registro. | `...` |
| `FILE_SERVICE_URL` | URL del servicio de archivos. | `http://fatum-file-service:8081` |
| `FILE_SERVICE_SECRET` | Secreto interno del servicio de archivos. | `...` |
| `FILE_SERVICE_PROFILE_IMAGE_ROUTE` | Ruta de las fotos de perfil. | `user-service:profile-image` |
| `FILE_SERVICE_LIVENESS_ROUTE` | Ruta de la evidencia de vida. | `user-service:liveness` |
| `FILE_SERVICE_DOCUMENT_ROUTE` | Ruta de los documentos. | `user-service:document` |
| `VERIFICATION_ENABLED` | Activa el proceso. | `true` |
| `VERIFICATION_MAX_ATTEMPTS` | Intentos antes de la revisión manual. | `3` |
| `VERIFICATION_VERIFIED_THRESHOLD` | Umbral de aprobación. | `80` |
| `VERIFICATION_MANUAL_THRESHOLD` | Umbral de rechazo duro. | `30` |
| `VERIFICATION_FRAUD_RISK_THRESHOLD` | Riesgo de falsificación tolerado. | `70` |
| `VERIFICATION_PROFILE_PHOTO_THRESHOLD` | Similitud mínima al cambiar la foto. | `80` |
| `VERIFICATION_MAX_PHOTO_CHANGES` | Cambios de foto encolados por día. | `5` |
| `S3_LIVENESS_BUCKET` | Bucket donde Rekognition escribe la referencia. | `fatum-liveness` |
| `VERIFICATION_LIVENESS_ENABLED` | Activa la prueba de vida. | `true` |
| `VERIFICATION_LIVENESS_MIN_CONFIDENCE` | Confianza mínima de la prueba de vida. | `80` |
| `VERIFICATION_LIVENESS_SESSION_TTL` | Vida de una sesión reutilizable. | `3m` |
| `VERIFICATION_LIVENESS_MAX_SESSIONS` | Sesiones por intento. | `3` |
| `VERIFICATION_BEDROCK_MODEL_ID` | Modelo de Bedrock. | `anthropic.claude-3-5-sonnet-20241022-v2:0` |
| `COGNITO_GROUPS_ENABLED` | Sincroniza los grupos de Cognito. | `true` |
| `COGNITO_VERIFIED_GROUP` | Grupo de cuentas verificadas. | `VERIFIED` |
| `COGNITO_PROFESSIONAL_GROUP` | Grupo de profesionales. | `PROFESSIONAL` |
| `JPA_DDL_AUTO` | Validación de Hibernate. | `validate` |
| `FLYWAY_ENABLED` | Migraciones al arrancar. | `true` |
| `CORS_ALLOWED_ORIGINS` | Orígenes permitidos. | `http://localhost:5173` |
| `PORT` | Puerto HTTP. | `8080` |

El detalle completo, con las variables opcionales y sus valores por defecto, está en `.env.example`.

## Compilación

```bash
./mvnw clean verify      # compila, ejecuta las pruebas y valida la cobertura (≥ 70 %)
```

Las pruebas cubren la política de decisión, la retención de evidencia, los servicios y los analizadores
con dobles de AWS: no hace falta ninguna credencial para ejecutarlas.

## Documentación

* [`API.md`](API.md) — contrato HTTP completo con ejemplos.
* [`INFORME_CAMBIOS.md`](INFORME_CAMBIOS.md) — informe de los cambios de esta versión.
* `MIGRATION_REPORT.md` — informe histórico de la migración anterior.

## Referencias

[1]: https://jakarta.ee/specifications/bean-validation/3.0/ "Jakarta Bean Validation 3.0"
[2]: https://jakarta.ee/specifications/persistence/3.1/ "Jakarta Persistence 3.1"
[3]: https://documentation.red-gate.com/flyway/flyway-concepts/migrations "Flyway migrations"
[4]: https://docs.aws.amazon.com/textract/latest/dg/API_AnalyzeID.html "Amazon Textract AnalyzeID"
[5]: https://docs.aws.amazon.com/rekognition/latest/APIReference/API_CompareFaces.html "Amazon Rekognition CompareFaces"
[6]: https://docs.aws.amazon.com/bedrock/latest/userguide/model-parameters-anthropic-claude-messages.html "Bedrock Anthropic Messages"
