package com.footiqo.manager;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * B1.1 de la guia operativa: consulta multiliga de SOLO LECTURA.
 * Se inicia en un proceso Java 8 separado por TotalCornerManager; el classpath
 * incluye el conector JDBC Maven del proyecto totalcorner-downloader.
 * No se conecta a TotalCorner y nunca escribe en MySQL.
 */
public final class B11ControlRunner {
    private static final DateTimeFormatter MYSQL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private B11ControlRunner() {}

    public static void main(String[] args) {
        try {
            if (args.length == 1 && "--self-test".equals(args[0])) {
                String sql = buildSql(8);
                int count = sql.length() - sql.replace("?", "").length();
                if (count != 10 || sql.toUpperCase(Locale.ROOT).contains("UPDATE ")
                        || sql.toUpperCase(Locale.ROOT).contains("DELETE "))
                    throw new IllegalStateException("La consulta no supera la verificacion estructural.");
                System.out.println("B11_SELF_TEST_OK - 8 ligas, 10 parametros, solo SELECT");
                return;
            }
            List<String> ids = parseIds(args);
            boolean baseline = true;
            for (String id : ids) if (!LeagueMappingRegistry.isBaseline(id)) baseline = false;
            System.out.println("B11_TRACE\tversion=1.9.9\tmode=" + (baseline ? "BASELINE_189" : "MULTI_1N")
                    + "\tids=" + String.join(",", ids)
                    + "\tclass=" + B11ControlRunner.class.getProtectionDomain().getCodeSource().getLocation());
            String pass = System.getenv("TOTALCORNER_DB_PASSWORD");
            if (pass == null || pass.trim().isEmpty())
                throw new IllegalArgumentException("Falta TOTALCORNER_DB_PASSWORD en el entorno del gestor.");
            String url = env("TOTALCORNER_DB_URL",
                    "jdbc:mysql://localhost:3307/freefootstats?serverTimezone=UTC&useUnicode=true");
            String user = env("TOTALCORNER_DB_USER", "root");
            Class.forName("com.mysql.cj.jdbc.Driver");
            DriverManager.setLoginTimeout(12);
            try (Connection db = DriverManager.getConnection(url, user, pass)) {
                db.setReadOnly(true);
                // Diagnostico SQL sin escrituras: identifica la BD efectiva y las ligas
                // seleccionadas, incluso si la consulta de resumen no devuelve filas.
                try (Statement diag = db.createStatement();
                     ResultSet rs = diag.executeQuery("SELECT DATABASE()")) {
                    if (rs.next()) System.out.println("B11_DATABASE\t" + sanitize(rs.getString(1)));
                }
                StringBuilder diagPlaceholders = new StringBuilder();
                for (int d = 0; d < ids.size(); d++) {
                    if (d > 0) diagPlaceholders.append(',');
                    diagPlaceholders.append('?');
                }
                try (PreparedStatement diag = db.prepareStatement(
                        "SELECT tc_league_id, id, enabled FROM tc_league "
                        + "WHERE CAST(tc_league_id AS UNSIGNED) IN (" + diagPlaceholders + ") "
                        + "ORDER BY CAST(tc_league_id AS UNSIGNED)")) {
                    int d = 1;
                    for (String id : ids) diag.setLong(d++, Long.parseLong(id));
                    try (ResultSet rs = diag.executeQuery()) {
                        int found = 0;
                        while (rs.next()) {
                            System.out.println("B11_DB_LEAGUE\t" + sanitize(rs.getString(1))
                                    + "\tpk=" + rs.getLong(2)
                                    + "\tenabled=" + rs.getInt(3));
                            found++;
                        }
                        System.out.println("B11_DB_LEAGUES_FOUND\t" + found + "\t" + ids.size());
                    }
                }
                try (PreparedStatement ps = db.prepareStatement(buildSql(ids))) {
                    ps.setQueryTimeout(120);
                    int i = 1;
                    for (String id : ids) ps.setString(i++, id);
                    // ls_match.matchDate almacena hora local de Madrid, sin zona.
                    // Enviamos cadena DATETIME para evitar conversion JDBC de Timestamp.
                    String desdeMadrid = LocalDateTime.now(ZoneId.of("Europe/Madrid"))
                            .minusDays(60).format(MYSQL_DATE);
                    ps.setString(i++, desdeMadrid);
                    ps.setString(i++, desdeMadrid);
                    int rows = 0;
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            StringBuilder sb = new StringBuilder("B11_ROW");
                            for (int c = 1; c <= 9; c++) {
                                String val = rs.getString(c);
                                sb.append('\t').append(sanitize(val));
                            }
                            System.out.println(sb.toString());
                            rows++;
                        }
                    }
                    if (rows != ids.size()) {
                        throw new IllegalStateException("B1.1 recibio " + rows + " filas para "
                                + ids.size() + " ligas solicitadas. Comprueba tc_league.enabled "
                                + "y tc_league_id; no se interpretara como sin pendientes.");
                    }
                    System.out.println("B11_DONE\t" + rows + "\t" + ids.size()
                            + "\t" + sanitize(desdeMadrid));
                }
            }
        } catch (Throwable err) {
            // No revelar la URL ni posibles detalles sensibles de conexion.
            String message;
            if (err instanceof SQLException) {
                SQLException sqlErr = (SQLException) err;
                String detail = sanitize(sqlErr.getMessage());
                if (detail.length() > 350) detail = detail.substring(0, 350) + "...";
                message = "Error SQL B1.1 (SQLState="
                        + sanitize(sqlErr.getSQLState()) + ", codigo=" + sqlErr.getErrorCode()
                        + "): " + detail;
            } else if (err instanceof ClassNotFoundException) {
                message = "No se encontro mysql-connector-java en el classpath del proyecto Eclipse.";
            } else {
                message = err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage();
            }
            System.err.println("B11_ERROR\t" + sanitize(message));
            System.exit(1);
        }
    }

    static List<String> parseIds(String[] args) {
        if (args.length != 1 || !args[0].startsWith("--league-ids="))
            throw new IllegalArgumentException("Uso: B11ControlRunner --league-ids=1,14,...");
        String[] raw = args[0].substring("--league-ids=".length()).split(",", -1);
        Set<String> unique = new HashSet<String>();
        List<String> result = new ArrayList<String>();
        for (String s : raw) {
            String id = s.trim();
            if ((!LeagueMappingRegistry.load().containsKey(id) || !LeagueMappingRegistry.load().get(id).updates))
                throw new IllegalArgumentException("B1.1 no tiene correspondencia Footiqo validada para TC=" + id
                        + ". Amplia la consulta de control antes de usar esta liga.");
            if (!unique.add(id)) throw new IllegalArgumentException("ID repetido: " + id);
            result.add(id);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Selecciona al menos una liga.");
        return result;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static String sanitize(String input) {
        if (input == null) return "";
        return input.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * B1.1 uses the SQL proven in v1.8.9 for the original eight competitions.
     * When a user explicitly enables extra TC competitions, use the 1:N query.
     * This keeps the normal B1.1 control stable while supporting MLS play-offs.
     */
    static String buildSql(int leagueCount) { return buildLegacySql(leagueCount); }

    static String buildSql(List<String> tcIds) {
        boolean baselineOnly = true;
        for (String tc : tcIds) {
            if (!LeagueMappingRegistry.isBaseline(tc)) {
                baselineOnly = false;
                break;
            }
        }
        return baselineOnly ? buildLegacySql(tcIds.size()) : buildMultiSql(tcIds.size());
    }

    /** Dynamic, numeric CASE for LS league IDs. Avoids textual collation issues. */
    private static String sqlNumericCase() {
        StringBuilder sql = new StringBuilder("(CASE CAST(l.tc_league_id AS UNSIGNED)");
        for (LeagueMappingRegistry.Association a : LeagueMappingRegistry.load().values()) {
            sql.append(" WHEN ").append(a.tcId).append(" THEN ").append(a.lsId);
        }
        return sql.append(" ELSE NULL END)").toString();
    }

    /** B1.1 de Obsidian; cambia solo la seleccion parametrizada y corte Madrid. */
    static String buildLegacySql(int leagueCount) {
        if (leagueCount < 1 || leagueCount > 8)
            throw new IllegalArgumentException("Numero de ligas invalido.");
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < leagueCount; i++) {
            if (i != 0) placeholders.append(',');
            placeholders.append('?');
        }
        return "WITH ligas AS (\n"
            + " SELECT l.id AS tc_pk, l.tc_league_id, l.name AS liga,\n"
            + " CASE l.tc_league_id\n"
            + "  WHEN '1' THEN 2 WHEN '14' THEN 1 WHEN '12' THEN 3 WHEN '16' THEN 4\n"
            + "  WHEN '25' THEN 5 WHEN '137' THEN 18 WHEN '138' THEN 28 WHEN '2' THEN 6\n"
            + " END AS ls_league_id\n"
            + " FROM tc_league l WHERE l.enabled = 1 AND l.tc_league_id IN (" + placeholders + ")\n"
            + "), ultimo_tc AS (\n"
            + " SELECT m.league_id AS tc_pk, MAX(m.kickoff_utc) AS ultimo_partido_tc_utc\n"
            + " FROM tc_match m JOIN ligas l ON l.tc_pk=m.league_id GROUP BY m.league_id\n"
            + "), ultima_api AS (\n"
            + " SELECT l.tc_pk, MAX(r.last_fetched_at) AS ultima_pagina_1_utc\n"
            + " FROM ligas l JOIN tc_api_response r\n"
            + " ON r.endpoint COLLATE utf8mb4_unicode_ci = CONCAT('/league/schedule/', l.tc_league_id) COLLATE utf8mb4_unicode_ci\n"
            + " JOIN tc_download_job j ON j.last_response_id=r.id\n"
            + " WHERE j.job_type='LEAGUE_SCHEDULE_PAGE' AND j.status='DONE'\n"
            + " AND j.last_http_status=200\n"
            + " AND CAST(JSON_UNQUOTE(JSON_EXTRACT(j.target_params_json, '$.page')) AS UNSIGNED)=1\n"
            + " GROUP BY l.tc_pk\n"
            + "), resumen_footiqo AS (\n"
            + " SELECT l.tc_pk,\n"
            + " MAX(CASE WHEN lm.pendingFinalResult=b'0' THEN lm.matchDate END) AS ultimo_finalizado_footiqo_madrid,\n"
            + " SUM(CASE WHEN lm.pendingFinalResult=b'0' AND lm.matchDate>=?\n"
            + " AND ml.tc_match_pk IS NULL THEN 1 ELSE 0 END) AS sin_enlace_60d,\n"
            + " SUM(CASE WHEN lm.pendingFinalResult=b'0' AND lm.matchDate>=?\n"
            + " AND ml.tc_match_pk IS NOT NULL AND NOT (\n"
            + " EXISTS (SELECT 1 FROM tc_download_job jb WHERE jb.match_id=ml.tc_match_pk\n"
            + " AND jb.job_type='MATCH_BOOKMAKER_ODDS' AND jb.status='DONE'\n"
            + " AND jb.last_http_status=200 AND jb.last_response_id IS NOT NULL)\n"
            + " AND EXISTS (SELECT 1 FROM tc_download_job jh WHERE jh.match_id=ml.tc_match_pk\n"
            + " AND jh.job_type='MATCH_BET365_HISTORY' AND jh.status='DONE'\n"
            + " AND jh.last_http_status=200 AND jh.last_response_id IS NOT NULL)\n"
            + " ) THEN 1 ELSE 0 END) AS sin_descarga_completa_60d\n"
            + " FROM ligas l\n"
            + " LEFT JOIN ls_league_season lls ON lls.league_id=l.ls_league_id\n"
            + " LEFT JOIN ls_match lm ON lm.lsLeague_season_id=lls.id\n"
            + " LEFT JOIN tc_match_link ml ON ml.ls_match_id=lm.id AND ml.review_status='VERIFIED'\n"
            + " GROUP BY l.tc_pk\n"
            + ") SELECT l.tc_league_id AS tc_id, l.liga,\n"
            + " t.ultimo_partido_tc_utc, f.ultimo_finalizado_footiqo_madrid,\n"
            + " a.ultima_pagina_1_utc AS ultima_revision_api_utc,\n"
            + " COALESCE(f.sin_enlace_60d,0) AS pendientes_enlazar,\n"
            + " COALESCE(f.sin_descarga_completa_60d,0) AS pendientes_descargar_cuotas,\n"
            + " COALESCE(f.sin_enlace_60d,0)+COALESCE(f.sin_descarga_completa_60d,0) AS total_pendientes_conocidos,\n"
            + " CASE WHEN a.ultima_pagina_1_utc IS NULL THEN 'REVISAR: sin pagina 1'\n"
            + " WHEN a.ultima_pagina_1_utc < UTC_TIMESTAMP()-INTERVAL 4 DAY THEN 'REFRESCAR CALENDARIO'\n"
            + " WHEN COALESCE(f.sin_enlace_60d,0)>0 THEN 'ENLAZAR PARTIDOS'\n"
            + " WHEN COALESCE(f.sin_descarga_completa_60d,0)>0 THEN 'DESCARGAR CUOTAS'\n"
            + " ELSE 'SIN PENDIENTES LOCALES' END AS proxima_accion\n"
            + " FROM ligas l LEFT JOIN ultimo_tc t ON t.tc_pk=l.tc_pk\n"
            + " LEFT JOIN ultima_api a ON a.tc_pk=l.tc_pk\n"
            + " LEFT JOIN resumen_footiqo f ON f.tc_pk=l.tc_pk\n"
            + " ORDER BY l.liga";
    }

    /** B1.1 de Obsidian; cambia solo la seleccion parametrizada y corte Madrid. */
    static String buildMultiSql(int leagueCount) {
        if (leagueCount < 1 || leagueCount > LeagueMappingRegistry.load().size())
            throw new IllegalArgumentException("Numero de ligas invalido.");
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < leagueCount; i++) {
            if (i != 0) placeholders.append(',');
            placeholders.append('?');
        }
        return "WITH ligas AS (\n"
            + " SELECT l.id AS tc_pk, l.tc_league_id, l.name AS liga,\n"
            + " " + sqlNumericCase() + " AS ls_league_id\n"
            + " FROM tc_league l WHERE l.enabled = 1 AND l.tc_league_id IN (" + placeholders + ")\n"
            + "), ultimo_tc AS (\n"
            + " SELECT m.league_id AS tc_pk, MAX(m.kickoff_utc) AS ultimo_partido_tc_utc\n"
            + " FROM tc_match m JOIN ligas l ON l.tc_pk=m.league_id GROUP BY m.league_id\n"
            + "), ultima_api AS (\n"
            + " SELECT l.tc_pk, MAX(r.last_fetched_at) AS ultima_pagina_1_utc\n"
            + " FROM ligas l JOIN tc_api_response r\n"
            + " ON r.endpoint COLLATE utf8mb4_unicode_ci = CONCAT('/league/schedule/', l.tc_league_id) COLLATE utf8mb4_unicode_ci\n"
            + " JOIN tc_download_job j ON j.last_response_id=r.id\n"
            + " WHERE j.job_type='LEAGUE_SCHEDULE_PAGE' AND j.status='DONE'\n"
            + " AND j.last_http_status=200\n"
            + " AND CAST(JSON_UNQUOTE(JSON_EXTRACT(j.target_params_json, '$.page')) AS UNSIGNED)=1\n"
            + " GROUP BY l.tc_pk\n"
            + "), resumen_footiqo AS (\n"
            + " SELECT l.tc_pk,\n"
            + " MAX(CASE WHEN lm.pendingFinalResult=b'0' THEN lm.matchDate END) AS ultimo_finalizado_footiqo_madrid,\n"
            + " SUM(CASE WHEN CAST(l.tc_league_id AS UNSIGNED) = (SELECT MIN(CAST(q.tc_league_id AS UNSIGNED)) FROM ligas q WHERE q.ls_league_id=l.ls_league_id) AND lm.pendingFinalResult=b'0' AND lm.matchDate>=?\n"
            + " AND ml.tc_match_pk IS NULL THEN 1 ELSE 0 END) AS sin_enlace_60d,\n"
            + " SUM(CASE WHEN lm.pendingFinalResult=b'0' AND lm.matchDate>=?\n"
            + " AND ml.tc_match_pk IS NOT NULL AND EXISTS (SELECT 1 FROM tc_match mt WHERE mt.id=ml.tc_match_pk AND mt.league_id=l.tc_pk) AND NOT (\n"
            + " EXISTS (SELECT 1 FROM tc_download_job jb WHERE jb.match_id=ml.tc_match_pk\n"
            + " AND jb.job_type='MATCH_BOOKMAKER_ODDS' AND jb.status='DONE'\n"
            + " AND jb.last_http_status=200 AND jb.last_response_id IS NOT NULL)\n"
            + " AND EXISTS (SELECT 1 FROM tc_download_job jh WHERE jh.match_id=ml.tc_match_pk\n"
            + " AND jh.job_type='MATCH_BET365_HISTORY' AND jh.status='DONE'\n"
            + " AND jh.last_http_status=200 AND jh.last_response_id IS NOT NULL)\n"
            + " ) THEN 1 ELSE 0 END) AS sin_descarga_completa_60d\n"
            + " FROM ligas l\n"
            + " LEFT JOIN ls_league_season lls ON lls.league_id=l.ls_league_id\n"
            + " LEFT JOIN ls_match lm ON lm.lsLeague_season_id=lls.id\n"
            + " LEFT JOIN tc_match_link ml ON ml.ls_match_id=lm.id AND ml.review_status='VERIFIED'\n"
            + " GROUP BY l.tc_pk\n"
            + ") SELECT l.tc_league_id AS tc_id, l.liga,\n"
            + " t.ultimo_partido_tc_utc, f.ultimo_finalizado_footiqo_madrid,\n"
            + " a.ultima_pagina_1_utc AS ultima_revision_api_utc,\n"
            + " COALESCE(f.sin_enlace_60d,0) AS pendientes_enlazar,\n"
            + " COALESCE(f.sin_descarga_completa_60d,0) AS pendientes_descargar_cuotas,\n"
            + " COALESCE(f.sin_enlace_60d,0)+COALESCE(f.sin_descarga_completa_60d,0) AS total_pendientes_conocidos,\n"
            + " CASE WHEN a.ultima_pagina_1_utc IS NULL THEN 'REVISAR: sin pagina 1'\n"
            + " WHEN a.ultima_pagina_1_utc < UTC_TIMESTAMP()-INTERVAL 4 DAY THEN 'REFRESCAR CALENDARIO'\n"
            + " WHEN COALESCE(f.sin_enlace_60d,0)>0 THEN 'ENLAZAR PARTIDOS'\n"
            + " WHEN COALESCE(f.sin_descarga_completa_60d,0)>0 THEN 'DESCARGAR CUOTAS'\n"
            + " ELSE 'SIN PENDIENTES LOCALES' END AS proxima_accion\n"
            + " FROM ligas l LEFT JOIN ultimo_tc t ON t.tc_pk=l.tc_pk\n"
            + " LEFT JOIN ultima_api a ON a.tc_pk=l.tc_pk\n"
            + " LEFT JOIN resumen_footiqo f ON f.tc_pk=l.tc_pk\n"
            + " ORDER BY l.liga";
    }
}