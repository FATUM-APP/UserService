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
| **Detección de vida real** | El "liveness" es un frame comparado con Rekognition, no una prueba de vida activa (parpadeo, giro de cabeza). Si el negocio la exige, hay que añadirla. |
| **`MIGRATION_REPORT.md`** | Es el informe de una migración anterior; se conserva como histórico. |
| **Notificaciones** | Cuando un caso pasa a `MANUAL_REVIEW` no se avisa a nadie: falta correo o evento. |
| **Reintentos tras un rechazo** | Hoy un `REJECTED` definitivo exige que un administrador reabra el caso; no hay endpoint para eso. |
