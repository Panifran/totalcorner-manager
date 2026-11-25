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
 * B2.1 de la Guia Operativa: previsualizacion, SIN INSERT/UPDATE/DELETE.
 * Mantiene la ventana Madrid 60 dias y permite tolerancia configurable (5 min predeterminados).
 * Usa CTEs (MySQL 8) en lugar de tablas temporales de usuario. Los resultados
 * no autorizan por si solos a ejecutar B2.2: este es un control de diagnostico.
 */
public final class B21PreviewRunner {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private B21PreviewRunner() {}

    public static void main(String[] args) {
        try {
            if (args.length == 1 && "--self-test".equals(args[0])) {
                if (parseTolerance(new String[]{"--league-ids=1", "--tolerance-minutes=0"}) != 0
                        || parseTolerance(new String[]{"--league-ids=1", "--tolerance-minutes=5"}) != 5
                        || parseTolerance(new String[]{"--league-ids=1", "--tolerance-minutes=14400"}) != 14400)
                    throw new IllegalStateException("La validacion de tolerancia B2.1 no funciona.");
                try {
                    parseTolerance(new String[]{"--league-ids=1", "--tolerance-minutes=14401"});
                    throw new IllegalStateException("Se ha permitido una tolerancia fuera de rango.");
                } catch (IllegalArgumentException esperado) { /* correcto */ }
                String base = ctes(8);
                String summary = base + summarySql();
                String detail = base + detailSql();
                if (placeholders(summary) != 11 || placeholders(detail) != 11
                        || !summary.contains("PROFILE_VERIFIED")
                        || !summary.contains("COUNT(*) OVER (PARTITION BY")
                        || !summary.contains("REVISAR_AMBIGUO")
                        || !summary.contains("REVISAR_MARCADOR")
                        || !summary.contains("TC_SIN_MARCADOR")
                        || !summary.contains("REVISAR_ENLACE_EXISTENTE")
                        || !summary.contains("REVISAR_TOLERANCIA_AMPLIADA")
                        || !base.contains("INTERVAL tol.tolerancia_minutos MINUTE")
                        || !base.contains("p.tolerancia_minutos + 1440")
                        || summary.contains("INSERT INTO") || summary.contains("UPDATE tc_")
                        || summary.contains("DELETE FROM") || detail.contains("INSERT INTO")) {
                    throw new IllegalStateException("B2.1 no supera comprobacion estructural.");
                }
                System.out.println("B21_SELF_TEST_OK - 8 ligas, 11 parametros por SELECT, tolerancia configurable; sin escrituras");
                return;
            }
            List<String> ids = parseIds(args);
            int tolerance = parseTolerance(args);
            String pass = System.getenv("TOTALCORNER_DB_PASSWORD");
            if (pass == null || pass.trim().isEmpty())
                throw new IllegalArgumentException("Falta TOTALCORNER_DB_PASSWORD en el entorno del gestor.");
            String url = env("TOTALCORNER_DB_URL",
                    "jdbc:mysql://localhost:3307/freefootstats?serverTimezone=UTC&useUnicode=true");
            String user = env("TOTALCORNER_DB_USER", "root");
            Class.forName("com.mysql.cj.jdbc.Driver");
            DriverManager.setLoginTimeout(12);
            LocalDateTime end = LocalDateTime.now(ZoneId.of("Europe/Madrid"));
            String desde = end.minusDays(60).format(DATE);
            String hasta = end.format(DATE);
            try (Connection db = DriverManager.getConnection(url, user, pass)) {
                db.setReadOnly(true);
                db.setAutoCommit(true);
                // Verifica tablas de zona horaria y ambos limites reales del periodo antes de buscar candidatos.
                try (PreparedStatement check = db.prepareStatement(
                        "SELECT CONVERT_TZ('2026-11-01 12:00:00','+00:00','Europe/Madrid'), "
                        + "CONVERT_TZ(CAST(? AS DATETIME),'Europe/Madrid','+00:00'), "
                        + "CONVERT_TZ(CAST(? AS DATETIME),'Europe/Madrid','+00:00')")) {
                    check.setString(1, desde);
                    check.setString(2, hasta);
                    try (ResultSet rs = check.executeQuery()) {
                        if (!rs.next() || rs.getString(1) == null
                                || rs.getString(2) == null || rs.getString(3) == null)
                            throw new IllegalStateException("CONVERT_TZ devuelve NULL para Europe/Madrid o algun limite de fechas; B2.1 cancelado.");
                    }
                }
                int totalListos = 0, totalRevision = 0, totalSinCandidato = 0;
                int summaryRows = 0, detailRows = 0;
                try (PreparedStatement ps = db.prepareStatement(ctes(ids.size()) + summarySql())) {
                    ps.setQueryTimeout(180);
                    bind(ps, ids, desde, hasta, tolerance);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            int pendientes = rs.getInt("pendientes_sin_enlace");
                            int listos = rs.getInt("listos_para_enlazar");
                            int revisar = rs.getInt("requieren_revision");
                            int sin = rs.getInt("sin_candidato_tolerancia");
                            if (pendientes != listos + revisar + sin)
                                throw new IllegalStateException("B2.1: no cuadran contadores de " + rs.getString("liga"));
                            totalListos += listos;
                            totalRevision += revisar;
                            totalSinCandidato += sin;
                            System.out.println("B21_ROW\t" + clean(rs.getString("tc_id")) + "\t"
                                    + clean(rs.getString("liga")) + "\t" + pendientes + "\t"
                                    + listos + "\t" + revisar + "\t" + sin);
                            summaryRows++;
                        }
                    }
                }
                if (summaryRows != ids.size())
                    throw new IllegalStateException("Se solicitaron " + ids.size()
                            + " ligas, pero el resumen solo contiene " + summaryRows + ".");
                try (PreparedStatement ps = db.prepareStatement(ctes(ids.size()) + detailSql())) {
                    ps.setQueryTimeout(180);
                    bind(ps, ids, desde, hasta, tolerance);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            StringBuilder line = new StringBuilder("B21_DETAIL");
                            for (int i = 1; i <= 8; i++) line.append('\t').append(clean(rs.getString(i)));
                            System.out.println(line);
                            detailRows++;
                        }
                    }
                }
                System.out.println("B21_DONE\t" + summaryRows + "\t" + ids.size()
                        + "\t" + totalListos + "\t" + totalRevision + "\t"
                        + totalSinCandidato + "\t" + detailRows + "\t" + desde + "\t" + hasta + "\t" + tolerance);
            }
        } catch (Throwable e) {
            String message;
            if (e instanceof SQLException) {
                SQLException sql = (SQLException) e;
                message = "Error MySQL en B2.1 (SQLState=" + clean(sql.getSQLState())
                        + ", codigo=" + sql.getErrorCode() + "). Comprueba esquema y zonas horarias.";
            } else if (e instanceof ClassNotFoundException) {
                message = "No se encuentra el driver MySQL JDBC del proyecto downloader.";
            } else {
                message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            System.err.println("B21_ERROR\t" + clean(message));
            System.exit(1);
        }
    }

    static List<String> parseIds(String[] args) {
        if (args.length != 2 || !args[0].startsWith("--league-ids="))
            throw new IllegalArgumentException("Uso: B21PreviewRunner --league-ids=1,14,... --tolerance-minutes=5");
        Set<String> seen = new HashSet<String>();
        List<String> ids = new ArrayList<String>();
        for (String raw : args[0].substring("--league-ids=".length()).split(",", -1)) {
            String id = raw.trim();
            if ((!LeagueMappingRegistry.load().containsKey(id) || !LeagueMappingRegistry.load().get(id).updates))
                throw new IllegalArgumentException("No hay correspondencia TC/Footiqo B2.1 para TC=" + id);
            if (!seen.add(id)) throw new IllegalArgumentException("Liga duplicada: " + id);
            ids.add(id);
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("Selecciona alguna liga.");
        return ids;
    }

    static int parseTolerance(String[] args) {
        if (args.length != 2 || !args[1].startsWith("--tolerance-minutes="))
            throw new IllegalArgumentException("Especifica --tolerance-minutes=5 (rango 0..14400).");
        String raw = args[1].substring("--tolerance-minutes=".length());
        if (!raw.matches("[0-9]{1,5}"))
            throw new IllegalArgumentException("Tolerancia invalida; utiliza minutos enteros de 0 a 14400.");
        int minutes = Integer.parseInt(raw);
        if (minutes < 0 || minutes > 14400)
            throw new IllegalArgumentException("La tolerancia debe estar entre 0 y 14400 minutos.");
        return minutes;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static String clean(String text) {
        return text == null ? "" : text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static int placeholders(String sql) { return sql.length() - sql.replace("?", "").length(); }
    private static void bind(PreparedStatement ps, List<String> ids, String desde, String hasta, int tolerance) throws SQLException {
        int i = 1;
        for (String id : ids) ps.setString(i++, id);
        ps.setString(i++, desde);
        ps.setString(i++, hasta);
        ps.setInt(i++, tolerance);
    }

    static String ctes(int leagueCount) {
        if (leagueCount < 1 || leagueCount > LeagueMappingRegistry.load().size())
            throw new IllegalArgumentException("Numero de ligas no valido.");
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < leagueCount; i++) {
            if (i != 0) ids.append(',');
            ids.append('?');
        }
        return "WITH ligas AS (\n"
                + " " + LeagueMappingRegistry.sqlCteRows() + "\n"                + "), seleccion AS (SELECT * FROM ligas WHERE tc_id IN (" + ids + ")),\n"
                + " parametros AS (SELECT CAST(? AS DATETIME) AS desde, CAST(? AS DATETIME) AS hasta, "
                + " CAST(? AS UNSIGNED) AS tolerancia_minutos),\n"
                + " pendientes_ls AS (\n"
                + " SELECT x.tc_id,x.ls_league_id,x.liga, lm.id AS ls_match_id,\n"
                + " lm.matchDate AS kickoff_madrid, lm.lsHomeTeam_id AS ls_home_team_id,\n"
                + " lm.lsAwayTeam_id AS ls_away_team_id, lm.ftHomeTeamGoals AS ls_home_goals,\n"
                + " lm.ftAwayTeamGoals AS ls_away_goals\n"
                + " FROM ls_match lm JOIN ls_league_season lls ON lls.id=lm.lsLeague_season_id\n"
                + " JOIN seleccion x ON x.ls_league_id=lls.league_id CROSS JOIN parametros p\n"
                + " WHERE lm.pendingFinalResult=b'0' AND lm.matchDate>=p.desde AND lm.matchDate<p.hasta\n"
                + " AND NOT EXISTS (SELECT 1 FROM tc_match_link old\n"
                + "   WHERE old.ls_match_id=lm.id AND old.review_status='VERIFIED')\n"
                + "), candidatos_tc AS (\n"
                + " SELECT x.tc_id,x.ls_league_id, tm.id AS tc_match_pk, tm.tc_match_id AS tc_partido_api,\n"
                + " CONVERT_TZ(tm.kickoff_utc,'+00:00','Europe/Madrid') AS kickoff_madrid,\n"
                + " tm.kickoff_timezone_status, tm.home_team_id AS tc_home_team_pk,\n"
                + " tm.away_team_id AS tc_away_team_pk, tm.home_goals AS tc_home_goals,\n"
                + " tm.away_goals AS tc_away_goals\n"
                + " FROM tc_match tm JOIN tc_league tl ON tl.id=tm.league_id\n"
                + " JOIN seleccion x ON tl.tc_league_id COLLATE utf8mb4_unicode_ci\n"
                + "  = x.tc_id COLLATE utf8mb4_unicode_ci CROSS JOIN parametros p\n"
                + " WHERE tm.kickoff_utc >= CONVERT_TZ(p.desde,'Europe/Madrid','+00:00') "
                + "- INTERVAL (p.tolerancia_minutos + 1440) MINUTE\n"
                + " AND tm.kickoff_utc < CONVERT_TZ(p.hasta,'Europe/Madrid','+00:00') "
                + "+ INTERVAL (p.tolerancia_minutos + 1440) MINUTE\n"
                + "), pares AS (\n"
                + " SELECT DISTINCT l.tc_id,l.liga,l.ls_match_id,t.tc_match_pk,t.tc_partido_api,\n"
                + " l.kickoff_madrid AS fecha_footiqo,t.kickoff_madrid AS fecha_totalcorner,\n"
                + " TIMESTAMPDIFF(SECOND,l.kickoff_madrid,t.kickoff_madrid) AS diferencia_segundos,\n"
                + " l.ls_home_goals,l.ls_away_goals,t.tc_home_goals,t.tc_away_goals,\n"
                + " t.kickoff_timezone_status\n"
                + " FROM pendientes_ls l CROSS JOIN parametros tol "
                + "JOIN candidatos_tc t ON t.ls_league_id=l.ls_league_id AND t.tc_id=l.tc_id\n"
                + " AND t.kickoff_madrid BETWEEN l.kickoff_madrid-INTERVAL tol.tolerancia_minutos MINUTE\n"
                + "   AND l.kickoff_madrid+INTERVAL tol.tolerancia_minutos MINUTE\n"
                + " JOIN tc_team_link ht ON ht.tc_team_pk=t.tc_home_team_pk\n"
                + " AND ht.ls_team_id=l.ls_home_team_id AND ht.review_status='VERIFIED'\n"
                + " JOIN tc_team_link at ON at.tc_team_pk=t.tc_away_team_pk\n"
                + " AND at.ls_team_id=l.ls_away_team_id AND at.review_status='VERIFIED'\n"
                + "), contados AS (\n"
                + " SELECT p.*, COUNT(*) OVER (PARTITION BY p.ls_match_id) AS candidatos_tc_num,\n"
                + " COUNT(*) OVER (PARTITION BY p.tc_match_pk) AS candidatos_ls_num FROM pares p\n"
                + "), evaluados AS (\n"
                + " SELECT p.*, CASE\n"
                + " WHEN EXISTS (SELECT 1 FROM tc_match_link ml WHERE ml.ls_match_id=p.ls_match_id)\n"
                + " OR EXISTS (SELECT 1 FROM tc_match_link ml WHERE ml.tc_match_pk=p.tc_match_pk)\n"
                + " THEN 'REVISAR_ENLACE_EXISTENTE'\n"
                + " WHEN p.candidatos_tc_num<>1 OR p.candidatos_ls_num<>1 THEN 'REVISAR_AMBIGUO'\n"
                + " WHEN p.kickoff_timezone_status IS NULL OR p.kickoff_timezone_status<>'PROFILE_VERIFIED'\n"
                + " THEN 'REVISAR_ZONA_HORARIA'\n"
                // TC carece de un gol, pero ningun gol presente contradice a Footiqo.
                // Solo admite revision manual con hora <=5 min, unicidad y zona verificadas.
                + " WHEN p.ls_home_goals IS NOT NULL AND p.ls_away_goals IS NOT NULL\n"
                + " AND (p.tc_home_goals IS NULL OR p.tc_away_goals IS NULL)\n"
                + " AND (p.tc_home_goals IS NULL OR p.tc_home_goals=p.ls_home_goals)\n"
                + " AND (p.tc_away_goals IS NULL OR p.tc_away_goals=p.ls_away_goals)\n"
                + " AND ABS(p.diferencia_segundos)<=300 THEN 'TC_SIN_MARCADOR'\n"
                + " WHEN p.ls_home_goals IS NULL OR p.ls_away_goals IS NULL\n"
                + " OR p.tc_home_goals IS NULL OR p.tc_away_goals IS NULL\n"
                + " OR p.ls_home_goals<>p.tc_home_goals OR p.ls_away_goals<>p.tc_away_goals\n"
                + " THEN 'REVISAR_MARCADOR'\n"
                + " WHEN ABS(p.diferencia_segundos)>300 THEN 'REVISAR_TOLERANCIA_AMPLIADA'\n"
                + " ELSE 'LISTO_PARA_ENLAZAR' END AS estado\n"
                + " FROM contados p\n"
                + "), resumen_ls AS (\n"
                + " SELECT ls_match_id,COUNT(*) AS pares_encontrados,\n"
                + " SUM(estado='LISTO_PARA_ENLAZAR') AS pares_seguros\n"
                + " FROM evaluados GROUP BY ls_match_id\n"
                + ")\n";
    }

    private static String summarySql() {
        // Solo el TC primario de cada liga LS presenta los pendientes: no duplicar
        // el mismo ls_match en la fila de MLS regular y MLS play-offs.
        String primary = "x.tc_id=(SELECT CAST(MIN(CAST(x2.tc_id AS UNSIGNED)) AS CHAR) FROM seleccion x2 WHERE x2.ls_league_id=x.ls_league_id)";
        return "SELECT x.tc_id,x.liga, CASE WHEN "+primary+" THEN COUNT(l.ls_match_id) ELSE 0 END AS pendientes_sin_enlace,\n"
                + " CASE WHEN "+primary+" THEN COALESCE(SUM(IF(r.pares_seguros>0,1,0)),0) ELSE 0 END AS listos_para_enlazar,\n"
                + " CASE WHEN "+primary+" THEN COALESCE(SUM(IF(r.pares_encontrados>0 AND r.pares_seguros=0,1,0)),0) ELSE 0 END AS requieren_revision,\n"
                + " CASE WHEN "+primary+" THEN COALESCE(SUM(IF(l.ls_match_id IS NOT NULL AND r.pares_encontrados IS NULL,1,0)),0) ELSE 0 END AS sin_candidato_tolerancia\n"
                + " FROM seleccion x LEFT JOIN pendientes_ls l ON l.ls_league_id=x.ls_league_id AND l.tc_id=x.tc_id\n"
                + " LEFT JOIN resumen_ls r ON r.ls_match_id=l.ls_match_id\n"
                + " GROUP BY x.tc_id,x.liga,x.ls_league_id ORDER BY x.liga";
    }

    private static String detailSql() {
        return "SELECT e.liga,e.ls_match_id,e.tc_match_pk,e.tc_partido_api,\n"
                + " CAST(e.fecha_footiqo AS CHAR) AS fecha_footiqo,\n"
                + " CAST(e.fecha_totalcorner AS CHAR) AS fecha_totalcorner,\n"
                + " e.diferencia_segundos,e.estado FROM evaluados e\n"
                + " UNION ALL\n"
                + " SELECT l.liga,l.ls_match_id,NULL,NULL,CAST(l.kickoff_madrid AS CHAR),NULL,NULL,\n"
                + " 'SIN_CANDIDATO_EN_TOLERANCIA' FROM pendientes_ls l\n"
                + " LEFT JOIN resumen_ls r ON r.ls_match_id=l.ls_match_id\n"
                + " WHERE r.ls_match_id IS NULL\n"
                + " AND l.tc_id=(SELECT CAST(MIN(CAST(x2.tc_id AS UNSIGNED)) AS CHAR) FROM seleccion x2 WHERE x2.ls_league_id=l.ls_league_id)\n"
                + " ORDER BY liga, fecha_footiqo,ls_match_id,tc_match_pk";
    }
}
