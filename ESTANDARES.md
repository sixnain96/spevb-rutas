# ESTÁNDARES DEL EQUIPO – SEVB

**Código de sesión: llano-14**

## 1. Propósito

Este documento reúne los acuerdos de trabajo que vamos a seguir en el desarrollo del **SEVB – Sistema Electrónico de Buses de Villavicencio**, especialmente en el módulo de rutas, recorridos y paraderos.

La idea es que los dos integrantes trabajemos de la misma manera y que cualquier persona que revise el repositorio pueda entender cómo organizamos el código, cómo hacemos los commits, cuándo una tarea está lista para empezar, cuándo realmente está terminada y cómo revisamos los cambios antes de subirlos a `main`.

## 2. Contexto del proyecto

Actualmente el proyecto está desarrollado con las siguientes herramientas:

- **Lenguaje:** Java 21
- **Framework:** Spring Boot 4.1.1
- **Gestor de dependencias:** Maven
- **Base de datos:** PostgreSQL con PostGIS
- **Contenedores:** Docker y Docker Compose
- **Frontend:** HTML, CSS y JavaScript
- **Mapa:** Leaflet
- **IDE:** IntelliJ IDEA
- **Control de versiones:** Git
- **Repositorio:** GitHub
- **Repositorio remoto:** https://github.com/sixnain96/spevb-rutas
- **Análisis estático:** PMD 7 (maven-pmd-plugin)
- **Cobertura de pruebas:** JaCoCo (jacoco-maven-plugin)

En este momento el sistema permite trabajar con rutas, recorridos, paraderos, consultas mediante API REST, visualización en el mapa y edición de recorridos.

## 3. Integrantes y roles

El equipo está conformado por dos integrantes. Como la actividad exige cuatro roles, cada integrante asume dos responsabilidades diferentes. Aunque una persona tenga dos roles, las funciones de cada uno se mantienen separadas.

### Juan José Montoya Garzón

#### Guardián de lo verificable

Se encarga de revisar cada acuerdo preguntando:

> ¿Cómo lo comprueba alguien que no estuvo aquí?

Si una regla no se puede revisar desde el repositorio, GitHub o ejecutando el proyecto, se debe modificar o eliminar.

#### Responsable del repositorio

Se encarga de:

- Crear y actualizar `ESTANDARES.md`.
- Revisar que los archivos acordados estén en el repositorio.
- Verificar que los commits respeten la convención definida.
- Manejar las ramas cuando sea necesario.
- Subir los cambios a GitHub.
- Confirmar que los cambios aprobados queden en `main`.

### Santiago Saray Quevedo

#### Redactor

Se encarga de escribir en el documento lo que el equipo acuerde. El redactor no toma decisiones por su cuenta, sino que organiza y deja por escrito lo definido entre los dos integrantes.

#### Abogado del diablo

Se encarga de plantear situaciones en las que un acuerdo podría ser difícil de cumplir. Si el caso planteado es realista, se revisa la regla y se ajusta antes de aceptarla.

## 4. Guía de estilo y nombres

### 4.1 Guía adoptada

Para el código Java usamos como referencia la **Google Java Style Guide**, junto con las convenciones normales de Java y Spring Boot.

La guía funciona como referencia común para mantener consistencia. Cuando exista una regla específica acordada por el equipo y documentada en este archivo o en `.editorconfig`, esa regla tendrá prioridad dentro del proyecto.

### 4.2 Idioma del código

Los conceptos que hacen parte directamente del sistema se nombran principalmente en español.

Ejemplos: `Ruta`, `Paradero`, `Recorrido`, `listarRutas()` y `actualizarRecorrido()`.

Los nombres técnicos propios de Spring Boot se mantienen en inglés, por ejemplo: `Controller`, `Service`, `Repository`, `Request`, `Response` y `Exception`.

De esta manera mantenemos el código relacionado con el problema en español sin cambiar términos técnicos que normalmente se utilizan en Java y Spring.

### 4.3 Formateador

El formateador que utilizamos es el integrado en **IntelliJ IDEA**:

`Code > Reformat Code`

Atajo en Windows:

`Ctrl + Alt + L`

También usamos un archivo `.editorconfig` en la raíz del proyecto para compartir las reglas básicas de formato. Antes de hacer un commit con cambios de código, se debe aplicar el formateador.

### 4.5 Análisis estático y cobertura

Usamos **PMD 7** como herramienta de análisis estático y **JaCoCo** para medir la cobertura de pruebas. Las dos se ejecutan con:

`mvn verify`

Las reglas de PMD están en `config/pmd/sevb-ruleset.xml`: el conjunto recomendado `quickstart` y la complejidad ciclomática con un máximo de **10 por método**.

**Verificación:** después de `mvn verify`, el reporte de PMD queda en `target/pmd.xml` (y su versión HTML, `pmd.html`, dentro de `target`) y el de cobertura en `target/site/jacoco/index.html`.

### 4.4 Reglas de nombres

#### Regla 1. Clases

Las clases Java se escriben en **PascalCase**. Cuando una clase pertenece a una capa específica, el nombre debe ayudar a identificar su función.

Ejemplos: `RutaController`, `RutaService`, `RutaRepository`, `RutaDetalleResponse` y `RutaNoEncontradaException`.

#### Regla 2. Métodos y variables

Los métodos y variables se escriben en **camelCase**. No usamos tildes, espacios ni caracteres especiales, y evitamos abreviaturas difíciles de interpretar.

Ejemplos correctos: `listarRutas()`, `obtenerRuta()`, `actualizarRecorrido()`, `rutaService` y `rutaId`.

Es mejor usar `obtenerRuta()` que `obtR()`.

#### Regla 3. Base de datos

Las tablas y columnas de PostgreSQL se escriben en **snake_case**.

Ejemplos: `rutas`, `paraderos`, `ruta_id`, `color_hex`, `creado_en` y `actualizado_en`.

## 5. Convención de commits

Los commits deben utilizar el formato:

`tipo(alcance): descripción`

Ejemplo:

`feat(rutas): agrega consulta de rutas activas`

La idea es que una persona pueda revisar el historial y entender qué se cambió sin tener que abrir todos los archivos.

### 5.1 Tipos permitidos

- **feat:** nueva funcionalidad. Ejemplo: `feat(paraderos): agrega registro de paraderos`.
- **fix:** corrección de un error. Ejemplo: `fix(editor): corrige actualización del recorrido`.
- **docs:** cambios de documentación. Ejemplo: `docs(estandares): agrega estándares del equipo`.
- **refactor:** cambios internos del código sin agregar una nueva funcionalidad. Ejemplo: `refactor(rutas): reorganiza lógica del servicio`.
- **test:** creación o modificación de pruebas. Ejemplo: `test(rutas): agrega pruebas de consulta`.
- **chore:** cambios técnicos o de configuración. Ejemplo: `chore(docker): ajusta configuración de postgis`.

### 5.2 Mensajes que evitamos

No usamos mensajes como `cambios`, `arreglo`, `prueba`, `final` o `cosas nuevas`, porque no explican realmente qué se hizo.

## 6. Convención de ramas

### 6.1 Rama principal

`main`

Esta rama representa la versión estable del proyecto. No se deben desarrollar nuevas funcionalidades directamente sobre `main`.

### 6.1.1 Rama de integración

`dev`

Las ramas `feature/`, `fix/` y `docs/` se integran primero a `dev` mediante un Pull Request revisado por el otro integrante. Cuando `dev` está estable (`mvn clean test` termina sin errores y la aplicación inicia), se abre un Pull Request de `dev` a `main`, que también revisa el otro integrante. No se integran cambios directamente a `main` desde una rama de trabajo.

### 6.2 Nuevas funcionalidades

Formato:

`feature/nombre-corto`

Ejemplos: `feature/gestion-paraderos`, `feature/editor-recorridos` y `feature/mapa-rutas`.

### 6.3 Correcciones

Formato:

`fix/nombre-corto`

Ejemplos: `fix/carga-rutas` y `fix/conexion-postgis`.

### 6.4 Documentación

Formato:

`docs/nombre-corto`

Ejemplo: `docs/estandares-equipo`.

## 7. Definition of Ready – DoR

Una tarea está lista para comenzar cuando cumple estas condiciones:

1. Tiene una descripción clara de lo que se debe desarrollar, modificar o corregir.
2. Tiene al menos un criterio de aceptación que permita saber cuándo se terminó correctamente.
3. Está identificado el componente del SEVB que se va a modificar, por ejemplo API, base de datos, rutas, paraderos, mapa, editor o frontend.
4. Están disponibles los datos, archivos, servicios o decisiones necesarias para realizar la tarea.
5. Está definido quién realizará la tarea y en qué rama se va a trabajar.

Si una tarea no cumple estas cinco condiciones, todavía no se considera lista para comenzar.

## 8. Definition of Done – DoD

Una tarea se considera terminada únicamente cuando otra persona puede comprobar los siguientes puntos.

### 8.1 El proyecto compila

Desde la raíz del proyecto se debe ejecutar:

`mvn clean test`

**Verificación:** el comando debe terminar sin errores de compilación.

### 8.2 La base de datos puede iniciar

Desde la raíz del proyecto se ejecuta:

`docker compose up -d`

Después se revisa con:

`docker ps`

**Verificación:** debe aparecer el contenedor de PostgreSQL/PostGIS funcionando. En nuestro proyecto actualmente aparece como `spevb-postgis`.

### 8.3 Spring Boot inicia correctamente

Se debe ejecutar `SpevbRutasApplication`.

**Verificación:** la aplicación debe iniciar sin errores y quedar disponible en el puerto configurado. Actualmente usamos el puerto `8080`.

### 8.4 La funcionalidad se puede probar

Si el cambio corresponde al backend, debe existir un endpoint o una acción concreta para comprobarlo, por ejemplo `/api/rutas` o `/api/rutas/{id}`.

Si el cambio corresponde a la interfaz, se debe poder revisar desde la página modificada, por ejemplo `index.html` o `editor.html`.

**Verificación:** el resultado observado debe coincidir con el criterio de aceptación definido en la tarea.

### 8.5 Los cambios de base de datos quedan guardados en el repositorio

Si una tarea modifica tablas, columnas o datos iniciales, esos cambios deben quedar registrados en archivos del proyecto, actualmente `schema.sql` y `data.sql`.

**Verificación:** un tercero debe poder encontrar en el repositorio los cambios necesarios para reproducir la base de datos. Un cambio que solo exista en la base local de un integrante no se considera terminado.

### 8.6 No se suben archivos innecesarios

El repositorio no debe contener archivos generados o locales que deberían estar ignorados, como `target/`, logs, archivos temporales o configuraciones locales innecesarias.

**Verificación:** se revisan el repositorio en GitHub y el archivo `.gitignore`.

### 8.7 El commit cumple la convención

El cambio debe estar registrado con el formato `tipo(alcance): descripción`.

Ejemplo: `fix(rutas): corrige consulta de recorrido`.

**Verificación:** se revisa el historial de Git.

### 8.8 El otro integrante revisó el cambio

Antes de integrar un cambio importante a `main`, el otro integrante debe revisarlo.

**Verificación:** debe existir evidencia mediante Pull Request, comentario de revisión o aprobación registrada en GitHub.

### 8.9 La calidad medible no empeora

Ningún método nuevo o modificado supera una complejidad ciclomática de 10 según PMD, y la cobertura de líneas reportada por JaCoCo no baja frente a la última medición registrada.

**Verificación:** se ejecuta `mvn verify` y se revisan los reportes de PMD y JaCoCo.

Si alguno de estos nueve puntos no se puede comprobar, la tarea todavía no se considera terminada.

## 9. Política de revisión

### 9.1 Quién revisa

El integrante que no realizó el cambio es quien debe revisarlo.

- Si desarrolla **Juan José Montoya Garzón**, revisa **Santiago Saray Quevedo**.
- Si desarrolla **Santiago Saray Quevedo**, revisa **Juan José Montoya Garzón**.

### 9.2 Plazo

La revisión debe realizarse en un máximo de **24 horas** después de solicitarla.

## 10. Qué bloquea una integración

Un cambio no se puede integrar a `main` cuando:

1. `mvn clean test` termina con error.
2. Spring Boot no inicia por un cambio realizado.
3. La funcionalidad no cumple el criterio de aceptación.
4. El cambio rompe una funcionalidad existente relacionada.
5. Se suben contraseñas, tokens o información sensible.
6. Se incluyen archivos que deberían estar ignorados.
7. Hay conflictos de Git sin resolver.
8. Los cambios de base de datos no están registrados en el repositorio.
9. El commit no sigue la convención.
10. No existe una forma clara de probar el cambio.
11. Un método nuevo o modificado supera una complejidad ciclomática de 10 según PMD.

## 11. Qué no bloquea una integración

Las siguientes situaciones pueden generar una sugerencia, pero no bloquean por sí solas el cambio:

1. Preferencias personales de estilo que no contradigan estas reglas.
2. Ideas para funcionalidades futuras.
3. Otra forma de implementar una solución cuando ambas cumplen el mismo criterio de aceptación.
4. Cambios visuales menores que no afectan la funcionalidad.
5. Refactorizaciones que puedan hacerse después y no afecten la tarea actual.

## 12. Forma de hacer comentarios

Los comentarios de revisión deben decir claramente:

`archivo o componente + problema + cambio esperado`

Ejemplo:

> `RutaService.java`: cuando la ruta no existe no se está controlando el caso. Agregar el manejo correspondiente antes de aprobar.

Otro ejemplo:

> `schema.sql`: falta registrar la nueva columna utilizada por el servicio. Agregarla para que la base de datos pueda reproducirse desde el repositorio.

Evitamos comentarios como “Está mal”, “No sirve” o “Cambie eso”, porque no ayudan a entender qué debe corregirse.

## 13. Declaración sobre uso de inteligencia artificial

Para la elaboración de este documento se utilizó una herramienta de inteligencia artificial como apoyo para organizar la información, revisar la redacción y proponer una estructura inicial.

Los estándares, decisiones técnicas y acuerdos incluidos fueron revisados y ajustados por **Juan José Montoya Garzón** y **Santiago Saray Quevedo**, teniendo en cuenta el funcionamiento real del proyecto SEVB, las herramientas utilizadas por el equipo y las condiciones indicadas para la actividad.

La versión publicada en el repositorio corresponde a los acuerdos aceptados por los integrantes del equipo.

## 14. Aceptación

**Juan José Montoya Garzón:** conozco y acepto estos estándares.

**Santiago Saray Quevedo:** conozco y acepto estos estándares.

Al aceptar este documento, ambos integrantes se comprometen a aplicar estos acuerdos durante el desarrollo del SEVB y a actualizar el archivo cuando el equipo decida cambiar alguno de ellos.

## 15. Publicación

Este documento debe estar ubicado en la raíz del repositorio con el nombre `ESTANDARES.md`.

El archivo `.editorconfig` también debe mantenerse en la raíz del proyecto.

El commit inicial del documento será:

`docs(estandares): agrega estándares del equipo`

Cualquier cambio posterior debe quedar registrado mediante Git siguiendo la misma convención.

## Referencias

Google. (s. f.). *Google Java Style Guide*. https://google.github.io/styleguide/javaguide.html

JetBrains. (s. f.). *Reformat and rearrange code*. https://www.jetbrains.com/help/idea/reformat-and-rearrange-code.html

Spring. (s. f.). *Spring Boot reference documentation*. https://docs.spring.io/spring-boot/

## Código de sesión

**llano-14**
