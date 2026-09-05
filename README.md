# Fatum UserService — Java, Spring Boot y AWS S3

Este microservicio utiliza Java 21, Spring Boot, PostgreSQL, Auth0 y Amazon S3. La API separa explícitamente **entidades JPA**, **DTOs HTTP**, **mapeo**, **reglas de negocio** y **persistencia**.

> Las entidades representan el estado persistente y las invariantes del dominio. Los DTOs definen el contrato JSON y concentran la validación de las peticiones.

## Arquitectura

| Capa | Responsabilidad |
| --- | --- |
| `fatum.controller` | Recibe requests validados y devuelve response DTOs. No expone entidades JPA. |
| `fatum.dto` | Define contratos de entrada/salida y contiene `UserMapper`. |
| `fatum.service` | Aplica reglas, unicidad, transacciones e integración S3. |
| `fatum.model` | Contiene entidades JPA y métodos de dominio, sin Jackson ni Bean Validation de transporte. |
| `fatum.repository` | Ejecuta consultas mediante Spring Data JPA. |
| `db/migration` | Versiona el esquema y sus restricciones con Flyway. |

Las anotaciones `@NotBlank`, `@NotNull`, `@Email`, `@Past` y `@Size` están en los DTOs de entrada. Las entidades conservan `@Column`, `@Id`, `@OneToOne` y otras anotaciones JPA porque éstas describen la persistencia, no la serialización ni la validación HTTP.[1] [2]

## Contrato de usuario

El alta requiere email, nombres, apellidos, teléfono, fecha de nacimiento, username, documento y tipo de documento. `document` y `documentType` son obligatorios desde la creación y no forman parte del DTO de actualización; por tanto, quedan inmutables en este servicio.

### Creación

```json
{
  "email": "user@example.com",
  "names": "Camilo",
  "surnames": "Castaño",
  "phoneNumber": "+573001112233",
  "birthDate": "1998-05-10",
  "username": "ccastano46",
  "document": "1000271422",
  "documentType": "ID"
}
```

### Actualización parcial

```json
{
  "username": "nuevoUsername",
  "phoneNumber": "+573009998877",
  "role": "PROFESSIONAL",
  "city": "Bogotá"
}
```

Los campos omitidos en la actualización no cambian. Los textos enviados explícitamente en blanco son rechazados por validación.

## Respuesta pública

Los controllers devuelven `UserResponse` y `ProfileImageResponse`. La entidad `ProfileImage` sólo persiste `id`, `imageKey` y su relación con `User`; la URL prefirmada se calcula en el servicio y se incorpora al DTO sin modificar la entidad.

```json
{
  "auth0Id": "auth0|123",
  "email": "user@example.com",
  "names": "CAMILO",
  "surnames": "CASTAÑO",
  "birthDate": "1998-05-10",
  "username": "ccastano46",
  "phoneNumber": "+573001112233",
  "role": "CLIENT",
  "isAuthenticated": false,
  "isActive": true,
  "document": "1000271422",
  "documentType": "ID",
  "city": null,
  "profileImage": null
}
```

## Endpoints

| Método | Ruta | Autoridad | Resultado |
| --- | --- | --- | --- |
| `POST` | `/users` | JWT válido | Crea el usuario y devuelve `UserResponse`. |
| `GET` | `/users/me` | JWT válido | Devuelve el usuario actual. |
| `PUT` | `/users/me` | JWT válido | Actualiza únicamente campos mutables. |
| `PUT` | `/users/me/profile-image` | JWT válido | Reemplaza la imagen mediante multipart. |
| `GET` | `/users/me/authenticated` | JWT válido | Consulta `isAuthenticated`. |
| `DELETE` | `/users/me` | JWT válido | Desactiva lógicamente el usuario. |
| `GET` | `/users/active?email=...` | `read:users` | Consulta actividad por email. |
| `GET` | `/users/search?names=...&surnames=...` | `read:users` | Busca por nombres y apellidos. |
| `GET` | `/users/username/{username}` | `read:users` | Busca por username. |

## Persistencia y migraciones

Flyway administra el esquema y Hibernate usa `ddl-auto=validate` por defecto.[3] La migración `V1` crea un esquema nuevo. La migración `V2` revisa bases existentes y falla con un mensaje claro si hay filas sin username, documento o tipo de documento antes de aplicar `NOT NULL`.

Antes de desplegar sobre una base con datos históricos, identifique y complete los usuarios afectados:

```sql
SELECT auth0_id, username, document, document_type
FROM users
WHERE username IS NULL
   OR BTRIM(username) = ''
   OR document IS NULL
   OR BTRIM(document) = ''
   OR document_type IS NULL;
```

No se asignan documentos ficticios automáticamente porque son datos de identidad y requieren una decisión de negocio.

## Variables de entorno

| Variable | Descripción | Ejemplo |
| --- | --- | --- |
| `DB_URL` | URL JDBC de PostgreSQL. | `jdbc:postgresql://localhost:5432/fatum_users` |
| `DB_USERNAME` | Usuario de PostgreSQL. | `postgres` |
| `DB_PASSWORD` | Contraseña de PostgreSQL. | `postgres` |
| `AUTH0_DOMAIN` | Dominio Auth0 sin protocolo. | `tenant.us.auth0.com` |
| `AUTH0_AUDIENCE` | Audience de Auth0. | `https://api.fatum.example` |
| `AWS_REGION` | Región del bucket S3. | `us-east-1` |
| `AWS_S3_BUCKET` | Bucket privado de imágenes. | `fatum-profile-images` |
| `JPA_DDL_AUTO` | Validación Hibernate. | `validate` |
| `FLYWAY_ENABLED` | Activa migraciones al arrancar. | `true` |
| `CORS_ALLOWED_ORIGINS` | Orígenes permitidos. | `http://localhost:5173` |
| `PORT` | Puerto HTTP. | `8080` |

## Compilación

```bash
./mvnw clean verify
```

## Referencias

[1]: https://jakarta.ee/specifications/bean-validation/3.0/jakarta-bean-validation-spec-3.0.html "Jakarta Bean Validation 3.0"
[2]: https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1 "Jakarta Persistence 3.1"
[3]: https://documentation.red-gate.com/flyway/flyway-concepts/migrations "Flyway migrations"
[4]: https://gitlab.com/ccastano46-group/userservicejava.git "userservicejava"
