# Informe de actualización de UserService

**Objetivo:** adaptar el repositorio actualizado para eliminar el estado documental secundario y reemplazar Google Cloud Storage por Amazon S3.

## Cambios realizados

| Área | Antes | Ahora |
| --- | --- | --- |
| Almacenamiento | Google Cloud Storage (`Storage`, `BlobInfo`, `BlobId`). | AWS SDK v2 con `S3Client` y `S3Presigner`. |
| Configuración | `gcp.storage.bucket` y `GCS_BUCKET`. | `aws.region`, `aws.s3.bucket`, `AWS_REGION` y `AWS_S3_BUCKET`. |
| Credenciales | Credenciales por defecto de Google Cloud. | Cadena estándar de credenciales del AWS SDK. |
| URL de imagen | URL firmada V4 de GCS. | URL prefirmada de lectura de S3 con duración de quince minutos. |
| Excepciones de infraestructura | `StorageException`. | `SdkException`. |
| Modelo | No contenía el campo retirado, pero el servicio aún tenía referencias a su método asociado. | Servicio, controller y pruebas sin ese flujo. |
| API | Incluía `POST /users/me/authenticate-document`. | La ruta fue eliminada. Se conserva `GET /users/me/authenticated`. |
| Alta | El controller no enviaba el teléfono al constructor actual. | `CreateUserRequest` y `UserController` incluyen `phoneNumber`. |

## Archivos a reemplazar

| Archivo | Motivo |
| --- | --- |
| `pom.xml` | Sustituye la dependencia de Google Cloud por el BOM y módulo S3 del AWS SDK v2. |
| `.env.example` | Reemplaza `GCS_BUCKET` por `AWS_REGION` y `AWS_S3_BUCKET`. |
| `src/main/resources/application.yaml` | Reemplaza el bloque `gcp` por el bloque `aws`. |
| `src/main/java/fatum/service/UserService.java` | Implementa carga, eliminación y URLs prefirmadas con S3; elimina el flujo documental retirado. |
| `src/main/java/fatum/controller/UserController.java` | Elimina el endpoint documental y completa el alta con teléfono. |
| `src/main/java/fatum/controller/GlobalExceptionHandler.java` | Usa `SdkException` en lugar de la excepción de GCP. |
| `src/main/java/fatum/dto/CreateUserRequest.java` | Añade el teléfono obligatorio de la entidad actual. |
| `src/test/java/fatum/UserServiceApplicationTest.java` | Sustituye mocks y propiedades GCP por AWS. |
| `src/test/java/fatum/model/UserTest.java` | Elimina asserts del estado retirado. |
| `src/test/java/fatum/controller/UserControllerTest.java` | Actualiza el constructor de alta y elimina la ruta retirada. |
| `src/test/java/fatum/service/UserServiceTest.java` | Migra mocks de GCP a S3 y prueba cargas, borrados y URLs prefirmadas. |
| `README.md` y `MIGRATION_REPORT.md` | Actualizan la documentación para no referenciar GCP ni la ruta retirada. |

## Archivo a eliminar

Debe eliminarse `src/main/java/fatum/configuration/CloudStorageConfig.java`. Su reemplazo es `AwsS3Config.java`, que expone los beans `S3Client` y `S3Presigner` usando `AWS_REGION`.

## Reglas S3 implementadas

La imagen nueva se almacena con una clave como `profile-images/<uuid>-<nombre-original>`. Si el usuario ya tenía una imagen, la nueva referencia se persiste y posteriormente se solicita el borrado del objeto anterior. Las respuestas del usuario generan una URL prefirmada de lectura con duración de quince minutos; el bucket puede permanecer privado.

El SDK no recibe credenciales explícitas en el código. Utiliza el mecanismo estándar de AWS, que permite perfiles locales, variables de entorno y roles IAM en los entornos administrados.

## Regla de autenticación del usuario

El modelo conserva `isAuthenticated` e `isActive`, además de `document` y `documentType`. Cuando `authenticate(true)` se invoca, se verifica que exista documento y tipo; cuando se invoca con `false`, el estado se desactiva. Ya no existe un segundo booleano para representar una verificación documental ni un endpoint separado para ejecutar ese flujo.

## Validación

La primera compilación del repositorio actualizado fallaba por un carácter `#` en `UserService.java`. Ese error también fue corregido en el archivo reemplazado. Después de integrar los archivos entregados se debe ejecutar:

```bash
./mvnw clean verify
```

La suite actualizada cubre el arranque del contexto Spring, el controller, el modelo y el servicio con mocks de S3. La validación final se ejecutará sobre el repositorio ya modificado y se incluirá en la entrega.

## Referencias

[1]: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html "AWS SDK for Java 2.x — S3 pre-signed URLs"
[2]: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html "AWS SDK for Java 2.x — Default credentials provider chain"
[3]: https://gitlab.com/ccastano46-group/userservicejava.git "userservicejava actualizado"
