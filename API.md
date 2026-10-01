# API — UserService y FileService

Todos los endpoints del **UserService** salvo los dos públicos (`/users/validate-signup` y
`/users/register`) exigen un `Authorization: Bearer <JWT>` emitido por Cognito. El `sub` del token es el
`awsId` del usuario y **nunca** se recibe por parámetro: el servicio lo lee del token.

Los errores tienen siempre la misma forma:

```json
{
  "timestamp": "2026-10-01T12:00:00Z",
  "status": 409,
  "error": "Conflict",
  "message": "A user with that email already exists",
  "path": "/users/update",
  "validationErrors": {}
}
```

---

## 1. UserService

### 1.1 Cuenta

#### `POST /users/validate-signup` — público

Valida los datos antes de crear la cuenta en Cognito. No persiste nada.

```bash
curl -X POST http://localhost:8080/users/validate-signup \
  -H "Content-Type: application/json" \
  -d '{
        "awsId": "sub-de-cognito",
        "email": "user@example.com",
        "name": "Camilo Castaño",
        "phoneNumber": "+573001112233",
        "birthDate": "1998-05-10",
        "username": "ccastano46",
        "document": "1000271422",
        "documentType": "ID",
        "gender": "MALE"
      }'
```

| Código | Significado |
| --- | --- |
| `204` | los datos son válidos |
| `400` | falta un campo obligatorio, el usuario es menor de edad o el formato no es válido |
| `409` | el email, el username, el teléfono o el documento ya existen |

#### `POST /users/register` — público con secreto

Crea el usuario. Lo invoca la Lambda de post-confirmación de Cognito.

| Cabecera | Valor |
| --- | --- |
| `Lambda-Secret` | el valor de `LAMBDA_SECRET` |

Devuelve `201` con el `UserResponse`. Si el secreto no coincide: `403`.

#### `GET /users/me`

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

#### `PUT /users/update`

Actualización parcial: los campos ausentes no cambian.

```json
{
  "username": "nuevoUsername",
  "phoneNumber": "+573009998877",
  "role": "PROFESSIONAL",
  "city": "Medellín"
}
```

* `role = PROFESSIONAL` exige `city` (o una ciudad ya guardada); en caso contrario `400`.
* Al pasar a `PROFESSIONAL` el usuario se añade al grupo `PROFESSIONAL` de Cognito.

#### `GET /users/me/authenticated`

```json
{ "verificationStatus": "UNVERIFIED", "isVerified": false }
```

#### `PUT /users/deactivate?email=...`

Desactiva lógicamente la cuenta. Devuelve `204`.

### 1.2 Foto de perfil

#### `PUT /profile-image/update`

`multipart/form-data` con el campo `image` (JPEG, PNG o WebP).

* Antes de existir una verificación el cambio es inmediato.
* Si la cuenta ya está verificada, la imagen **no se publica todavía**: se guarda como `PENDING`, se
  compara en segundo plano con la referencia de vida y la respuesta llega con `pendingVerification:
  true`. El cambio nunca queda a revisión de una persona: se acepta o se descarta.

```json
{
  "image": {
    "id": "8f1c…",
    "originalFilename": "avatar.png",
    "contentType": "image/png",
    "size": 20480,
    "downloadUrl": "https://s3…/avatar.png?X-Amz-Signature=…",
    "createdAt": "2026-10-01T12:00:00Z",
    "updatedAt": "2026-10-01T12:00:00Z"
  },
  "pendingImage": {
    "id": "b3d9…",
    "originalFilename": "nueva.png",
    "contentType": "image/png",
    "size": 21330,
    "downloadUrl": "https://s3…/nueva.png?X-Amz-Signature=…",
    "createdAt": "2026-10-01T12:30:00Z",
    "updatedAt": "2026-10-01T12:30:00Z"
  },
  "pendingVerification": true,
  "lastChangeOutcome": "PENDING"
}
```

| Código | Significado |
| --- | --- |
| `400` | el archivo no es una imagen |
| `409` | `The account has no live reference…` (dato inconsistente) |
| `429` | demasiados cambios encolados en el día |
| `502` | el servicio de archivos no respondió |

#### `GET /profile-image`

Devuelve el mismo objeto. Es el recurso que el cliente consulta hasta que `pendingVerification` pase a
`false`; entonces `lastChangeOutcome` dice qué pasó con el último cambio:

| `lastChangeOutcome` | Significado |
| --- | --- |
| `PENDING` | la comparación sigue corriendo |
| `VERIFIED` | la imagen nueva sustituyó a la anterior |
| `REJECTED` | la imagen nueva se descartó; sigue viéndose la anterior |
| `null` | el usuario todavía no pidió ningún cambio |

Si el usuario no tiene foto, `image` es `null` (ya no responde `404`).

### 1.3 Documento de identidad

#### `POST /documents/upload`

`multipart/form-data`:

| Campo | Obligatorio | Descripción |
| --- | --- | --- |
| `type` | sí | `PASSPORT`, `ID` o `DRIVING_LICENSE` |
| `front` | sí | foto del frente |
| `back` | según el tipo | foto del reverso; obligatorio salvo en `PASSPORT` |

```json
{
  "id": "1c2d…",
  "documentType": "ID",
  "frontFilename": "frente.jpg",
  "backFilename": "reverso.jpg",
  "contentType": "image/jpeg",
  "frontSize": 145233,
  "backSize": 132011,
  "frontUrl": "https://s3…/front.jpg?X-Amz-Signature=…",
  "backUrl": "https://s3…/back.jpg?X-Amz-Signature=…",
  "createdAt": "2026-10-01T12:00:00Z",
  "updatedAt": "2026-10-01T12:00:00Z"
}
```

Volver a subir reemplaza el documento anterior y borra sus objetos. Solo se aceptan imágenes: un PDF
responde `400` con `Only image document files are allowed`.

#### `GET /documents` / `DELETE /documents`

Consulta el documento vigente o lo elimina junto con sus objetos (`204`).

### 1.4 Evidencia de vida (liveness)

La prueba de vida la ejecuta la app con `FaceLivenessDetector` de Amplify: la cámara se transmite
directamente a Rekognition y este servicio solo abre la sesión y recoge el veredicto. **No existe
endpoint para subir una imagen**: una foto tomada de antemano no prueba quién está frente a la pantalla.

#### `POST /liveness/sessions`

Abre una sesión, o devuelve una que siga viva si el cliente reintentó por un fallo de red. Solo se puede
llamar cuando `GET /verification/status` responde `livenessRequired: true`, es decir cuando la fase
gratuita ya pasó el umbral.

```json
{
  "sessionId": "3f0a1b…",
  "expiresAt": "2026-10-01T12:03:00Z",
  "reused": false
}
```

| Código | Significado |
| --- | --- |
| `409` | `The proof of life can only be requested after…` |
| `429` | ya se abrieron demasiadas sesiones para este intento |
| `503` | la prueba de vida está desactivada o no tiene bucket configurado |

#### `POST /liveness/sessions/{sessionId}/complete`

Pide el veredicto a Rekognition y, si ya hay decisión, cierra el intento. Es idempotente: reportar la
misma sesión dos veces devuelve la decisión registrada en lugar de volver a decidir.

```json
{
  "sessionId": "3f0a1b…",
  "status": "SUCCEEDED",
  "confidence": 96.4,
  "identityVerified": true,
  "userStatus": "VERIFIED",
  "outcome": "VERIFIED",
  "referenceDocumentMatch": 92.1,
  "flags": [],
  "summary": "Proof of life with confidence 96.40; the live reference matches the document (92.10)"
}
```

| `status` | Efecto |
| --- | --- |
| `SUCCEEDED` | se verifica solo si la referencia devuelta coincide con el documento |
| `FAILED` / `EXPIRED` | `MANUAL_REVIEW`, sin segundo intento |
| `PENDING` | Rekognition todavía procesa; el intento sigue abierto |

Un fallo técnico de Rekognition (`UNAVAILABLE`) responde `503` y **deja el intento abierto**, para que el
cliente pueda reportar la sesión otra vez.

#### `GET /liveness`

Devuelve la imagen de referencia de la cuenta (la que produjo Rekognition o, en una revisión manual, la
que adoptó un administrador) o `404` si todavía no existe.

### 1.5 Verificación

#### `GET /verification/status`

```json
{
  "status": "UNVERIFIED",
  "attemptsUsed": 1,
  "attemptsRemaining": 2,
  "canAttempt": false,
  "documentUploaded": true,
  "livenessCompleted": false,
  "profileImageUploaded": true,
  "livenessRequired": true,
  "lastAttempt": {
    "id": "9a8b…",
    "type": "FULL",
    "attemptNumber": 1,
    "band": "VERIFIED",
    "outcome": "AWAITING_LIVENESS",
    "decision": "SYSTEM",
    "score": 91.2,
    "documentMatch": 100.0,
    "documentProfileMatch": 95.0,
    "referenceDocumentMatch": 0.0,
    "livenessConfidence": null,
    "fraudRisk": 5.0,
    "summary": "Attempt 1/3 score 91.20 | …",
    "flags": [],
    "decidedBy": null,
    "notes": null,
    "createdAt": "2026-10-01T12:00:00Z"
  }
}
```

`livenessRequired` es lo que decide si la app debe abrir el componente de Face Liveness, y solo es `true`
después de que la fase gratuita pasó. Mientras lo sea, `canAttempt` es `false`.

#### `POST /verification/submit`

Ejecuta la **fase gratuita** con la evidencia ya subida. Si el resultado pasa el umbral, el intento queda
`AWAITING_LIVENESS` y hay que abrir una prueba de vida.

```json
{
  "userAwsId": "sub-de-cognito",
  "attemptNumber": 2,
  "attemptsUsed": 2,
  "attemptsRemaining": 1,
  "score": 91.2,
  "documentMatch": 100.0,
  "documentProfileMatch": 95.0,
  "referenceDocumentMatch": 0.0,
  "livenessConfidence": null,
  "fraudRisk": 5.0,
  "band": "VERIFIED",
  "outcome": "AWAITING_LIVENESS",
  "userStatus": "UNVERIFIED",
  "needsLiveness": true,
  "flags": [],
  "summary": "Attempt 2/3 score 91.20 | …",
  "decidedAt": "2026-10-01T12:00:00Z"
}
```

| Código | Significado |
| --- | --- |
| `400` | falta evidencia (`A profile picture and an identity document are required…`) |
| `409` | la cuenta ya está verificada o no quedan intentos |
| `502` | un servicio de AWS o el servicio de archivos no respondió |

#### `GET /verification/history`

Lista los intentos, del más reciente al más antiguo.

### 1.6 Revisión manual (rol `ADMIN`)

#### `GET /verification/admin/pending`

```json
[
  {
    "userAwsId": "sub-de-cognito",
    "name": "CAMILO CASTAÑO",
    "username": "ccastano46",
    "status": "MANUAL_REVIEW",
    "attemptsUsed": 3,
    "lastOutcome": "MANUAL_REVIEW",
    "lastScore": 46.5,
    "lastSummary": "Attempt 3/3 …",
    "lastAttemptAt": "2026-10-01T12:00:00Z"
  }
]
```

#### `POST /verification/admin/review`

`multipart/form-data`:

| Campo | Obligatorio | Descripción |
| --- | --- | --- |
| `userAwsId` | sí | usuario revisado |
| `verified` | sí | `true` confirma la identidad, `false` la rechaza |
| `notes` | no | queda registrado en el intento |
| `photo` | sí cuando `verified=true` | foto validada; pasa a ser la foto de perfil **y** la referencia de liveness |

También acepta `application/json` (`{"userAwsId":"…","verified":false,"notes":"…"}`) para rechazos sin
foto.

---

## 2. FileService

Microservicio de almacenamiento compartido. El cliente **no elige bucket**: elige una *ruta*
(`microservicio:propósito`) y el servicio resuelve bucket, prefijo, tipos permitidos y tamaño máximo.

| Cabecera | Valor |
| --- | --- |
| `X-Storage-Key` | el valor de `STORAGE_INTERNAL_SECRET` (vacío desactiva la comprobación en local) |

### `POST /files?route=user-service:document`

`multipart/form-data` con el campo `file`.

```json
{
  "id": "obj-1",
  "key": "documents/2026/10/01/8f1c…-frente.jpg",
  "bucket": "fatum-documents-902353451847-us-east-1-an",
  "route": "user-service:document",
  "originalFilename": "frente.jpg",
  "contentType": "image/jpeg",
  "size": 145233,
  "etag": "\"9c1f…\""
}
```

### `GET /files/url?route=user-service:document&key=documents/…`

```json
{
  "key": "documents/2026/10/01/8f1c…-frente.jpg",
  "bucket": "fatum-documents-902353451847-us-east-1-an",
  "route": "user-service:document",
  "url": "https://s3…?X-Amz-Signature=…",
  "expiresAt": "2026-10-01T12:15:00Z"
}
```

### `DELETE /files?route=user-service:document&key=documents/…`

`204`. Borra el objeto. El borrado es idempotente.

### `GET /files/routes`

Catálogo de rutas configuradas, útil para que cualquier equipo descubra qué puede subir y dónde.

```json
[
  {
    "route": "user-service:document",
    "bucket": "fatum-documents-902353451847-us-east-1-an",
    "prefix": "documents",
    "allowedContentTypes": ["image/jpeg", "image/png", "image/webp"],
    "maxSizeBytes": 10485760,
    "presignTtlSeconds": 900,
    "configured": true
  }
]
```

### `GET /actuator/health`

Estado del servicio.

---

## 3. Códigos de error frecuentes

| Código | Cuándo |
| --- | --- |
| `400` | validación, tipo de archivo no permitido, falta el reverso del documento |
| `401` | falta el token o no es válido |
| `403` | token válido sin permiso (por ejemplo, revisión manual sin ser `ADMIN`) |
| `404` | usuario, documento, liveness o foto inexistentes |
| `409` | duplicados, cuenta ya verificada, sin intentos, cambio de foto que no coincide |
| `502` | el servicio de archivos o un servicio de AWS no respondió |
| `503` | la verificación está desactivada (`VERIFICATION_ENABLED=false`) |
