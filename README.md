# Fatum UserService — Java, Spring Boot y AWS S3

Este microservicio utiliza Java 21, Spring Boot, Spring Web MVC, Spring Data JPA, PostgreSQL, Auth0 y Amazon S3. La estructura mantiene el patrón de controllers, services, repositories, models, DTO y configuración utilizado en el proyecto de referencia.

La identidad de las operaciones `/users/me` se obtiene exclusivamente desde el claim `sub` del JWT. El cuerpo de la petición no puede seleccionar otro usuario.

## Arquitectura

| Paquete | Responsabilidad |
| --- | --- |
| `fatum.controller` | Endpoints REST y manejo global de errores. |
| `fatum.service` | Reglas de negocio, transacciones y coordinación con S3. |
| `fatum.model` | Entidades JPA y comportamiento del dominio. |
| `fatum.repository` | Consultas Spring Data JPA. |
| `fatum.dto` | Contratos de entrada y salida. |
| `fatum.configuration` | Auth0, CORS y clientes AWS. |

## Endpoints

| Método | Ruta | Autoridad | Descripción |
| --- | --- | --- | --- |
| `POST` | `/users` | JWT válido | Crea el usuario actual. Requiere email, nombres, apellidos, teléfono y fecha de nacimiento. |
| `GET` | `/users/me` | JWT válido | Obtiene el perfil actual y una URL prefirmada para la imagen, si existe. |
| `PUT` | `/users/me` | JWT válido | Actualiza parcialmente username, teléfono, rol, ciudad y documento. |
| `PUT` | `/users/me/profile-image` | JWT válido | Reemplaza la imagen mediante `multipart/form-data`, campo `image`. |
| `GET` | `/users/me/authenticated` | JWT válido | Consulta el valor de `isAuthenticated`. |
| `DELETE` | `/users/me` | JWT válido | Desactiva lógicamente el usuario. |
| `GET` | `/users/active?email=...` | `read:users` | Consulta si un usuario está activo. |
| `GET` | `/users/search?names=...&surnames=...` | `read:users` | Busca por nombres y apellidos. |
| `GET` | `/users/username/{username}` | `read:users` | Busca por username. |

El endpoint de autenticación documental fue retirado. El estado `isAuthenticated` se conserva como atributo de usuario y puede ser validado por el método de dominio `authenticate(boolean)` cuando otra parte de la aplicación decida cambiarlo.

Swagger UI queda disponible en `/swagger-ui.html` y la especificación OpenAPI en `/api-docs`.

## Modelo y reglas de negocio

El alta requiere correo, nombres, apellidos, teléfono y fecha de nacimiento. El usuario debe tener al menos 18 años. El rol inicial es `CLIENT`; un usuario `PROFESSIONAL` requiere ciudad.

El documento y su tipo se registran juntos y no pueden cambiarse después de ser establecidos. Username, teléfono, correo y documento tienen restricciones de unicidad. La entidad mantiene `isAuthenticated` e `isActive`, pero no mantiene un segundo estado de autenticación documental.

### Ejemplo de creación

```json
{
  "email": "user@example.com",
  "names": "Camilo",
  "surnames": "Castaño",
  "phoneNumber": "+573001112233",
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

## AWS S3

Las imágenes se guardan en el bucket indicado por `AWS_S3_BUCKET`, bajo el prefijo `profile-images/`. La aplicación utiliza `S3Client` para cargar y eliminar objetos, y `S3Presigner` para generar URLs de lectura temporales. La URL se genera por quince minutos cuando el perfil se devuelve por la API.

El AWS SDK utiliza su cadena estándar de credenciales. En desarrollo se puede utilizar el perfil local de AWS, variables `AWS_ACCESS_KEY_ID` y `AWS_SECRET_ACCESS_KEY`, o `AWS_PROFILE`. En producción se recomienda asignar un IAM role a la tarea ECS, instancia, función o servicio donde se ejecute la aplicación, sin incluir credenciales en el repositorio.

El role de ejecución necesita permisos equivalentes a `s3:PutObject`, `s3:GetObject` y `s3:DeleteObject` sobre el prefijo usado por el servicio. Para generar URLs prefirmadas no es necesario exponer públicamente el bucket.

## Variables de entorno

| Variable | Descripción | Ejemplo |
| --- | --- | --- |
| `DB_URL` | URL JDBC de PostgreSQL. | `jdbc:postgresql://localhost:5432/fatum_users` |
| `DB_USERNAME` | Usuario de PostgreSQL. | `postgres` |
| `DB_PASSWORD` | Contraseña de PostgreSQL. | `postgres` |
| `AUTH0_DOMAIN` | Dominio Auth0 sin protocolo. | `tenant.us.auth0.com` |
| `AUTH0_AUDIENCE` | Audience de la API Auth0. | `https://api.fatum.example` |
| `AWS_REGION` | Región del bucket S3. | `us-east-1` |
| `AWS_S3_BUCKET` | Bucket S3 de imágenes. | `fatum-profile-images` |
| `CORS_ALLOWED_ORIGINS` | Orígenes separados por coma. | `http://localhost:5173` |
| `PORT` | Puerto HTTP; por defecto `8080`. | `8080` |
| `JPA_DDL_AUTO` | Estrategia Hibernate; por defecto `update`. | `validate` |

El archivo `.env.example` contiene una plantilla local. No debe sustituirse por credenciales reales dentro del control de versiones.

## Compilación y pruebas

```bash
./mvnw clean verify
```

Para ejecutar localmente, exporte las variables necesarias y utilice:

```bash
./mvnw spring-boot:run
```

Para generar la imagen del contenedor:

```bash
./mvnw clean package
docker build -t fatum-user-service .
docker run --rm -p 8080:8080 --env-file .env fatum-user-service
```

## Archivos modificados o nuevos en esta actualización

La integración requiere reemplazar `pom.xml`, `.env.example`, `README.md`, `MIGRATION_REPORT.md`, `application.yaml`, `GlobalExceptionHandler.java`, `UserController.java`, `CreateUserRequest.java`, `UserService.java` y las pruebas correspondientes. Debe eliminarse `CloudStorageConfig.java` y añadirse `AwsS3Config.java`.

## Referencias

[1]: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html "AWS SDK for Java 2.x — S3 pre-signed URLs"
[2]: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html "AWS SDK for Java 2.x — Default credentials provider chain"
[3]: https://gitlab.com/ccastano46-group/userservicejava.git "userservicejava actualizado"
