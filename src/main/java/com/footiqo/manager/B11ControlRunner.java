package com.footiqo.manager;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
    private static final Set<String> KNOWN_TC_IDS = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList("1", "14", "12", "16", "25", "137", "138", "2")));
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
                try (PreparedStatement ps = db.prepareStatement(buildSql(ids.size()))) {
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
                    System.out.println("B11_DONE\t" + rows + "\t" + ids.size()
                            + "\t" + sanitize(desdeMadrid));
                }
            }
        } catch (Throwable err) {
            // No revelar la URL ni posibles detalles sensibles de conexion.
            String message;
            if (err instanceof SQLException) {
                SQLException sqlErr = (SQLException) err;
                message = "Error de consulta/conexion MySQL (SQLState="
                        + sanitize(sqlErr.getSQLState()) + ", codigo=" + sqlErr.getErrorCode()
                        + "). Comprueba la conexion, las tablas TC y el esquema B1.1.";
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
            if (!KNOWN_TC_IDS.contains(id))
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

    /** B1.1 de Obsidian; cambia solo la seleccion parametrizada y corte Madrid. */
    static String buildSql(int leagueCount) {
        if (leagueCount < 1 || leagueCount > KNOWN_TC_IDS.size())
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
}
