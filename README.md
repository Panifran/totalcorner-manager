# TotalCorner Manager v1.9.1 — A6.3/A6.4 revisión y enlaces manuales

Consulta `NOTAS_1_9_0.txt`. Integra una pantalla tipo B2.2 para buscar pendientes y enlazar parejas revisadas de cualquier liga seleccionada. Mantiene A1–A6.2, B1–B7 y los datos existentes; no añade ligas a los productos comerciales.

---

# TotalCorner Manager v1.8.9 - A6.2 enlaces de partidos seguros

Consulta `NOTAS_1_8_9.txt`. Permite simular y aplicar exclusivamente los enlaces de partidos `LISTO_PARA_INSERTAR` del CSV A6.1, despues de regenerar la evidencia actual; insercion transaccional con ROLLBACK si algo no coincide. Conserva los filtros A5.2/A6.1 al reiniciar y actualiza el panel general. No activa la liga en los datasets comerciales y no toca el fichero `totalcorner-history.properties`.

---

# TotalCorner Manager v1.8.8 - A6.1 enlaces de equipos filtrados por liga

Consulta `NOTAS_1_8_8.txt`. La correccion es exclusiva de `A61MatchPreviewRunner` y no cambia la base de datos.

---

# TotalCorner Manager v1.8.7 - Totales, B4 y bloqueos explicados

Consulta `NOTAS_1_8_7.txt`. Esta revision SOLO modifica la interfaz del
proyecto `totalcorner-manager`; el descargador de la v1.8.6 no cambia.

---

# TotalCorner Manager v1.8.6 — B3/B4 coherencia y clasificacion de pendientes

Consulta `NOTAS_1_8_6.txt` para la instalacion, las comprobaciones y los cambios. Esta version actualiza tambien el proyecto totalcorner-downloader.

---

# TotalCorner Manager v1.8.5: coherencia y diagnostico de B3

B3 consulta y simulacion no deben discrepar. Esta version compara el total de
pendientes de la consulta con el de la simulacion en el mismo rango y bloquea
la descarga si difieren. Incorpora **5. Diagnosticar diferencias B3 + CSV**:
consulta SQL solo lectura, sin API, y expone los partidos con jobs pendientes,
filtros, exclusiones y reintentos. CSV UTF-8 con comas y BOM para Excel.

Tambien corrige el texto del dialogo de confirmacion: manda el final de
`ls_match`, no el marcador de TC.

**Importante:** esta version diagnostica los desajustes, no supone que los
JSON esten disponibles en la API. Ejecutar B3 solo tras consultar y simular
con resultados coherentes. Compilar con Java 8 en Eclipse.

---

# TotalCorner Manager v1.8.4 — B3: criterio LS finalizado

**Cambio:** B3 toma como referencia `ls_match.pendingFinalResult=b'0'` y
`tc_match_link.review_status='VERIFIED'`. **No exige que TotalCorner tenga
`match_status=full/ft/ended` ni `home_goals/away_goals` presentes.** Requiere
`tc_match.kickoff_utc` no nulo y anterior al instante UTC actual; conserva filtros
por liga, fechas y excepciones activas, trabajos DONE y control de reintentos.

Se han armonizado **las tres selecciones**: consulta B3 del Manager, simulacion
de `HistoricalDownloadRunner` y seleccion de `LeagueOddsBatchDownloader`.
La tabla B3 incorpora la columna `TC no final` para mostrar cuantos partidos
LS finalizados no tienen aun estado+marcador final en TC.

**No se modifican** tablas `ls_*`, enlaces de partidos, cuotas existentes ni
JSON descargados. La logica original del runner sin `--verified-final-only`
permanece intacta. El filtro comercial sigue activado en el Manager.

## Instalacion

Cerrar Manager y descomprimir el ZIP de actualizacion en
`C:\Users\Javi\eclipse-workspace\` **sobrescribiendo los dos proyectos**
`totalcorner-manager` y `totalcorner-downloader`. En Eclipse: F5 en ambos,
Maven > Update Project y Project > Build Project. Java 8.

## Primera prueba

En B3, pulsar **Consultar pendientes B3**. Los tres partidos LS
155548, 155549 y 155550 de Championship deberian pasar a elegibles
si conservan enlace VERIFIED, ls_match finalizado y hora TC valida.
Luego **Simular B3** y comprobar `faltan=3` para su temporada, sin ejecutar
si aparecen partidos inesperados. Ejecutar la descarga real despues de
revisar la simulacion. B4 sigue necesitando ambos JSON validos.

**Limite:** compilacion/validaciones locales no sustituyen una prueba real
contra MySQL y API. No modificar `totalcorner-history.properties`.

---

# TotalCorner Manager v1.8.3 — B4: diagnostico de pendientes sin procesar

**Novedad:** en `02. Actualizar ligas > 7. B4 Cobertura`, pulsar
`5. Diagnosticar pendientes + CSV` despues de consultar la cobertura. Solo consulta
MySQL en modo lectura; **0 peticiones API, 0 modificaciones en la base de datos**.

Genera automaticamente un CSV (delimitado por **comas**, UTF-8 BOM para Excel) en:
`totalcorner-downloader/reports/totalcorner/b4_diagnostico_YYYYMMDD-HHmmssSSS.csv`.
Incluye `ls_match_id`, `tc_match_pk`, equipos y fecha Footiqo, resultado/estado TC,
estados de los jobs B3, existencia y validez del JSON para cada endpoint,
estados de cobertura, motivo del bloqueo y accion sugerida.

La consulta considera exactamente las temporadas habilitadas, ligas seleccionadas,
partidos LS finalizados y enlaces `VERIFIED` que el contador B4 considera pendientes.
Los dos JSON se validan con las mismas condiciones de metadatos que utiliza
`OddsCoverageBuilder` (respuesta HTTP 200, endpoint correcto, tamaños >0, match correcto).
**No certifica que exista una cuota concreta ni que la API la ofrezca.**

Motivos posibles: jobs de B3 pendientes, JSON invalido/ausente, job de cobertura en
espera/bloqueado, desacuerdo entre version de resumen y seleccion del Builder,
o fuentes aparentemente validas que requieren investigacion adicional.

**Correccion visual:** si se simula o ejecuta B4 con pendientes, pero el Builder
selecciona `0`, el gestor indicara expresamente `SIN ELEGIBLES` y propondra
usar el diagnostico, sin presentar la ejecucion como cobertura completada.

**Instalacion:** cerrar el gestor, extraer el ZIP de actualizacion en
`C:\Users\Javi\eclipse-workspace\` aceptando sobrescrituras. En Eclipse,
F5, `Maven > Update Project` y ejecutar `TotalCornerManager` con Java 8.
No cambia `totalcorner-downloader`, `totalcorner-history.properties` ni los archivos comerciales.

**Verificacion:** compilacion Java 8, autopruebas de runners y JAR. La consulta real
no ha sido ejecutada contra MySQL del usuario; los motivos precisos de los tres
partidos no se pueden conocer hasta ejecutar este diagnostico.

---

# TotalCorner Manager v1.8.2 — B2.2: seleccion manual de cualquier estado

En la tabla B2.2 **todas las casillas se pueden marcar/desmarcar** independientemente
 del estado. Solo LISTO_PARA_ENLAZAR se marca por defecto.

- Cualquier fila no segura exige confirmacion escrita `REVISADOS N`, tras revisar cada pareja.
- Los enlaces manuales se guardan con `match_method='MATCH_MANUAL_REVIEW'` y `review_status='VERIFIED'`.
- Se permite ignorar advertencias de marcadores, zona horaria o candidatos multiples,
  **pero solo puede seleccionarse una pareja por partido LS y una por partido TC**.
- Si el LS o TC ya tiene cualquier enlace previo, la transaccion se cancela; no se
  modifican ni reemplazan enlaces existentes.
- La base exige liga correcta, ambos equipos VERIFIED y coincidencia dentro de la
  tolerancia configurada. No permite forzar parejas que no aparezcan en B2.2.
- Antes de insertar, el sistema comprueba de nuevo todas las parejas y el SHA-256
  completo de la simulacion. No modifica goles, cuotas ni ls_match.

Instalacion: cerrar Manager, descomprimir actualizacion en
`C:\Users\Javi\eclipse-workspace\`, refrescar en Eclipse (F5) y actualizar Maven.
Repetir **Simular B2.2**, seleccionar las filas y pulsar Aplicar.

# TotalCorner Manager v1.8.1 — B2.2 enlazar partidos con TC sin marcador

**Cambio concreto sobre v1.8.0.** En B2.2 aparece el estado `TC_SIN_MARCADOR`
solo si se cumplen todas las condiciones siguientes: partido finalizado con resultado FT en
`ls_match`, liga correcta, ambos equipos enlazados `VERIFIED`, fecha +/- 5 minutos,
zona horaria `PROFILE_VERIFIED`, un único par LS/TC en ambos sentidos, y resultados
de TotalCorner ausentes (uno o ambos `NULL`) sin ningún gol conocido que contradiga a LS.

- `LISTO_PARA_ENLAZAR`: marcado automáticamente.
- `TC_SIN_MARCADOR`: desmarcado al simular; seleccionar uno a uno tras revisar
  equipos y fecha; confirmar con `TC REVISADO N`, donde N es el número seleccionado.
- `REVISAR_MARCADOR`: discrepancia real de marcadores, FT de LS incompleto,
  o TC sin marcador con diferencia de más de 5 minutos: no se permite aplicar.
- Siguen bloqueados conflictos de enlace, identidad ambigua y zona horaria no verificada.

**La aplicación NO actualiza `tc_match`, `ls_match`, goles ni cuotas.**
Si se aplica un enlace sin goles TC, `tc_match_link` se inserta con
`review_status='VERIFIED'` y `match_method='MATCH_NO_SCORE_REVIEW'`
para que se distinga del método tradicional con marcador validado.

B2.2 recalcula los candidatos bajo transacción y exige que el SHA-256 completo de la
propuesta sea idéntico al de la simulación; además, vuelve a comprobar las condiciones
con `SELECT ... FOR UPDATE` antes de insertar. Si algún resultado TC aparece mientras
se revisa, o cambia otro dato, se cancela y hay que simular de nuevo.

## Instalación

1. Cierra Manager.
2. Extrae el ZIP de actualización sobre `C:\Users\Javi\eclipse-workspace\`.
3. En Eclipse, refresca `totalcorner-manager` (F5), luego **Maven > Update Project**
   y compila el proyecto.
4. Abre **02. Actualizar ligas > 4. B2.2 Enlazar** y pulsa **Simular B2.2**.
5. Los tres casos de Championship deberían aparecer como `TC_SIN_MARCADOR` si sus
   goles TC siguen ausentes y mantienen los demás controles. Selecciónalos
   individualmente y revisa fecha/equipos antes de aplicar.

## Validación local

Compilación de fuentes Java 8 (`javac --release 8`) y autopruebas B2.1, B2.2,
A5.2, A5.3 y flujo general. **No se ha ejecutado contra la base MySQL real.**

---

# TotalCorner Manager v1.8.0 — A6.1

Incluye previsualización y CSV de partidos desde ls_match, por liga, sin insertar enlaces.
Solo considera partidos Footiqo finalizados, mapeos de equipos VERIFIED, coincidencia temporal,
marcadores FT y unicidad 1:1. El CSV se genera con comas y BOM UTF-8.
A6.2 (aplicación) y A6.3/A6.4 (pendientes/manual) siguen pendientes de implementar.

# TotalCorner Manager v1.7.1 (Java 8 / Swing)

## Actualizacion desde v1.6.1

1. Cierra TotalCorner Manager.
2. Extrae `totalcorner-manager-1.7.1-actualizacion.zip` en `C:\Users\Javi\eclipse-workspace\` y acepta sobrescribir solo los ficheros del proyecto `totalcorner-manager`.
3. Eclipse: selecciona `totalcorner-manager`, pulsa **F5**, **Maven > Update Project**, y, si no compila automaticamente, **Project > Build Project**.
4. Ejecuta `com.footiqo.manager.TotalCornerManager` con **Java 8**.

La actualizacion no modifica `totalcorner-downloader`, `Corners_Cards_Simulator`, ningun `.properties` de los otros proyectos, las tablas MySQL ni los scripts de WinSCP.

## Navegacion: dos bloques independientes

- **01 · Incorporar liga nueva**, la primera pestaña a la izquierda. Campos editables: liga Footiqo (`ls_league.id`), liga TotalCorner y nombre esperado en la API. **USA MLS (LS 7; TC 85 candidato)** es solo el ejemplo precargado. Se pueden introducir otras ligas; el boton **Guardar liga** permite guardar una liga sin darla por verificada, y el selector *Ligas guardadas* recupera las ligas conservadas. La confirmacion de A1 y A2 se guarda por cada combinacion LS/TC/nombre, no se hereda de otras ligas.
- **02 · Actualizar ligas** conserva las diez pestañas internas B1.1–B7 y los procesos existentes. La seleccion multiliga y su fichero `.properties` solo aparecen en este bloque.
- **Panel general · Estado** agrupa A1–A9 (por liga) y B1.1–B7 (por ciclo y seleccion de ligas). Incluye accion de navegacion directa al siguiente paso o al paso seleccionado.

## Semantica de las marcas

- **✓ Hecho**: comprobacion, descarga o publicacion confirmada por la aplicacion.
- **— Omitir**: se ha consultado y no hay trabajo pendiente para esa etapa.
- **! Revisar**: quedan tareas, incidencias, o una validacion adicional necesaria.
- **○ Pendiente**: no existe una confirmacion comprobable en el ciclo actual.
- **· Futuro**: etapas A5.3–A9 todavia sin modulo propio en el gestor.

**Las simulaciones no generan un ✓ de ejecucion.** En B1.2 se conserva la comprobacion por ciclo, ligas y paginas; el paso debe repetirse en un ciclo nuevo aun cuando B1.1 muestre cero pendientes. Las pruebas B1–B4 registran el resultado al completar controles reales; B5, B6 y B7 notifican confirmaciones solo tras completar los procesos apropiados.

En B7, el control de SHA-256 del servidor deja el estado **! Revisar** hasta que el usuario compruebe manualmente la descarga del Coverage y un ZIP desde WooCommerce y pulse **6. Confirmar verificacion web**. Abrir la URL por si solo no equivale a comprobarla.

## Persistencia y compatibilidad

- Las confirmaciones de A1/A2/A3/A4 se guardan en `%USERPROFILE%\.totalcorner-manager-new-league.properties`, con compatibilidad con la version 1.1.0. No se guardan claves API ni credenciales.
- Las evidencias del Bloque 02 se guardan en `%USERPROFILE%\.totalcorner-manager-workflow.properties`, por identificador del ciclo y conjunto de ligas TC seleccionadas. Al cambiar de ciclo o de ligas, no se muestran ticks de la seleccion anterior.
- Los pasos de ciclos anteriores a **v1.2.0** no se marcan como realizados de forma retrospectiva si no existe confirmacion guardada. B1.2 sigue usando su registro de ejecucion previo cuando es compatible con la configuracion actual.
- Los procesos desde A4 a A9 siguen pendientes; **MLS no se da por incorporada automaticamente**, ni se anade a la lista comercial B5/B7.

## Comprobaciones locales

Compilacion de todos los fuentes con `javac --release 8`; pruebas `--self-test-a1`, `--self-test-progress`, `--self-test-flow`, `--self-test-b7`; arranque de las tres vistas en Swing mediante escritorio virtual. No se han realizado llamadas reales a la API ni a MySQL ni publicaciones en WinSCP.


### v1.3.0 · A3 Calendario completo

- A3 reutiliza HistoricalDownloadRunner con `--phase=discovery` y configuracion propia en `totalcorner-downloader/config/totalcorner-manager-league-ID.properties`.
- Primer paso `Verificar configuracion A3`, despues `Consultar pagina pendiente` y `Descargar lote` (10 solicitudes / 1 h por defecto).
- Los lotes pueden acabar antes del final; repite la simulacion y ejecucion hasta confirmar `fin paginacion verificado=true` (o `FINALIZADO en pagina N` en comprobacion de lectura).
- Sin sobrescribir `config/totalcorner-history.properties`; el panel registra A3 por liga cuando MySQL confirma fin de paginacion.

### v1.4.0 · A4 Comprobar partidos y temporadas

- Nueva pestaña **A4. Comprobar partidos/temporadas** en Bloque 01, despues de A3.
- Ejecuta `A4AuditRunner` mediante Java 8 y el JDBC ya disponible en `totalcorner-downloader`, con **consultas SELECT exclusivamente**. No usa la API ni modifica tablas `tc_` / `ls_`.
- Muestra: partidos TC, faltantes de IDs de equipo o `kickoff_utc`, primero/ultimo UTC; tabla de temporadas Footiqo con partidos y finalizados.
- Exige que A3 este confirmado. No autoriza marcar A4 cuando hay incidencias, no hay partidos o no existen partidos/temporadas comerciales en Footiqo.
- Una consulta correcta **no genera el tick de forma automatica**: el usuario debe revisar las fechas y temporadas y pulsar `2. Confirmar revision A4`. La confirmacion queda guardada por los IDs/nombre de la liga.
- Siguiente: **A5.1 · Comprobar equipos existentes**; no se ejecutan enlaces en A4.
- Esta actualizacion solo sustituye fuentes y JAR de `totalcorner-manager`; no modifica el downloader ni su `.properties`.
- Comprobaciones locales: compilacion `javac --release 8`, `--self-test-a4`, regresion de los self-tests previos. No se ha conectado a tu base MySQL.

### v1.5.0 · A4 advertencias y A5.1 mappings existentes

- A4 sigue siendo SELECT / sin API / sin escrituras. Permite confirmar **con advertencias** cuando existen partidos en TC, temporadas y partidos en Footiqo, aunque algunos TC no tengan kickoff o IDs de equipo. **Bloquea** cuando faltan partidos TC, temporadas LS o partidos LS. No crea CSV de los TC incompletos.
- La referencia para el enlazado comercial sigue siendo `ls_match`. Los problemas de enlace **se analizaran en A6** con un CSV de LS sin enlazar y posibles candidatos; A6 todavia no se implementa en v1.5.0.
- Nuevo **A5.1 Comprobar equipos**: consulta los equipos TC de la liga y sus mappings `tc_team_link.review_status='VERIFIED'` asociados a equipos usados en la liga LS. No inserta mappings, no usa API.
- El panel general muestra tick separado para **A5.1**, sin dar por completado **A5.2/A5.3**.
- Compatibilidad con los archivos `.properties` de versiones anteriores, y persistencia de la consulta A5.1 por liga. No cambia `totalcorner-downloader`, sus `.properties`, ni los datos `ls_*`.
- Instalacion: extraer sobre `C:\Users\Javi\eclipse-workspace\`; en Eclipse refrescar proyecto y **Maven → Update Project**.
- Comprobaciones locales: `javac --release 8`, autopruebas A4/A5.1 y regresiones anteriores. **No se ha ejecutado contra MySQL real**.

### v1.6.0 · A5.2 Proponer equipos por partidos, con CSV

- A5.1 muestra el diagnostico de los equipos TC existentes. Si todos estan sin mappings VERIFIED, ejecutar a continuacion **A5.2. Proponer equipos + CSV**.
- Parametros por liga: fecha desde/hasta exclusiva en Europe/Madrid, tolerancia inicial 5 minutos (aviso si >15). Para MLS (año natural), el rango inicial por defecto es 2014-01-01 a 2027-01-01, no temporadas europeas.
- Usa los partidos **finalizados** `ls_match` como referencia, y propone correspondencias TC con mismo inicio (margen), marcador FT, timezone `PROFILE_VERIFIED`, coincidencia unica en ambos lados y sin conflicto con enlaces de partidos existentes.
- Genera `totalcorner-downloader/reports/totalcorner/a52_propuestas_ls<ID>_tc<ID>_<fecha>.csv` con UTF-8 BOM y separador `,`: equipo TC, equipo LS, evidencia, candidatos alternativos, estado y ejemplos de pares TC:LS.
- Estados: `CANDIDATO_CON_EVIDENCIA`, `POSIBLES_ALIAS_TC_REVISAR`, `AMBIGUO_REVISAR`, `EVIDENCIA_LIMITADA_REVISAR`, `SIN_CANDIDATO_POR_PARTIDOS`. Todos los candidatos son propuestas; ninguno se valida ni inserta automaticamente.
- El panel marca **✓ CSV** exclusivamente cuando se genera un informe con datos para la liga, rango y tolerancia seleccionados, y el fichero sigue existiendo. No se marca A5.3.
- A5.3 (aplicacion de mappings) y A6 (CSV de `ls_match` sin enlazar) siguen pendientes de integrar.
- El runner lee la base mediante SELECT y crea un fichero CSV local, sin llamadas API ni escrituras SQL. El SQL esta en `src/main/resources/com/footiqo/manager/a52-candidates.sql`.
- Prueba local: `java ... TotalCornerManager --self-test-a52`. SQL real pendiente de comprobar contra MySQL del usuario.

### v1.6.1 · Correccion CSV A5.2 para Excel

- A5.2 exporta los CSV usando `,` como separador de columnas, conforme a la configuracion de Excel del usuario, en lugar de `;`.
- Conserva UTF-8 con BOM, comillas dobles para todos los valores, escape de comillas y terminacion CRLF; comas en nombres de equipos no dividen columnas.
- Los resultados, correspondencias, consultas SQL y estado del panel no cambian. No requiere repetir A5.1 ni A1-A4.
- El CSV antiguo no se modifica; ejecutar A5.2 de nuevo produce un CSV con nuevo nombre y separador correcto.

### v1.7.0 · A5.3 Simular y aplicar enlaces de equipos revisados

- Nueva pestaña **A5.3. Revisar y aplicar equipos** en el Bloque 01, a continuacion de A5.2. Las etapas A1–A5.3 mantienen una sola fila de pestanas desplazable, sin alterar el orden.
- Carga exclusivamente el CSV que **A5.2 genero para la liga y rango actuales**. Separa las filas `CANDIDATO_CON_EVIDENCIA` y `POSIBLES_ALIAS_TC_REVISAR` de los estados no seleccionables (`SIN_CANDIDATO_POR_PARTIDOS`, `AMBIGUO_REVISAR`, `EVIDENCIA_LIMITADA_REVISAR`).
- Permite seleccionar por tipo, desmarcar manualmente filas individuales y revisar nombres TC/LS, IDs, evidencias y evaluacion. Los alias exigen una confirmacion de seleccion explicitamente revisada, pues varios IDs TC podrian corresponder a un unico ID LS.
- **Simular** revalida en MySQL la correspondencia exacta con el CSV, los partidos y las evaluaciones, sin insertar enlaces. Los filtros son el mismo rango y tolerancia de A5.2, con partidos finalizados de `ls_match` y zonas horarias verificadas en TC. Una simulacion solo habilita **Aplicar** mientras el CSV, la seleccion y las fechas no cambien.
- **Aplicar** pide confirmacion y revalida otra vez en MySQL antes de insertar con transaccion: solo `tc_team_link`, como `VERIFIED`, metodo `MATCH_5MIN_SCORE`, evidencias y anotacion auditada. Si un TC ya tiene cualquier enlace, hay conflicto, el CSV esta obsoleto o cambia el numero de pares, aborta con `ROLLBACK`. Los alias seleccionados permiten varios TC por un LS; el mismo TC nunca se enlaza a distintos LS.
- Se crea un CSV de auditoria `a53_aplicados_ls<ID>_tc<ID>_<fecha>.csv` en `totalcorner-downloader/reports/totalcorner`, ademas del log. Guarda la evaluacion original y si se acepto como alias manualmente.
- El panel general registra **✓ Lote** solo tras respuesta exitosa de aplicacion y conserva el numero del ultimo lote, no una confirmacion de cobertura completa de los equipos. Los casos sin candidatos quedan pendientes para una revision posterior. A6 sigue sin integrar.
- **No modifica** `ls_match`, otras tablas `ls_*`, ficheros `.properties` de descargas ni scripts de publicacion. No ejecuta llamadas a TotalCorner API.
- **No se ha probado la insercion real en el MySQL del usuario**. Primero ejecutar simulacion y revisar recuentos en MLS, y solo despues aplicar.
- Pruebas locales: compilacion `javac --release 8`, `--self-test-a53`, `--self-test-a52` y arranque Swing con Xvfb.

### v1.7.1 · A5.3 selección exclusiva y evidencias actuales

- «Solo candidatos con evidencia» desmarca cualquier alias anterior y selecciona exclusivamente los candidatos fuertes. «Anadir alias revisados» agrega alias tras confirmación manual.
- A5.3 recalcula SIEMPRE las evidencias en MySQL antes del INSERT. Si solo cambia el número de partidos o los ejemplos, muestra `A53_EVIDENCE_UPDATED` y continúa utilizando el recuento de MySQL si se mantiene el mismo par TC/LS, los mismos nombres, la evaluación y la unicidad. Nunca acepta que un candidato fuerte baje de 3 evidencias.
- Cambios de identificador, nombre, tipo, unicidad o conflicto con `tc_team_link` siguen bloqueando la operación. La transacción se revierte ante cualquier error.
- Compatibilidad Java 8; no modifica los demás proyectos ni la configuración de ligas.

## Cambios v1.9.1 (23/11/2026)

- `CANDIDATO` exige ambos equipos mapeados como `VERIFIED`, resultado FT igual y pareja UNICA en ambas direcciones dentro del margen de busqueda (incluyendo candidatas no mostradas por limite).
- `REVISAR_MAPPING`: uno o ambos equipos no tienen correspondencia verificada con el equipo LS esperado. Puede seleccionarse manualmente tras comprobar la pareja.
- `REVISAR_MARCADOR`: equipos verificados pero marcador no igual o incompleto.
- `AMBIGUO`: ambos equipos verificados y marcador igual, pero existen varias propuestas LS o TC en el margen.
- A6.3 permite «Marcar unicos seguros» y «Marcar visibles (revision)» con un limite independiente de minutos, por defecto 100; solamente parejas sin competencia 1:1 se marcan masivamente. Alternativas duplicadas se dejan desmarcadas.
- La seleccion masiva **no escribe MySQL**. La previsualizacion A6.4 vuelve a consultar MySQL, exige huellas coincidentes y mantiene la validacion de unicidad e insercion transaccional bajo confirmacion doble.
- El CSV de auditoria A6.4 incluye ahora `tipo_a63` para conservar que categoria se aprobo. No altera ls_match ni equipos.
- Tras actualizar la version, generar de nuevo el informe A6.3 para calcular las etiquetas nuevas. Los informes anteriores se conservan.
