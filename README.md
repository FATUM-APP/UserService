# Fatum UserService — Java y Spring Boot

Este proyecto es la migración del prototipo **Kotlin/Ktor** a un microservicio **Java 21 con Spring Boot y controladores REST**. La estructura y las convenciones siguen el patrón de [LocalMarketBackend-2][1], mientras que las reglas de usuario preservan y completan el comportamiento de [fatumUserService][2].

## Arquitectura

El servicio utiliza Spring Web MVC para HTTP, Spring Data JPA para PostgreSQL, Spring Security como OAuth2 Resource Server con Auth0 y Google Cloud Storage para imágenes de perfil. La lógica de negocio permanece en `UserService`; los controladores sólo procesan los contratos HTTP y delegan las operaciones.

| Paquete | Responsabilidad |
| --- | --- |
| `fatum.controller` | Endpoints REST y manejo global de errores. |
| `fatum.service` | Reglas de negocio, transacciones e integración con GCS. |
| `fatum.model` | Entidades JPA y comportamiento del dominio. |
| `fatum.repository` | Consultas Spring Data JPA. |
| `fatum.dto` | Contratos de entrada y salida. |
| `fatum.configuration` | Auth0, CORS y cliente de almacenamiento. |

## Endpoints

Las rutas `/users/me` usan siempre el claim **`sub`** del JWT como `auth0Id`; el cliente no puede seleccionar otro usuario mediante el cuerpo de la solicitud.

| Método | Ruta | Autoridad | Descripción |
| --- | --- | --- | --- |
| `POST` | `/users` | JWT válido | Crea el usuario actual. |
| `GET` | `/users/me` | JWT válido | Obtiene el perfil actual. |
| `PUT` | `/users/me` | JWT válido | Actualiza parcialmente el perfil. |
| `PUT` | `/users/me/profile-image` | JWT válido | Reemplaza la imagen mediante `multipart/form-data`, campo `image`. |
| `POST` | `/users/me/authenticate-document` | JWT válido | Marca el documento como autenticado. |
| `GET` | `/users/me/authenticated` | JWT válido | Consulta si el perfil está autenticado. |
| `DELETE` | `/users/me` | JWT válido | Desactiva lógicamente el usuario. |
| `GET` | `/users/active?email=...` | `read:users` | Consulta si un usuario está activo. |
| `GET` | `/users/search?names=...&surnames=...` | `read:users` | Busca por nombres y apellidos. |
| `GET` | `/users/username/{username}` | `read:users` | Busca por nombre de usuario. |

Swagger UI queda disponible en `/swagger-ui.html` y la especificación OpenAPI en `/api-docs`.

## Modelo y reglas de negocio

El alta requiere correo, nombres, apellidos y fecha de nacimiento. El usuario debe tener **18 años o más**. El rol inicial es `CLIENT`; para cambiar a `PROFESSIONAL` debe existir una ciudad efectiva en la misma solicitud o en el perfil actual.

El usuario queda autenticado cuando tiene username, teléfono, documento, tipo de documento, documento verificado y, si es profesional, ciudad. El documento y su tipo se registran juntos y no pueden modificarse posteriormente. Username, teléfono, correo y documento tienen restricciones de unicidad.

### Ejemplo de creación

```json
{
  "email": "user@example.com",
  "names": "Camilo",
  "surnames": "Castaño",
  "birthDate": "1998-05-10"
}
```

### Ejemplo de actualización parcial

```json
{
  "username": "ccastano46",
  "phoneNumber": "+573001112233",
  "role": "PROFESSIONAL",
  "city": "Bogotá",
  "document": "1000271422",
  "documentType": "ID"
}
```

## Variables de entorno

| Variable | Descripción | Ejemplo |
| --- | --- | --- |
| `DB_URL` | URL JDBC de PostgreSQL. | `jdbc:postgresql://localhost:5432/fatum_users` |
| `DB_USERNAME` | Usuario de base de datos. | `postgres` |
| `DB_PASSWORD` | Contraseña de base de datos. | `postgres` |
| `AUTH0_DOMAIN` | Dominio del tenant Auth0 sin protocolo. | `tenant.us.auth0.com` |
| `AUTH0_AUDIENCE` | Audience de la API Auth0. | `https://api.fatum.example` |
| `GCS_BUCKET` | Bucket para imágenes de perfil. | `fatum-profile-images` |
| `CORS_ALLOWED_ORIGINS` | Orígenes separados por coma. | `http://localhost:5173` |
| `PORT` | Puerto HTTP; por defecto `8080`. | `8080` |
| `JPA_DDL_AUTO` | Estrategia Hibernate; por defecto `update`. | `validate` |

Google Cloud usa las credenciales por defecto del entorno. En desarrollo se puede definir `GOOGLE_APPLICATION_CREDENTIALS`; en Cloud Run se recomienda asignar acceso al bucket a la cuenta de servicio de la revisión.

## Compilación y pruebas

```bash
./mvnw clean verify
```

Para ejecutar localmente, exporte las variables del archivo `.env.example` y utilice:

```bash
./mvnw spring-boot:run
```

## Contenedor

```bash
./mvnw clean package

docker build -t fatum-user-service .
docker run --rm -p 8080:8080 --env-file .env fatum-user-service
```

## Decisiones de migración

El proyecto Kotlin adjunto tenía la estructura inicial de modelos y repositorios, pero sólo exponía un endpoint de prueba. Además, `UserService.kt` usaba propiedades que no existían en `UserUpdate` ni en `User`. Esta versión añade los contratos faltantes, restaura `documentIsAuthenticated`, utiliza JPA en lugar de Exposed y reemplaza el routing Ktor por controladores Spring MVC.

La versión histórica de fatumUserService estaba orientada a funciones Lambda. Esta migración elimina handlers y fábricas estáticas, utiliza el contenedor de dependencias de Spring Boot y ofrece una API HTTP convencional apta para Cloud Run, siguiendo la intención arquitectónica de LocalMarketBackend-2.[1] [2]

## Referencias

[1]: https://gitlab.com/ccastano46/LocalMarketBackend-2 "LocalMarketBackend-2"
[2]: https://gitlab.com/ccastano46-group/fatumuserservice "fatumUserService"
