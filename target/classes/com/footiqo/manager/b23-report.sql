
-- TOTALCORNER / FOOTIQO - B2.3 - A6.3 MULTILIGA (CORREGIDO ERROR 1137)
-- MySQL 8. SOLO LECTURA de tablas permanentes.
-- Genera un resumen por liga y UN informe unificado con country, liga, temporada,
-- partidos Footiqo sin enlace VERIFICADO y candidatos TotalCorner del MISMO campeonato.
-- La rama SOLO_TC muestra partidos TC sin enlace (con ambos goles informados).
-- NINGUN candidato se inserta ni se da por verificado automaticamente.
USE freefootstats;

-- ==== 1. CONFIGURACION ====
-- Por defecto: TODO el periodo historico de los datasets, hasta hoy inclusive.
-- Para el control rutinario de martes/viernes puede sustituirse la fecha inicial por:
-- SET @desde_madrid = NOW() - INTERVAL 60 DAY;
SET @desde_madrid = '2015-07-01 00:00:00';
SET @hasta_madrid_exclusiva = DATE_ADD(CURDATE(), INTERVAL 1 DAY);
SET @margen_horas = 48;
SET @max_propuestas_por_ls = 10;
SET @incluir_solo_tc = 1; -- 1: mostrar tambien TC -> Footiqo; 0: solo pendientes Footiqo

SET @desde_utc = CONVERT_TZ(@desde_madrid, 'Europe/Madrid', '+00:00');
SET @hasta_utc = CONVERT_TZ(@hasta_madrid_exclusiva, 'Europe/Madrid', '+00:00');
SELECT @desde_madrid AS desde_madrid, @hasta_madrid_exclusiva AS hasta_exclusiva,
       @margen_horas AS margen_horas, @incluir_solo_tc AS incluir_tc_sin_enlace,
       CASE WHEN @desde_utc IS NULL OR @hasta_utc IS NULL
                      OR @desde_utc >= @hasta_utc OR @margen_horas <= 0
            THEN 'ERROR: REVISAR FECHAS O ZONA HORARIA'
            ELSE 'OK' END AS preflight;

-- Correspondencia EXPLICITA entre ID API TC y ls_league.id.
-- Al incorporar una liga comercial nueva, anadir UNA fila aqui.
DROP TEMPORARY TABLE IF EXISTS tmp_b23_ligas;
CREATE TEMPORARY TABLE tmp_b23_ligas (
    ls_league_id INT NOT NULL PRIMARY KEY,
    tc_id VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    UNIQUE KEY uq_tc_id (tc_id)
);
INSERT INTO tmp_b23_ligas (ls_league_id, tc_id) VALUES
    (2,'1'),    -- Premier League
    (1,'14'),   -- LaLiga
    (3,'12'),   -- Serie A
    (4,'16'),   -- Bundesliga
    (5,'25'),   -- Ligue 1
    (18,'137'), -- Champions League
    (28,'138'), -- Europa League
    (6,'2');    -- Championship

-- ==== 2. PENDIENTES DE FOOTIQO (SOLO FINALIZADOS) ====
DROP TEMPORARY TABLE IF EXISTS tmp_b23_ls;
CREATE TEMPORARY TABLE tmp_b23_ls AS
SELECT m.id AS ls_match_id, c.name AS country, ll.name AS liga,
       g.ls_league_id, g.tc_id AS tc_league_id_api,
       ss.name AS temporada_ls,
       m.matchDate AS fecha_ls_madrid,
       CONVERT_TZ(m.matchDate, 'Europe/Madrid', '+00:00') AS fecha_ls_utc,
       m.lsHomeTeam_id AS ls_home_id, m.lsAwayTeam_id AS ls_away_id,
       h.name AS equipo_ls_local, a.name AS equipo_ls_visitante,
       m.ftHomeTeamGoals AS goles_ls_local, m.ftAwayTeamGoals AS goles_ls_visitante,
       lx.tc_match_pk AS tc_id_enlace_existente,
       lx.review_status AS estado_enlace_ls,
       ex.exception_code AS excepcion_conocida,
       ex.notes AS notas_excepcion
FROM ls_match m
JOIN ls_league_season lls ON lls.id=m.lsLeague_season_id
JOIN tmp_b23_ligas g ON g.ls_league_id=lls.league_id
JOIN ls_league ll ON ll.id=lls.league_id
LEFT JOIN ls_country c ON c.id=ll.country_id
JOIN ls_season ss ON ss.id=lls.season_id
LEFT JOIN ls_team h ON h.id=m.lsHomeTeam_id
LEFT JOIN ls_team a ON a.id=m.lsAwayTeam_id
LEFT JOIN tc_match_link lx ON lx.ls_match_id=m.id
LEFT JOIN tc_match_exception ex ON ex.ls_match_id=m.id AND ex.is_active=1
WHERE m.pendingFinalResult=b'0'
  AND m.matchDate >= @desde_madrid AND m.matchDate < @hasta_madrid_exclusiva
  AND (lx.ls_match_id IS NULL OR lx.review_status <> 'VERIFIED')
  AND @desde_utc IS NOT NULL AND @hasta_utc IS NOT NULL;
ALTER TABLE tmp_b23_ls ADD PRIMARY KEY (ls_match_id),
    ADD INDEX ix_ls_liga_fecha (ls_league_id, fecha_ls_utc);

-- ==== 3. PARTIDOS DE TC SIN ENLACE VERIFICADO Y CON MARCADOR ====
-- No se incluyen partidos futuros ni partidos que carezcan de ambos goles.
-- Tener marcador no prueba por si solo el estado final: revisar match_status.
DROP TEMPORARY TABLE IF EXISTS tmp_b23_tc;
CREATE TEMPORARY TABLE tmp_b23_tc AS
SELECT m.id AS tc_match_pk, m.tc_match_id AS tc_match_id_api,
       c.name AS country, ll.name AS liga, g.ls_league_id,
       g.tc_id AS tc_league_id_api,
       m.kickoff_utc AS fecha_tc_utc,
       CONVERT_TZ(m.kickoff_utc, '+00:00', 'Europe/Madrid') AS fecha_tc_madrid,
       m.kickoff_timezone_status AS zona_tc,
       m.home_team_id AS tc_home_id, m.away_team_id AS tc_away_id,
       m.home_team_name AS equipo_tc_local, m.away_team_name AS equipo_tc_visitante,
       m.home_goals AS goles_tc_local, m.away_goals AS goles_tc_visitante,
       m.match_status AS estado_partido_tc,
       m.classification_status AS clasificacion_tc,
       lx.ls_match_id AS ls_id_enlace_existente,
       lx.review_status AS estado_enlace_tc
FROM tc_match m
JOIN tc_league tcl ON tcl.id=m.league_id
JOIN tmp_b23_ligas g ON g.tc_id=tcl.tc_league_id COLLATE utf8mb4_unicode_ci
JOIN ls_league ll ON ll.id=g.ls_league_id
LEFT JOIN ls_country c ON c.id=ll.country_id
LEFT JOIN tc_match_link lx ON lx.tc_match_pk=m.id
WHERE m.kickoff_utc >= @desde_utc AND m.kickoff_utc < @hasta_utc
  AND m.home_goals IS NOT NULL AND m.away_goals IS NOT NULL
  AND (lx.tc_match_pk IS NULL OR lx.review_status <> 'VERIFIED')
  AND @desde_utc IS NOT NULL AND @hasta_utc IS NOT NULL;
ALTER TABLE tmp_b23_tc ADD PRIMARY KEY (tc_match_pk),
    ADD INDEX ix_tc_liga_fecha (ls_league_id, fecha_tc_utc);

-- ==== 4. CANDIDATOS EN LA MISMA LIGA Y +/- 48 HORAS ====
-- Indicios: marcador, equipo ya enlazado o nombres coincidentes.
-- No basta con compartir marcador para enlazar automaticamente.
DROP TEMPORARY TABLE IF EXISTS tmp_b23_pares;
CREATE TEMPORARY TABLE tmp_b23_pares AS
SELECT l.ls_match_id, t.tc_match_pk, l.ls_league_id,
       TIMESTAMPDIFF(MINUTE, l.fecha_ls_utc, t.fecha_tc_utc) AS diferencia_minutos,
       (l.goles_ls_local IS NOT NULL AND l.goles_ls_visitante IS NOT NULL
        AND l.goles_ls_local=t.goles_tc_local
        AND l.goles_ls_visitante=t.goles_tc_visitante) AS marcador_igual,
       EXISTS(SELECT 1 FROM tc_team_link x
              WHERE x.tc_team_pk=t.tc_home_id AND x.ls_team_id=l.ls_home_id
                AND x.review_status='VERIFIED') AS local_igual_por_link,
       EXISTS(SELECT 1 FROM tc_team_link x
              WHERE x.tc_team_pk=t.tc_away_id AND x.ls_team_id=l.ls_away_id
                AND x.review_status='VERIFIED') AS visitante_igual_por_link,
       (CONVERT(l.equipo_ls_local USING utf8mb4) COLLATE utf8mb4_unicode_ci
        =CONVERT(t.equipo_tc_local USING utf8mb4) COLLATE utf8mb4_unicode_ci)
             AS nombre_local_igual,
       (CONVERT(l.equipo_ls_visitante USING utf8mb4) COLLATE utf8mb4_unicode_ci
        =CONVERT(t.equipo_tc_visitante USING utf8mb4) COLLATE utf8mb4_unicode_ci)
             AS nombre_visitante_igual
FROM tmp_b23_ls l
JOIN tmp_b23_tc t
 ON t.ls_league_id=l.ls_league_id
 AND t.fecha_tc_utc BETWEEN l.fecha_ls_utc - INTERVAL (@margen_horas) HOUR
                          AND l.fecha_ls_utc + INTERVAL (@margen_horas) HOUR
WHERE l.fecha_ls_utc IS NOT NULL AND t.fecha_tc_utc IS NOT NULL;
ALTER TABLE tmp_b23_pares ADD INDEX ix_par_ls(ls_match_id),
    ADD INDEX ix_par_tc(tc_match_pk);
DELETE p FROM tmp_b23_pares p
WHERE COALESCE(p.marcador_igual,0)<>1
  AND COALESCE(p.local_igual_por_link,0)<>1
  AND COALESCE(p.visitante_igual_por_link,0)<>1
  AND COALESCE(p.nombre_local_igual,0)<>1
  AND COALESCE(p.nombre_visitante_igual,0)<>1;

DROP TEMPORARY TABLE IF EXISTS tmp_b23_candidatos;
CREATE TEMPORARY TABLE tmp_b23_candidatos AS
SELECT p.*,
       CASE WHEN p.local_igual_por_link=1 AND p.visitante_igual_por_link=1
                      AND p.marcador_igual=1
              THEN 'ALTA: DOS EQUIPOS + MARCADOR'
            WHEN (p.local_igual_por_link=1 OR p.visitante_igual_por_link=1)
                      AND p.marcador_igual=1
              THEN 'MEDIA: EQUIPO + MARCADOR'
            WHEN p.local_igual_por_link=1 AND p.visitante_igual_por_link=1
              THEN 'REVISAR: EQUIPOS OK, MARCADOR DISTINTO'
            WHEN p.marcador_igual=1 AND p.nombre_local_igual=1
                      AND p.nombre_visitante_igual=1
              THEN 'MEDIA: NOMBRES + MARCADOR'
            WHEN p.marcador_igual=1
              THEN 'BAJA: FECHA + MARCADOR'
            ELSE 'REVISAR: EQUIPO/NOMBRE SIN MARCADOR' END AS calidad_indicio,
       ROW_NUMBER() OVER (PARTITION BY p.ls_match_id ORDER BY
           (COALESCE(p.local_igual_por_link,0)+COALESCE(p.visitante_igual_por_link,0)) DESC,
           COALESCE(p.marcador_igual,0) DESC,
           (COALESCE(p.nombre_local_igual,0)+COALESCE(p.nombre_visitante_igual,0)) DESC,
           ABS(p.diferencia_minutos) ASC, p.tc_match_pk) AS orden_candidato_ls
FROM tmp_b23_pares p;
ALTER TABLE tmp_b23_candidatos ADD INDEX ix_c_ls (ls_match_id),
    ADD INDEX ix_c_tc (tc_match_pk);

-- ==== 5. COPIAS INDEPENDIENTES: EVITAN ERROR MySQL 1137 ====
DROP TEMPORARY TABLE IF EXISTS tmp_b23_ls_solo;
CREATE TEMPORARY TABLE tmp_b23_ls_solo AS SELECT * FROM tmp_b23_ls;
DROP TEMPORARY TABLE IF EXISTS tmp_b23_tc_solo;
CREATE TEMPORARY TABLE tmp_b23_tc_solo AS SELECT * FROM tmp_b23_tc;
DROP TEMPORARY TABLE IF EXISTS tmp_b23_cand_solo_ls;
CREATE TEMPORARY TABLE tmp_b23_cand_solo_ls AS SELECT * FROM tmp_b23_candidatos;
DROP TEMPORARY TABLE IF EXISTS tmp_b23_cand_solo_tc;
CREATE TEMPORARY TABLE tmp_b23_cand_solo_tc AS SELECT * FROM tmp_b23_candidatos;

-- ==== RESULTADO 1: UNA FILA POR LIGA ====
-- Los contadores solo incluyen el periodo seleccionado y partidos finalizados LS.
SELECT g.ls_league_id, g.tc_id AS tc_league_id_api, co.name AS country, ll.name AS liga,
       COALESCE(ls.n,0) AS ls_finalizados_sin_enlace_verificado,
       COALESCE(ls.con_excepcion,0) AS ls_con_excepcion_activa,
       COALESCE(tc.n,0) AS tc_con_marcador_sin_enlace_verificado,
       COALESCE(c.n,0) AS parejas_candidatas,
       COALESCE(sl.n,0) AS ls_sin_candidato,
       COALESCE(st.n,0) AS tc_sin_candidato
FROM tmp_b23_ligas g
JOIN ls_league ll ON ll.id=g.ls_league_id
LEFT JOIN ls_country co ON co.id=ll.country_id
LEFT JOIN (
    SELECT ls_league_id, COUNT(*) AS n,
           SUM(excepcion_conocida IS NOT NULL) AS con_excepcion
    FROM tmp_b23_ls GROUP BY ls_league_id
) ls ON ls.ls_league_id=g.ls_league_id
LEFT JOIN (
    SELECT ls_league_id, COUNT(*) AS n
    FROM tmp_b23_tc GROUP BY ls_league_id
) tc ON tc.ls_league_id=g.ls_league_id
LEFT JOIN (
    SELECT ls_league_id, COUNT(*) AS n
    FROM tmp_b23_candidatos GROUP BY ls_league_id
) c ON c.ls_league_id=g.ls_league_id
LEFT JOIN (
    SELECT l.ls_league_id, COUNT(*) AS n
    FROM tmp_b23_ls_solo l
    WHERE NOT EXISTS (SELECT 1 FROM tmp_b23_cand_solo_ls x
                      WHERE x.ls_match_id=l.ls_match_id)
    GROUP BY l.ls_league_id
) sl ON sl.ls_league_id=g.ls_league_id
LEFT JOIN (
    SELECT t.ls_league_id, COUNT(*) AS n
    FROM tmp_b23_tc_solo t
    WHERE NOT EXISTS (SELECT 1 FROM tmp_b23_cand_solo_tc x
                      WHERE x.tc_match_pk=t.tc_match_pk)
    GROUP BY t.ls_league_id
) st ON st.ls_league_id=g.ls_league_id
ORDER BY country, liga;

-- ==== 6. MAS COPIAS PARA EL INFORME UNIFICADO (MySQL 1137) ====
-- El resultado anterior usa copias; hacemos otras para que cada rama del UNION
-- tenga sus propias tablas y ninguna tabla TEMPORARY se reabra.
DROP TEMPORARY TABLE IF EXISTS tmp_b23_ls_final;
CREATE TEMPORARY TABLE tmp_b23_ls_final AS SELECT * FROM tmp_b23_ls;
DROP TEMPORARY TABLE IF EXISTS tmp_b23_tc_final;
CREATE TEMPORARY TABLE tmp_b23_tc_final AS SELECT * FROM tmp_b23_tc;
DROP TEMPORARY TABLE IF EXISTS tmp_b23_cand_final_ls;
CREATE TEMPORARY TABLE tmp_b23_cand_final_ls AS SELECT * FROM tmp_b23_candidatos;
DROP TEMPORARY TABLE IF EXISTS tmp_b23_cand_final_tc;
CREATE TEMPORARY TABLE tmp_b23_cand_final_tc AS SELECT * FROM tmp_b23_candidatos;

-- ==== RESULTADO 2: INFORME UNIFICADO, EXPORTABLE A EXCEL ====
-- CANDIDATO = un posible enlace LS/TC. Puede haber varias filas por partido LS.
-- SOLO_LS = finalizado en Footiqo, sin indicio TC dentro del margen.
-- SOLO_TC = partido TC con marcador, sin indicio Footiqo (solo si @incluir_solo_tc=1).
-- Los ID para A6.4 son ls_match_id y tc_match_pk (PK INTERNA, NO tc_match_id_api).
SELECT z.tipo_fila, z.valoracion,
       z.country, z.liga, z.ls_league_id, z.tc_league_id_api,
       z.temporada_ls,
       z.ls_match_id, z.tc_match_pk, z.tc_match_id_api,
       z.fecha_ls_madrid, z.fecha_tc_madrid, z.diferencia_minutos,
       z.equipo_ls_local, z.equipo_ls_visitante, z.marcador_ls,
       z.equipo_tc_local, z.equipo_tc_visitante, z.marcador_tc,
       z.equipo_local_link_verificado, z.equipo_visitante_link_verificado,
       z.nombre_local_igual, z.nombre_visitante_igual,
       z.zona_tc, z.estado_partido_tc, z.clasificacion_tc,
       z.estado_enlace_ls, z.estado_enlace_tc,
       z.excepcion_conocida, z.notas_excepcion
FROM (
  SELECT 'CANDIDATO' AS tipo_fila, c.calidad_indicio AS valoracion,
         l.country, l.liga, l.ls_league_id, l.tc_league_id_api,
         l.temporada_ls,
         l.ls_match_id, t.tc_match_pk, t.tc_match_id_api,
         l.fecha_ls_madrid, t.fecha_tc_madrid, c.diferencia_minutos,
         l.equipo_ls_local, l.equipo_ls_visitante,
         CONCAT(COALESCE(CAST(l.goles_ls_local AS CHAR),'?'), '-',
                COALESCE(CAST(l.goles_ls_visitante AS CHAR),'?')) AS marcador_ls,
         t.equipo_tc_local, t.equipo_tc_visitante,
         CONCAT(CAST(t.goles_tc_local AS CHAR), '-', CAST(t.goles_tc_visitante AS CHAR)) AS marcador_tc,
         c.local_igual_por_link AS equipo_local_link_verificado,
         c.visitante_igual_por_link AS equipo_visitante_link_verificado,
         c.nombre_local_igual, c.nombre_visitante_igual,
         t.zona_tc, t.estado_partido_tc, t.clasificacion_tc,
         l.estado_enlace_ls, t.estado_enlace_tc,
         l.excepcion_conocida, l.notas_excepcion,
         l.fecha_ls_madrid AS orden_fecha
  FROM tmp_b23_candidatos c
  JOIN tmp_b23_ls l ON l.ls_match_id=c.ls_match_id
  JOIN tmp_b23_tc t ON t.tc_match_pk=c.tc_match_pk
  WHERE c.orden_candidato_ls <= @max_propuestas_por_ls

  UNION ALL
  SELECT 'SOLO_LS', 'SIN_CANDIDATO',
         l.country, l.liga, l.ls_league_id, l.tc_league_id_api,
         l.temporada_ls,
         l.ls_match_id, NULL, NULL,
         l.fecha_ls_madrid, NULL, NULL,
         l.equipo_ls_local, l.equipo_ls_visitante,
         CONCAT(COALESCE(CAST(l.goles_ls_local AS CHAR),'?'), '-',
                COALESCE(CAST(l.goles_ls_visitante AS CHAR),'?')),
         NULL, NULL, NULL,
         NULL, NULL, NULL, NULL,
         NULL, NULL, NULL,
         l.estado_enlace_ls, NULL,
         l.excepcion_conocida, l.notas_excepcion,
         l.fecha_ls_madrid
  FROM tmp_b23_ls_final l
  WHERE NOT EXISTS (SELECT 1 FROM tmp_b23_cand_final_ls c
                    WHERE c.ls_match_id=l.ls_match_id)

  UNION ALL
  SELECT 'SOLO_TC', 'SIN_CANDIDATO',
         t.country, t.liga, t.ls_league_id, t.tc_league_id_api,
         NULL,
         NULL, t.tc_match_pk, t.tc_match_id_api,
         NULL, t.fecha_tc_madrid, NULL,
         NULL, NULL, NULL,
         t.equipo_tc_local, t.equipo_tc_visitante,
         CONCAT(CAST(t.goles_tc_local AS CHAR), '-', CAST(t.goles_tc_visitante AS CHAR)),
         NULL, NULL, NULL, NULL,
         t.zona_tc, t.estado_partido_tc, t.clasificacion_tc,
         NULL, t.estado_enlace_tc,
         NULL, NULL,
         t.fecha_tc_madrid
  FROM tmp_b23_tc_final t
  WHERE @incluir_solo_tc=1
    AND NOT EXISTS (SELECT 1 FROM tmp_b23_cand_final_tc c
                    WHERE c.tc_match_pk=t.tc_match_pk)
) z
ORDER BY z.country, z.liga, z.orden_fecha,
         FIELD(z.tipo_fila, 'CANDIDATO', 'SOLO_LS', 'SOLO_TC'),
         z.ls_match_id, ABS(z.diferencia_minutos), z.tc_match_pk;
