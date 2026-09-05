# Informe de migración de UserService

**Autor:** Manus AI  
**Resultado:** Proyecto Java 21 + Spring Boot compilable, ejecutable, probado y preparado para PostgreSQL, Auth0, Google Cloud Storage y Cloud Run.

## Resumen ejecutivo

Se reconstruyó el proyecto Kotlin/Ktor adjunto como un microservicio Java con Spring Boot. La solución adopta el patrón de capas y las convenciones observadas en **LocalMarketBackend-2** —controllers, services, repositories, models, DTO, configuración y excepción global— y recupera la especificación funcional de **fatumUserService**, incluida su implementación Java histórica y sus pruebas.[1] [2]

La migración no fue una traducción mecánica. El proyecto Kotlin estaba incompleto y no compilaba como una implementación funcional del dominio: sólo exponía `GET /`, no configuraba una base de datos y `UserService.kt` hacía referencia a propiedades ausentes como `phoneNumber`, `role`, `isActive` y `documentIsAuthenticated` dentro de sus contratos. Esas inconsistencias se resolvieron mediante un modelo coherente y DTOs explícitos.

## Resultado técnico

| Área | Implementación entregada |
| --- | --- |
| Plataforma | Java 21, Spring Boot 3.3.0 y Maven Wrapper. |
| HTTP | Spring Web MVC con `UserController` y respuestas `ResponseEntity`. |
| Persistencia | Spring Data JPA, Hibernate y PostgreSQL. |
| Seguridad | OAuth2 Resource Server, JWT Auth0, validación de issuer y audience. |
| Identidad | Las operaciones personales derivan `auth0Id` exclusivamente del claim `sub`. |
| Archivos | Google Cloud Storage, reemplazo de imagen anterior y URL firmada V4 durante 15 minutos. |
| Validación | Jakarta Validation, reglas de dominio y errores REST estructurados. |
| Documentación | README, `.env.example`, Swagger UI y OpenAPI. |
| Despliegue | JAR ejecutable y Dockerfile Java 21 compatible con Cloud Run. |
| Calidad | 30 pruebas automatizadas y prueba de arranque del contexto Spring. |

## Funcionalidades implementadas

| Caso de uso | Estado | Observación |
| --- | --- | --- |
| Crear usuario | Implementado | Verifica campos obligatorios, mayoría de edad, auth0Id y email únicos. |
| Obtener usuario actual | Implementado | Usa el JWT y agrega URL firmada a la imagen. |
| Actualizar usuario | Implementado | Actualización parcial de username, teléfono, rol, ciudad y documento. |
| Autenticar documento | Implementado | Verifica existencia del documento y recalcula autenticación. |
| Consultar autenticación | Implementado | Devuelve el estado actual. |
| Desactivar usuario | Implementado | Desactivación lógica mediante `isActive=false`. |
| Actualizar imagen | Implementado | Carga multipart en GCS y elimina el objeto anterior después de persistir el reemplazo. |
| Consultar actividad por email | Implementado | Conserva el caso de uso agregado en la versión Python. |
| Buscar por nombres y apellidos | Implementado | Conserva la intención del repositorio Kotlin. |
| Buscar por username | Implementado | Conserva la intención del repositorio Kotlin. |

## Contratos REST

| Método | Ruta | Resultado principal |
| --- | --- | --- |
| `POST` | `/users` | `201 Created` con el usuario creado. |
| `GET` | `/users/me` | `200 OK` con el perfil actual. |
| `PUT` | `/users/me` | `200 OK` con el perfil actualizado. |
| `PUT` | `/users/me/profile-image` | `200 OK`; multipart con campo `image`. |
| `POST` | `/users/me/authenticate-document` | `200 OK` con el resultado de la verificación. |
| `GET` | `/users/me/authenticated` | `200 OK` con el estado de autenticación. |
| `DELETE` | `/users/me` | `204 No Content`. |
| `GET` | `/users/active?email=...` | `200 OK`; requiere `SCOPE_read:users`. |
| `GET` | `/users/search?names=...&surnames=...` | `200 OK`; requiere `SCOPE_read:users`. |
| `GET` | `/users/username/{username}` | `200 OK`; requiere `SCOPE_read:users`. |

## Correcciones de diseño relevantes

La actualización de usuario emplea un DTO real y completo. Esto evita el problema del Kotlin adjunto, donde el servicio utilizaba campos no declarados. El documento y el tipo de documento deben enviarse juntos y quedan inmutables después de su registro. Además, se restauró el campo `documentIsAuthenticated`, indispensable para el flujo heredado.

La transición a `PROFESSIONAL` acepta ciudad y rol en una misma solicitud. Primero se calcula el estado final efectivo y después se aplican los cambios; así se evita el defecto de la implementación histórica que intentaba asignar el rol antes de establecer la ciudad.

La imagen de perfil conserva una relación uno-a-uno con eliminación de huérfanos. El servicio carga el nuevo objeto, persiste la referencia y sólo después elimina el objeto anterior, reduciendo el riesgo de que el perfil quede apuntando a una imagen inexistente si la persistencia falla.

La API mantiene los nombres JSON históricos `isAuthenticated`, `isActive` y `documentIsAuthenticated`, con una prueba de regresión que evita propiedades booleanas duplicadas.

## Seguridad

Las rutas Swagger son públicas. Todos los casos de uso personales requieren un JWT válido. Las consultas administrativas por email, nombre o username requieren la autoridad `SCOPE_read:users`, que Spring deriva normalmente de un scope Auth0 `read:users`.

> El proyecto valida tanto el **issuer** como el **audience** del JWT y nunca toma el `auth0Id` de un cuerpo controlado por el cliente para operar sobre `/users/me`.

## Validación ejecutada

Se ejecutó desde cero:

```bash
./mvnw clean verify
```

| Suite | Pruebas | Fallos | Errores |
| --- | ---: | ---: | ---: |
| Arranque de Spring y mapeo JPA | 1 | 0 | 0 |
| `UserController` | 5 | 0 | 0 |
| Dominio `User` y contrato JSON | 5 | 0 | 0 |
| `UserService` | 19 | 0 | 0 |
| **Total** | **30** | **0** | **0** |

El proceso generó correctamente el artefacto ejecutable `target/user-service-1.0.0-SNAPSHOT.jar`.

## Configuración requerida

| Variable | Uso |
| --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Conexión PostgreSQL. |
| `AUTH0_DOMAIN`, `AUTH0_AUDIENCE` | Validación de JWT. |
| `GCS_BUCKET` | Almacenamiento de imágenes. |
| `CORS_ALLOWED_ORIGINS` | Orígenes permitidos separados por coma. |
| `PORT` | Puerto HTTP, con valor predeterminado `8080`. |
| `JPA_DDL_AUTO` | Política de esquema, con valor predeterminado `update`. |

Para producción conviene cambiar `JPA_DDL_AUTO` a `validate` después de administrar el esquema mediante migraciones controladas. La cuenta de servicio de Cloud Run deberá tener permisos de creación, lectura firmada y eliminación de objetos en el bucket configurado.

## Estructura entregada

```text
UserServiceJava/
├── .mvn/wrapper/
├── src/main/java/fatum/
│   ├── configuration/
│   ├── controller/
│   ├── dto/
│   ├── exception/
│   ├── model/
│   ├── repository/
│   ├── service/
│   └── UserServiceApplication.java
├── src/main/resources/application.yaml
├── src/test/java/fatum/
├── .env.example
├── Dockerfile
├── pom.xml
├── README.md
└── MIGRATION_REPORT.md
```

## Referencias

[1]: https://gitlab.com/ccastano46/LocalMarketBackend-2 "LocalMarketBackend-2"
[2]: https://gitlab.com/ccastano46-group/fatumuserservice "fatumUserService"
