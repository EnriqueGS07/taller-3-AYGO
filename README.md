## Arquitectura del Prototipo de taller 3 AYGO

### 1. Arquitectura general

- **Patrón:** microservicios sin servidor (serverless) respaldados por un backend de datos común.
- **Componentes principales:**

  - `Amazon API Gateway` expone un API REST única y desacopla a los consumidores de los servicios internos.
  - Cuatro `AWS Lambda` independientes (una por recurso: users, drivers, rides, payments) desplegadas de forma aislada para favorecer escalabilidad independiente y despliegues rápidos.
  - `MongoDB` desplegado en un `Amazon EC2` como base de datos operacional responsable de persistir los documentos de cada dominio.

- **Seguridad y configuración:** cada Lambda recibe las credenciales de conexión vía variables de entorno (`MONGO_URI`, `MONGO_DB`, `MONGO_COLLECTION`) y usa un cliente `MongoClient` reutilizable para minimizar latencia en conexiones frías.

### 2. Razones detrás de las decisiones tecnológicas

- **Microservicios:** encapsulan el ciclo de vida de cada subdominio (usuarios, conductores, viajes y pagos) y facilitan escalar únicamente los componentes con mayor carga (por ejemplo, pagos durante horarios pico).
- **AWS Lambda:** elimina la necesidad de administrar servidores, ofrece escalado automático y un modelo de costos basado en uso real, ideal para un prototipo académico que busca reducir operaciones.
- **Amazon API Gateway:** provee ruteo REST, control de versiones, autenticación futura y observabilidad centralizada sin construir un gateway propio.
- **MongoDB en EC2:** modelo flexible basado en documentos adecuado para las entidades con campos opcionales y evolución rápida del esquema. Se eligió EC2 para respetar el enunciado (montar el backend sobre una instancia administrada por el equipo) y comprender el ciclo completo de aprovisionamiento.

### 3. Modelado de dominio

- **Users:** representa pasajeros. Campos principales: `id`, `name`, `traveling` (booleano), `travel` (viaje vigente).
- **Drivers:** representa conductores y el estado de su vehículo. Campos: `id`, `name`, `car`, `traveling`, `travel`, `busy`.
- **Rides:** agrupa la relación conductor-pasajero. Campos: `id`, `driver`, `available`, `passengerId`.
- **Payments:** registra cobros asociados a viajes. Campos: `id`, `userId`, `rideId`, `amount`, `processed`, `transactionId`.
- **Relaciones clave:** `rides` vincula `drivers` ↔ `users`; `payments` referencia tanto al `userId` como al `rideId` que se liquida.

### 4. URIs, métodos HTTP y casos de uso

| Recurso           | URI base                   | Operaciones soportadas         | Descripción                                                                  |
| ----------------- | -------------------------- | ------------------------------ | ---------------------------------------------------------------------------- |
| Users             | `/users`                   | `GET`, `POST`, `PUT`           | Listado completo, creación y actualización de estado de viaje.               |
| Users (por id)    | `/users?id={userId}`       | `GET`                          | Consulta puntual.                                                            |
| Drivers           | `/drivers`                 | `GET`, `POST`, `PUT`           | Alta de conductor, listado y actualización de disponibilidad/vehículo.       |
| Drivers (por id)  | `/drivers?id={driverId}`   | `GET`                          | Consulta puntual.                                                            |
| Rides             | `/rides`                   | `GET`, `POST`, `PUT`           | Publicación de viaje, listado y asignaciones (estado disponible / pasajero). |
| Rides (por id)    | `/rides?id={rideId}`       | `GET`                          | Consulta puntual.                                                            |
| Payments          | `/payments`                | `GET`, `POST`, `PUT`, `DELETE` | Registro de cobro, actualización de procesamiento, listado y baja lógica.    |
| Payments (por id) | `/payments?id={paymentId}` | `GET`, `DELETE`                | Consulta puntual o eliminación.                                              |

- **Representación estándar:** JSON, utilizado tanto en solicitudes (`POST`/`PUT`) como en respuestas. Ejemplo de alta de conductor:
  ```json
  {
    "name": "Alice Martínez",
    "car": "EV-1234"
  }
  ```

### 5. Aplicación de los pasos de desarrollo solicitados

- **Modelado de objetos:** las clases internas `Create…Request`, `…UpdateRequest` y `…Summary` codifican el diagrama de clases solicitado y muestran cómo se organiza cada recurso.
- **Creación de URIs:** el API Gateway rutea los prefijos `/users`, `/drivers`, `/rides` y `/payments`, manteniendo consistencia y claridad para los consumidores.
- **Representaciones JSON:** el uso de `Gson` asegura que todos los recursos se serialicen con un formato uniforme, facilitando integración con frontends o herramientas de prueba.
- **Asignación de métodos HTTP:** cada Lambda valida el método entrante y responde con errores `405` si se invoca un verbo no soportado, reforzando la semántica REST.
- **Diseño microservicios:** la separación por carpetas (`users`, `drivers`, `rides`, `payments`) refleja la división de despliegues en Lambdas independientes, cumpliendo el requisito de escalabilidad y desarrollo modular.
- **Implementación en la nube:** el prototipo opera enteramente sobre API Gateway + Lambda + EC2 (MongoDB), tal como exigía la tarea.

### 6. Despliegue y configuración

- **Compilación:** cada módulo es un proyecto Maven que genera un `*-1.0-SNAPSHOT.jar` listo para subir como artefacto a Lambda.
- **Variables de entorno por Lambda:**
  - `MONGO_URI`: cadena de conexión al servidor MongoDB en EC2.
  - `MONGO_DB`: base de datos lógica compartida.
  - `MONGO_COLLECTION`: colección específica (drivers, users, payments o rides).
- **Permisos:** se recomienda asociar las Lambdas a un rol de ejecución con acceso restringido a CloudWatch Logs y secretos (si se usa AWS Secrets Manager para gestionar la URI).
- **API Gateway:** definir recursos y métodos que proxyeen directamente hacia cada Lambda, habilitando CORS cuando se consuma desde aplicaciones web.

### 7. Observabilidad y pruebas

- **Logs:** cada Lambda registra excepciones en CloudWatch Logs mediante el `Context` de AWS, permitiendo auditar errores y trazas.
- **Pruebas manuales:** se pueden efectuar con Postman o `curl` contra el endpoint del API Gateway, enviando cuerpos JSON y parámetros de consulta para operaciones puntuales.
- **Pruebas automáticas futuras:** agregar pruebas unitarias con JUnit y mocks de `MongoCollection` para validar reglas de negocio sin depender del entorno en la nube.
