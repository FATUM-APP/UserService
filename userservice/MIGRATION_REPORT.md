# Informe de refactor empresarial de UserService

**Objetivo:** separar el contrato HTTP del modelo persistente, hacer obligatorios `document` y `documentType`, y administrar el esquema de forma versionada.

## Qué definía el campo original

```java
@NotBlank
@NotNull
@Size(max = 30)
@Column(name = "DOCUMENT", unique = true, length = 30, nullable = false)
private String document;
```

| Anotación | Responsabilidad |
| --- | --- |
| `@NotBlank` | Bean Validation: rechaza `null`, texto vacío y sólo espacios. |
| `@NotNull` | Bean Validation: rechaza `null`; es redundante en un `String` que ya tiene `@NotBlank`. |
| `@Size(max = 30)` | Bean Validation: limita el texto recibido a treinta caracteres. |
| `@Column(name = "DOCUMENT")` | Mapea el atributo a la columna SQL. |
| `unique = true` | Expresa unicidad en el esquema. |
| `length = 30` | Expresa la longitud de la columna de texto. |
| `nullable = false` | Expresa una restricción `NOT NULL` en persistencia; no sustituye la validación de la API. |

La intuición del usuario era parcialmente correcta: la validación de peticiones debe vivir en DTOs, pero `@Column` y las demás anotaciones JPA sí pertenecen a la entidad. Además, las invariantes esenciales deben protegerse en el dominio y la base de datos, porque no todas las entidades se crean necesariamente desde un controller.[1] [2]

## Arquitectura implementada

| Componente | Decisión |
| --- | --- |
| `CreateUserRequest` | Contiene la validación de todos los campos obligatorios, incluido documento y tipo. |
| `UserUpdateRequest` | Sólo contiene username, teléfono, rol y ciudad; el documento deja de ser actualizable. |
| `UserResponse` | Define explícitamente el JSON público del usuario. |
| `ProfileImageResponse` | Contiene la URL prefirmada sin persistirla en JPA. |
| `UserMapper` | Convierte request a entidad y entidad a response. |
| `UserController` | Nunca devuelve `User` ni `ProfileImage`; devuelve DTOs. |
| `User` | Sólo contiene JPA y comportamiento del agregado; no contiene Jackson ni Bean Validation de transporte. |
| `ProfileImage` | Ya no contiene `@JsonIgnore`, `@Transient` ni `presignedUrl`. |
| `UserService` | Conserva reglas, unicidad y transacciones; no modifica el documento. |
| Flyway | Versiona la creación del esquema y el cambio de nulabilidad. |

## Obligatoriedad del documento

La entidad utiliza ahora:

```java
@Column(name = "DOCUMENT", nullable = false, unique = true, length = 30)
private String document;

@Enumerated(EnumType.STRING)
@Column(name = "DOCUMENT_TYPE", nullable = false, length = 20)
private DocumentType documentType;
```

El constructor público exige ambos valores y rechaza valores ausentes incluso si la entidad se instancia fuera de la API. El DTO de creación usa `@NotBlank` para `document` y `@NotNull` para `documentType`. Finalmente, Flyway establece `NOT NULL` en PostgreSQL.

> La protección se aplica en tres fronteras: **request**, **dominio** y **base de datos**. Cada una cubre una vía de entrada distinta y no reemplaza a las otras.

## Migración de base de datos

`V1__create_user_schema.sql` crea una base nueva con las restricciones definitivas. Para una base ya existente, `baseline-on-migrate` registra la versión inicial y `V2__require_username_and_document.sql` valida los datos históricos antes de activar `NOT NULL`.[3]

La migración no inventa documentos. Si hay registros incompletos, falla deliberadamente para permitir una estrategia de backfill aprobada por el negocio. La consulta de diagnóstico está incluida en el README.

Las migraciones se ejecutaron también sobre PostgreSQL 16. En una base limpia, `username`, `document` y `document_type` quedaron con `is_nullable = NO`. En una base simulada con un usuario incompleto, `V2` se detuvo antes de alterar el esquema y mostró el mensaje de backfill esperado.

## Correcciones adicionales

El repositorio recibido no compilaba porque el controller utilizaba un constructor de `User` con seis argumentos mientras la entidad actual exigía siete. La creación manual fue reemplazada por `UserMapper` y el nuevo constructor exige nueve campos coherentes con el contrato.

`updateUser` llamaba `setUsername(update.username())` directamente. Cuando el campo se omitía, podía convertir un username obligatorio en `null`. El servicio ahora ignora campos omitidos, valida conflictos y llama métodos explícitos como `changeUsername`, `changePhoneNumber`, `changeRole`, `changeCity` y `deactivate`.

## Validación automatizada

La suite cubre contexto Spring/JPA, controller, validación DTO, mapper/JSON, agregado de dominio, reglas del servicio, restricciones del repositorio y almacenamiento S3. La última ejecución produjo:

| Resultado | Valor |
| --- | ---: |
| Pruebas | 35 |
| Fallos | 0 |
| Errores | 0 |
| Omitidas | 0 |

## Archivos principales modificados o nuevos

| Archivo | Tipo |
| --- | --- |
| `User.java`, `ProfileImage.java` | Refactor de entidades. |
| `CreateUserRequest.java`, `UserUpdateRequest.java` | Contratos de entrada. |
| `UserResponse.java`, `ProfileImageResponse.java`, `UserMapper.java` | Nuevos contratos y mapeo. |
| `UserController.java`, `UserService.java` | Adaptación de capas. |
| `V1__create_user_schema.sql`, `V2__require_username_and_document.sql` | Migraciones nuevas. |
| `pom.xml`, `application.yaml`, `.env.example` | Flyway y validación de esquema. |
| Pruebas en `src/test/java/fatum` | Cobertura actualizada y ampliada. |

## Referencias

[1]: https://jakarta.ee/specifications/bean-validation/3.0/jakarta-bean-validation-spec-3.0.html "Jakarta Bean Validation 3.0"
[2]: https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1 "Jakarta Persistence 3.1"
[3]: https://documentation.red-gate.com/flyway/flyway-concepts/migrations "Flyway migrations"
[4]: https://gitlab.com/ccastano46-group/userservicejava.git "userservicejava"
