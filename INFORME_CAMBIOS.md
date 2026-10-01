# Informe de cambios — UserService

**Fecha:** 1 de octubre de 2026
**Rama:** `feat/shared-storage-and-verification`
**Base:** `dev`

Este informe resume los tres bloques de trabajo pedidos: **separar la subida de archivos a un servicio
compartido**, **reescribir el documento de identidad como fotografías** e **implementar el proceso de
verificación de identidad**. Incluye el diseño, lo que se decidió y por qué, el inventario de archivos y
lo que queda pendiente.

---

## 1. Resumen

| | |
| --- | --- |
| Archivos de producción | 80 (23 modificados, 36 nuevos, 3 eliminados) |
| Líneas de producción | 5 595 |
| Archivos de prueba | 17 (nuevos) |
| Líneas de prueba | 2 949 |
| Pruebas | **180, todas en verde** |
| Cobertura (servicios, verificación, almacenamiento) | **> 90 %** (la puerta de JaCoCo es 70 %) |
| Migraciones nuevas | V4 … V9 |

---

## 2. Separación del almacenamiento (`fatum-file-service`)

### El problema

Cada microservicio subía archivos a S3 por su cuenta. Eso obligaba a repetir credenciales, políticas de
bucket y validaciones en cada servicio, y a desplegar todos ellos cada vez que cambiaba una regla (por
ejemplo, el tamaño máximo de una foto).

### La solución

Un microservicio de archivos con **rutas** en lugar de buckets:

```
"user-service:profile-image"  →  bucket fatum-user-profile-…  prefijo profile-images
"user-service:liveness"       →  bucket (configurable)       prefijo liveness
"user-service:document"       →  bucket fatum-documents-…    prefijo documents
"ratings-service:photo"       →  bucket fatum-ratings-…      prefijo photos
"catalog-service:photo"       →  bucket fatum-catalog-…      prefijo photos
```

El cliente pide una ruta y el servicio decide bucket, prefijo, tipos MIME permitidos, tamaño máximo y
vigencia de las URLs prefirmadas. Añadir un bucket nuevo para otro microservicio es **configuración**,
no código: la imagen Docker es la misma en todos los entornos.

### Qué se hizo en el UserService

* Se eliminó `S3FileStorage`, `StoredObject` y `PdfMerger` junto con `AwsS3Config`.
* Se añadió `fatum.storage.FileStorageClient` (interfaz) y `HttpFileStorageClient` (implementación
  `RestClient`), más `StoredFile`, `StorageException` y `FileStorageProperties`.
* Las claves de los objetos se ampliaron a `VARCHAR(512)`: las que emite el servicio de archivos incluyen
  prefijo, fecha e identificador.
* Los errores del servicio de archivos se traducen: un 4xx llega al cliente como `400` con el mensaje
  original; cualquier otro fallo es `502` en este servicio.

### Ventaja de diseño

`FileStorageClient` es una interfaz. En las pruebas se sustituye por un doble y no hace falta ni S3 ni
credenciales; si algún día el almacenamiento pasa a ser otra cosa, solo cambia la implementación.

---

## 3. Documento de identidad como fotografías

### El problema

El documento se subía como dos imágenes que se fusionaban en un PDF con PDFBox. La verificación necesita
la imagen: Textract y Rekognition no leen un PDF en memoria sin convertirlo otra vez.

### Qué se hizo

* `document_files` pasa de `document_key` a `front_key` + `back_key` (con sus nombres y tamaños).
* El pasaporte solo necesita el frente; el resto exige ambas caras, y se valida **antes** de subir nada.
* Se eliminó `PdfMerger` y la dependencia de PDFBox.
* Solo se aceptan imágenes: un PDF responde `400`.

---

## 4. Verificación de identidad

### 4.1 Flujo

```
documento + liveness + foto de perfil  →  POST /verification/submit  →  informe
```

### 4.2 Análisis y puntaje

| Señal | Fuente | Peso |
| --- | --- | --- |
| Coincidencia de datos del documento | Textract + reglas propias | 35 % |
| Rostro del documento ↔ liveness | Rekognition | 30 % |
| Foto de perfil ↔ liveness | Rekognition | 20 % |
| Autenticidad (riesgo de falsificación) | Bedrock (Claude) | 15 % |

Los pesos se **renormalizan** sobre las señales que realmente se pudieron evaluar: si Bedrock está
apagado, el puntaje no baja artificialmente, solo se reparte entre el resto.

### 4.3 Decisiones de negocio (todas configurables)

| Situación | Resultado |
| --- | --- |
| Puntaje ≥ 80 % | `VERIFIED` |
| 30 % – 80 % | `PENDING` con reintentos |
| < 30 % | `REJECTED` → revisión manual |
| Documento falsificado o rostro del documento < 20 % | `REJECTED` aunque el puntaje sea alto |
| 3 intentos sin resolverse | `MANUAL_REVIEW` |
| 2 intentos consecutivos < 20 % | `REJECTED` definitivo |
| Cualquier otro rechazo aislado | `MANUAL_REVIEW` |

Se eliminó el booleano `is_authenticated`: no sabía expresar "hace falta que lo mire una persona".
En su lugar, `verification_status` con cuatro estados (`UNVERIFIED`, `VERIFIED`, `MANUAL_REVIEW`,
`REJECTED`).

### 4.4 Retención de la evidencia

| Resultado | Documento | Liveness | Foto de perfil |
| --- | --- | --- | --- |
| `VERIFIED` | se borra | se conserva | se conserva |
| `PENDING` | se borra | se borra | se borra |
| `MANUAL_REVIEW` / `REJECTED` | se conserva para el revisor | se borra | se borra |

Todo borrado o conservación se registra en `storage_events` con su motivo, de modo que se puede auditar
por qué un documento ya no está en el bucket. Un objeto compartido (por ejemplo, la foto que un
administrador adopta como perfil y como liveness) no se borra mientras algo lo siga referenciando.

### 4.5 Revisión manual

Los administradores (grupo `ADMIN` de Cognito) tienen una cola de casos y una decisión:

```
GET  /verification/admin/pending
POST /verification/admin/review
```

Al confirmar una identidad, el administrador **debe** adjuntar la foto validada: pasa a ser la foto de
perfil y la referencia de liveness de la cuenta. Así el sistema conserva una sola imagen de confianza y
el usuario puede cambiar su foto después solo si sigue siendo la misma persona (comparación automática
contra esa referencia).

### 4.6 Integración con Cognito

* Al verificar la identidad, el usuario entra al grupo `VERIFIED`.
* Al pasar a `PROFESSIONAL`, entra al grupo `PROFESSIONAL` (antes no ocurría: la base de datos cambiaba
  pero Cognito no).
* Si Cognito falla, por defecto **no** se revienta la operación: la verificación ya está en la base de
  datos y se registra el error. `COGNITO_STRICT=true` invierte ese comportamiento.

---

## 5. Otras correcciones incluidas

| Corrección | Detalle |
| --- | --- |
| `gender VARCHAR(5)` | `FEMALE` tiene seis caracteres: la columna no admitía el valor. Corregido en `V4`. |
| Validación de DTOs | `@Valid` no estaba en los `@RequestBody`: las anotaciones de los DTOs no se aplicaban. |
| Números de documento | El OCR inserta puntos y espacios (`1.020.304.050`); ahora se comparan ignorando separadores. |
| Fechas con mes en texto | `10 MAY 1998` no se reconocía por ser mayúsculas. |
| Número leído del texto | El patrón podía capturar un separador inicial (`" 1020304050"`); ahora se recorta. |
| CORS | Se mantiene la configuración por variable de entorno. |

---

## 6. Inventario de archivos

### 6.1 Nuevos — almacenamiento

`fatum/storage/FileStorageClient.java`, `HttpFileStorageClient.java`, `StoredFile.java`,
`StorageException.java`, `FileStorageProperties.java`, `fatum/configuration/StorageClientConfig.java`

### 6.2 Nuevos — verificación

`fatum/verification/`: `VerificationService`, `VerificationPolicy`, `VerificationProperties`,
`VerificationReport`, `VerificationRetentionService`, `AdminVerificationService`, `ScoredAttempt`,
`FileContentFetcher`, `HttpFileContentFetcher`

`fatum/verification/analyzer/`: `DocumentAnalyzer`, `TextractDocumentAnalyzer`, `DocumentFieldMatcher`,
`ExtractedDocument`, `FaceComparator`, `RekognitionFaceComparator`, `FaceMatch`, `FraudAnalyzer`,
`BedrockFraudAnalyzer`, `FraudAssessment`, `VerificationScoreCalculator`, `ScoreComponent`

### 6.3 Nuevos — modelo, repositorios y DTOs

`LivenessFile`, `VerificationAttempt`, `StorageEvent`; `VerificationStatus`, `VerificationBand`,
`VerificationOutcome`, `VerificationDecision`, `StoredFileType`, `StorageEventAction`,
`StorageEventReason`; `VerificationAttemptRepository`, `LivenessFileRepository`,
`StorageEventRepository`; `DocumentResponse`, `VerificationAttemptResponse`,
`VerificationStatusResponse`, `AdminReviewRequest`, `PendingVerificationResponse`

### 6.4 Nuevos — servicios y controladores

`CognitoGroupService`, `LivenessService`, `CognitoProperties`; `LivenessController`,
`VerificationController`, `AdminVerificationController`

### 6.5 Eliminados

`fatum/storage/S3FileStorage.java`, `fatum/storage/StoredObject.java`, `fatum/storage/PdfMerger.java`,
`fatum/configuration/aws/AwsS3Config.java`

### 6.6 Modificados (destacados)

`User` (estado de verificación), `DocumentFile` (dos caras), `UserService` (grupo profesional),
`UserRepository` (cola de revisión), `UserResponse`/`UserStatusResponse`/`UserMapper`
(`verificationStatus`), `DocumentService` y `ProfileImageService` (cliente de archivos),
`GlobalExceptionHandler` (errores de almacenamiento), `SecurityConfig` (`@EnableMethodSecurity` y
protección de `/verification/admin/**`), `application.yaml`, `pom.xml`.

### 6.7 Migraciones

| Versión | Contenido |
| --- | --- |
| `V4` | `verification_status` sustituye a `is_authenticated`; `gender` a `VARCHAR(6)` |
| `V5` | `document_files` pasa a `front_*` / `back_*` |
| `V6` | tabla `liveness_files` |
| `V7` | `profile_images.image_key` a `VARCHAR(512)` |
| `V8` | tabla `verification_attempts` |
| `V9` | tabla `storage_events` |

---

## 7. Pruebas

180 pruebas, ninguna necesita credenciales de AWS: los clientes de Textract, Rekognition, Bedrock,
Cognito y del servicio de archivos se sustituyen por dobles.

| Suite | Qué cubre |
| --- | --- |
| `VerificationPolicyTest` (16) | todas las reglas de decisión, una por caso |
| `VerificationServiceTest` (16) | orquestación, puntajes, bandas, retención, Cognito |
| `VerificationRetentionServiceTest` (10) | borrado por resultado, objetos compartidos, fallos de S3 |
| `AdminVerificationServiceTest` (9) | confirmación, rechazo, auditoría, cola |
| `DocumentFieldMatcherTest` (10) | comparación de campos, formatos de fecha, OCR |
| `TextractDocumentAnalyzerTest` (10) | AnalyzeID → FORMS → texto, fallos |
| `BedrockFraudAnalyzerTest` (9) | veredicto, respuestas con markdown, carga útil |
| `RekognitionFaceComparatorTest` (6) | similitud, sin rostro, fallos |
| `DocumentServiceTest` (17) | una o dos caras, reemplazo, borrado |
| `ProfileImageServiceTest` (16) | comparación con liveness, adopción por el administrador |
| `LivenessServiceTest` (10) | alta y reemplazo de la evidencia |
| `UserServiceTest` (21) | unicidad, edad, roles, estado de verificación |
| `HttpFileStorageClientTest` (12) | multipart, errores 4xx/5xx, URLs prefirmadas |
| `HttpFileContentFetcherTest` (6) | descarga por URL prefirmada y sus fallos |
| `CognitoGroupServiceTest` (7) | grupos, desactivación, modo estricto |
| `VerificationScoreCalculatorTest` (5) | pesos y renormalización |

La puerta de cobertura de JaCoCo se mantiene en el 70 % sobre los paquetes de lógica
(`service`, `verification`, `storage`); los DTOs, entidades, configuración y controladores quedan
excluidos porque son adaptadores sin reglas propias.

---

## 8. Despliegue

1. **Levantar `fatum-file-service`** con sus buckets configurados y anotar `STORAGE_INTERNAL_SECRET`.
2. **Aplicar las migraciones** `V4`…`V9` (Flyway lo hace al arrancar).
3. **Configurar el UserService** con `FILE_SERVICE_URL`, `FILE_SERVICE_SECRET` y las tres rutas; las
   variables `AWS_S3_*` ya no se usan.
4. **Crear los grupos** `VERIFIED`, `PROFESSIONAL` y `ADMIN` en el pool de Cognito.
5. **Dar permisos IAM** al UserService para `textract:AnalyzeID`, `textract:AnalyzeDocument`,
   `textract:DetectDocumentText`, `rekognition:CompareFaces`,
   `bedrock:InvokeModel` y `cognito-idp:AdminAddUserToGroup`.
6. **Habilitar el modelo de Bedrock** que se indique en `VERIFICATION_BEDROCK_MODEL_ID` en la región.

### Orden de despliegue sugerido

El servicio de archivos debe estar en pie antes de desplegar el UserService: en cuanto arranque la nueva
versión, las subidas van por ahí. Los documentos antiguos (PDF) siguen en la base de datos con su clave;
al volver a subir uno se reemplazan y se borran sus objetos.

---

## 9. Pendientes y decisiones que conviene revisar

| Tema | Situación |
| --- | --- |
| **Rama `dev` sin remoto** | Los cambios están en local, en `feat/shared-storage-and-verification`; no se ha hecho push. |
| **Umbrales** | Los valores por defecto (80/30/20/70) son un punto de partida razonable; conviene calibrarlos con documentos reales. |
| **Antifraude con Bedrock** | El modelo juzga a partir de los campos leídos, no de la imagen. Si se quiere análisis de píxeles haría falta otra estrategia. |
| **Detección de vida real** | **Resuelto en la segunda iteración (ver §10)**: se sustituyó el frame subido a mano por Rekognition Face Liveness. |
| **`MIGRATION_REPORT.md`** | Es el informe de una migración anterior; se conserva como histórico. |
| **Notificaciones** | Cuando un caso pasa a `MANUAL_REVIEW` no se avisa a nadie: falta correo o evento. |
| **Reintentos tras un rechazo** | Hoy un `REJECTED` definitivo exige que un administrador reabra el caso; no hay endpoint para eso. |
---

# Segunda iteración — prueba de vida real y cambio de foto asíncrono

**Fecha:** 1 de octubre de 2026
**Rama:** `feat/shared-storage-and-verification` (local, sin push)

## 10. Qué cambió y por qué

### 10.1 El problema del "liveness" falso

La iteración anterior comprobaba la identidad con un frame que el cliente subía a mano. Eso tiene dos
fallos que no se arreglan con umbrales: una foto de una persona viva no prueba que haya alguien vivo
frente a la pantalla, y el atacante podía ser una persona distinta de la dueña del documento. Además,
cada intento costaba una llamada a Rekognition aunque el documento claramente no cuadrara.

Se reemplazó por **Amazon Rekognition Face Liveness**, que es una prueba de vida activa: la app abre una
sesión, transmite la cámara directamente a AWS y el servicio recibe el veredicto y una imagen de
referencia del rostro que pasó la prueba.

### 10.2 Las dos fases: primero gratis, después el pago

El orden es la regla de negocio, no un detalle de implementación:

| Fase | Coste | Qué se comprueba | Qué decide |
| --- | --- | --- | --- |
| 1. `POST /verification/submit` | gratis | Textract + campos + documento↔perfil + Bedrock | si hay que gastar en la fase 2 |
| 2. `POST /liveness/sessions` + `/complete` | **se paga por intento** | vida real + referencia↔documento | la verificación |

Si la fase 1 da `VERIFIED` (≥ 80 %) el intento queda `AWAITING_LIVENESS`: la cuenta **no** se verifica
todavía y solo entonces se puede abrir una prueba de vida. Si da `MANUAL` el usuario conserva sus
reintentos y no se gasta nada; si da `REJECTED` va a revisión manual. Así, subir fotos para tantear el
sistema no cuesta dinero.

Guardas adicionales sobre el paso pagado:

* `POST /liveness/sessions` responde `409` si no hay un intento esperando la prueba: no se puede pedir
  "por si acaso".
* Una sesión que sigue abierta se reutiliza, para que un reintento por fallo de red no pague dos veces.
* Tope de sesiones por intento (`max-sessions-per-attempt`, 3 por defecto).

### 10.3 Se cierra el hueco de seguridad

La fase 1 compara el documento con la foto de perfil, y ambas son imágenes que un atacante puede tener:
la cédula robada y una selfie de redes sociales. La prueba de vida demuestra que hay alguien vivo… pero
ese alguien podría ser el atacante y no el dueño del documento.

Por eso la fase 2 hace **dos** comparaciones:

```
Foto de perfil ──┬─ compare ──► Foto del documento     (fase 1, gratis)
                 │
Cámara en vivo ──┴─ Face Liveness ──► Referencia ── compare ──► Foto del documento   (fase 2, decisiva)
```

La segunda es barata (Rekognition ya devolvió la referencia) y es la que cierra el hueco: **no se
verifica a nadie cuyo rostro en vivo no coincida con el documento**. Si esa comparación no se puede
evaluar, el caso va a un humano; nunca se aprueba "por defecto".

### 10.4 Se eliminó `POST /liveness/upload`

Se quitó por completo (opción A). Una foto subida a mano no prueba nada, así que el endpoint ya no
existe: ni siquiera detrás de una bandera. Las apps que lo usaran deben pasar a
`POST /liveness/sessions` + `FaceLivenessDetector` + `POST /liveness/sessions/{id}/complete`.

Consecuencia: **una cuenta solo puede adquirir una referencia de confianza de dos formas**, y ambas
quedan registradas con su origen (`liveness_files.source`):

| Origen | Quién la produce | Cuándo |
| --- | --- | --- |
| `REKOGNITION` | Face Liveness, escrita en el bucket de vida | al pasar la prueba |
| `ADMIN` | un administrador, subiéndola durante una revisión manual | al confirmar la identidad |

### 10.5 Cambio de foto de perfil: se acepta o se descarta

Una cuenta verificada puede cambiar su foto, pero nunca queda "en verificación" ni esperando a una
persona:

1. `PUT /profile-image/update` guarda la imagen nueva como `PENDING` (nadie la ve; la anterior sigue
   visible) y responde `pendingVerification: true`. Nada se publica antes de comparar.
2. Después del commit del upload, `PendingProfilePhotoVerifier` compara en otro hilo la imagen nueva con
   la referencia de vida.
3. Si la similitud alcanza el umbral, la nueva sustituye a la anterior y el objeto viejo se borra del
   bucket. Si no, se descarta: **se borra la fila y el objeto**, y la foto anterior permanece.
4. `GET /profile-image` informa `pendingVerification` y `lastChangeOutcome`
   (`PENDING` / `VERIFIED` / `REJECTED`), que es lo que el cliente consulta.

Si la comparación no se puede evaluar (Rekognition caído, almacenamiento caído) **se descarta**. Publicar
a ciegas permitiría cambiar la cara de una cuenta verificada durante una caída. Hay un tope de cambios
por día, que es un límite de coste, no de seguridad.

Antes de existir una verificación no hay nada con qué comparar y el cambio sigue siendo inmediato.

### 10.6 Impacto en la retención de evidencia

La evidencia se conserva mientras haga falta y se borra en cuanto deja de hacer falta:

| Resultado | Documento | Referencia de vida | Foto de perfil |
| --- | --- | --- | --- |
| `AWAITING_LIVENESS` | se conserva | se creará al pasar la prueba | se conserva |
| `VERIFIED` | se borra | se conserva | se conserva |
| `PENDING` (reintento) | se borra | se borra | se borra |
| `MANUAL_REVIEW` / `REJECTED` | se conserva para el revisor | se conserva **si la produjo Rekognition** (es la prueba de quién estaba en la cámara) | se borra |

Todo queda auditado en `storage_events`, con el motivo nuevo `LIVENESS_PENDING`.

## 11. Inventario de esta iteración

### 11.1 Nuevos

| Área | Clases |
| --- | --- |
| Prueba de vida | `analyzer/FaceLivenessClient`, `RekognitionFaceLivenessClient`, `LivenessSession`, `LivenessResult`, `LivenessStatus` |
| Orquestación | `LivenessSessionService`, `PendingProfilePhotoVerifier`, `ProfileImageChangedEvent` |
| Modelo | `LivenessCheck`, `constant/LivenessCheckStatus`, `constant/ReferenceSource`, `constant/ProfileImageStatus`, `constant/VerificationAttemptType` |
| Repositorio | `LivenessCheckRepository` |
| DTOs | `LivenessSessionResponse`, `LivenessResultResponse`, `ProfileImageResponse` |
| Configuración | `AsyncConfig` (pool `verificationExecutor`) |
| Migraciones | `V10`…`V13` |

### 11.2 Eliminados

* `POST /liveness/upload` y la subida manual del frame (`LivenessService.upload`).
* Las constantes `INVALID_LIVENESS*` y `PROFILE_PHOTO_MISMATCH`, que ya no se pueden producir.

### 11.3 Modificados

`VerificationService` (dos fases y `awaitingLiveness`), `VerificationPolicy` (decide la prueba de vida),
`VerificationProperties` (bloque `liveness` y umbral de cambios de foto), `VerificationRetentionService`
(caso `AWAITING_LIVENESS`), `ProfileImageService` (encola en vez de comparar), `ProfileImage` (estado
`ACTIVE`/`PENDING`), `LivenessFile` (origen y bucket), `VerificationAttempt` (tipo, dos comparaciones,
`decidedAt`, `livenessConfidence`), `LivenessController`, `ProfileImageController`,
`VerificationController`, `GlobalExceptionHandler`, `FaceComparator` (comparar contra un objeto de S3),
`application.yaml`, `README.md`, `API.md`, `.env.example`.

### 11.4 Migraciones nuevas

| Versión | Contenido |
| --- | --- |
| `V10` | tabla `liveness_checks` |
| `V11` | `type` en `verification_attempts`, renombra las dos columnas de comparación, `liveness_confidence`, `decided_at` |
| `V12` | `profile_images.status` y la unicidad por usuario y estado |
| `V13` | `liveness_files.source` y `storage_bucket` |

## 12. Pruebas

```
./mvnw verify        →  230 pruebas, 0 fallos, cobertura ≥ 70 % (JaCoCo)
```

Se añadieron dos suites nuevas y se reescribieron las de la política, el orquestador, la retención, la
foto de perfil y la referencia de vida:

* `LivenessSessionServiceTest` (15) — guardas del paso pagado, reutilización de sesiones, veredictos,
  idempotencia y el caso en que otro usuario intenta reportar la sesión.
* `PendingProfilePhotoVerifierTest` (13) — publicar, descartar y **fallar cerrado** cuando la comparación
  no se puede evaluar.
* `VerificationPolicyTest` (23) — las dos fases por separado, incluida la regla "la prueba de vida falla
  → humano, sin segundo intento".

## 13. Despliegue de esta iteración

1. Aplicar checkpoints (`V10`…`V13`) — los ejecuta Flyway al arrancar.
2. Crear el bucket `S3_LIVENESS_BUCKET` (por ejemplo `fatum-liveness`).
3. Permisos IAM nuevos: `rekognition:CreateFaceLivenessSession`, `rekognition:GetFaceLivenessSessionResults`
   y lectura del bucket de vida (S3 `GetObject`, porque el comparador lee la referencia directamente).
4. Configurar `S3_LIVENESS_BUCKET` y el prefijo; si falta el bucket, la prueba de vida responde `503` y
   el resto del servicio sigue funcionando.
5. Actualizar las apps: quitar la subida del frame e integrar `FaceLivenessDetector` de Amplify.
6. Ventana de despliegue: las migraciones van en el mismo arranque de la versión nueva. La versión
   anterior no entiende `verification_attempts.type` ni `profile_images.status`, así que no conviene
   dejar las dos corriendo a la vez sobre la misma base de datos.

## 14. Pendientes de esta iteración

| Tema | Situación |
| --- | --- |
| **Veredicto asíncrono** | `POST /liveness/sessions/{id}/complete` se llama desde el cliente. Si quiere desacoplarse por completo (Lambda + webhook de Rekognition), el punto de entrada ya son los servicios, no los controladores. |
| **Reintento tras un rechazo** | Un `REJECTED` o un `MANUAL_REVIEW` por fallo de vida no se puede reabrir desde la app; sigue haciendo falta que un administrador lo haga. |
| **Notificaciones** | Cuando un caso cae en `MANUAL_REVIEW` nadie recibe un aviso. |
| **Copia de los audit images** | `audit-images-limit` está en 0: no se guarda ninguna imagen del intento fallido, solo la referencia. |
| **Retención temporal** | La política se aplica por evento (fin del intento); no hay un trabajo programado que borre evidencia antigua de casos abiertos. |
| **Rama sin push** | Los cambios de las dos iteraciones están en local. |
