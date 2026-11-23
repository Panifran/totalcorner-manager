package com.footiqo.manager;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Diagnostico B4 de solo lectura: replica el alcance del contador y valida las precondiciones del Builder. */
public final class B4PendingDiagnosticRunner {
    private B4PendingDiagnosticRunner() { }

    private static final String[] HEADERS = {
        "motivo", "accion_sugerida", "ls_match_id", "tc_match_pk", "tc_match_id_api",
        "liga_tc", "liga", "fecha_footiqo", "equipo_local_ls", "equipo_visitante_ls",
        "marcador_ls", "estado_tc", "marcador_tc", "bookmaker_ultimo_estado", "bookmaker_ultimo_http",
        "bookmaker_done", "bookmaker_json_valido", "bet365_ultimo_estado", "bet365_ultimo_http",
        "bet365_done", "bet365_json_valido", "cobertura_job_estado", "cobertura_job_version",
        "cobertura_resumen_version", "proximo_intento_utc", "intentos", "max_intentos",
        "lease_hasta_utc", "motivos_adicionales"
    };

    private static String env(String name, String fallback) {
        String v=System.getenv(name);return v==null||v.trim().isEmpty()?fallback:v;
    }
    private static String text(String v) {return v==null?"":v.replace('\r',' ').replace('\n',' ').replace('\t',' ');}
    private static String val(ResultSet r,String c) throws SQLException {return text(r.getString(c));}
    private static String csv(String v) {
        v=text(v);
        if(!v.isEmpty() && "=+-@".indexOf(v.charAt(0))>=0) v="'"+v;
        return "\""+v.replace("\"","\"\"")+"\"";
    }
    private static void line(BufferedWriter w,String[] parts) throws Exception {
        for(int i=0;i<parts.length;i++){if(i>0)w.write(',');w.write(csv(parts[i]));}w.newLine();
    }
    private static boolean yes(ResultSet r,String c) throws SQLException {return r.getInt(c)==1;}
    private static String status(ResultSet r,String c) throws SQLException {String v=val(r,c);return v.isEmpty()?"SIN_JOB":v;}
    private static int number(ResultSet r,String c) throws SQLException {return r.getInt(c);}
    private static boolean afterNow(Timestamp t) {return t!=null&&t.toInstant().isAfter(Instant.now());}

    public static void main(String[] args) {
        try {
            if(args.length==1&&"--self-test".equals(args[0])) {
                if(!SQL.contains("tc_cov_scope_season")||!SQL.contains("payload_gzip_size_bytes")
                    ||!SQL.contains("tc_api_response")||!SQL.contains("completed_builder_version")
                    ||!SQL.contains("MATCH_BET365_HISTORY")||!SQL.contains("review_status='VERIFIED'"))
                    throw new IllegalStateException("SQL diagnostico incompleto");
                if(!csv("A,B").equals("\"A,B\"")) throw new IllegalStateException("CSV sin escape");
                System.out.println("B4_DIAG_SELF_TEST_OK");return;
            }
            Map<String,String> opts=new HashMap<String,String>();
            for(String arg:args) {int p=arg.indexOf('=');if(!arg.startsWith("--")||p<3||
                    opts.put(arg.substring(2,p),arg.substring(p+1))!=null)
                    throw new IllegalArgumentException("Argumento invalido o repetido");}
            if(opts.size()!=2||!opts.containsKey("league-ids")||!opts.containsKey("output"))
                throw new IllegalArgumentException("Requiere --league-ids y --output");
            List<String> leagues=B11ControlRunner.parseIds(new String[] {"--league-ids="+opts.get("league-ids")});
            Path output=Paths.get(opts.get("output")).toAbsolutePath().normalize();
            if(!".csv".equalsIgnoreCase(output.getFileName().toString().substring(
                    Math.max(0,output.getFileName().toString().lastIndexOf('.')))))
                throw new IllegalArgumentException("Salida debe ser CSV");
            String password=System.getenv("TOTALCORNER_DB_PASSWORD");
            if(password==null||password.isEmpty())throw new IllegalArgumentException("Falta TOTALCORNER_DB_PASSWORD");
            Class.forName("com.mysql.cj.jdbc.Driver");DriverManager.setLoginTimeout(12);
            int count=0,needsB3=0,invalidJson=0,blocked=0,ready=0;
            Files.createDirectories(output.getParent());
            try(Connection db=DriverManager.getConnection(env("TOTALCORNER_DB_URL",
                    "jdbc:mysql://localhost:3307/freefootstats?serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8&connectTimeout=10000&socketTimeout=60000"),
                    env("TOTALCORNER_DB_USER","root"),password)) {
                if(!"freefootstats".equalsIgnoreCase(db.getCatalog()))
                    throw new IllegalStateException("Base de datos incorrecta");
                try(Statement st=db.createStatement()){st.execute("SET SESSION time_zone='+00:00'");}
                db.setReadOnly(true);
                StringBuilder marks=new StringBuilder();for(int i=0;i<leagues.size();i++){
                    if(i>0)marks.append(',');marks.append('?');}
                try(PreparedStatement ps=db.prepareStatement(SQL.replace("__IN__",marks.toString()));
                    BufferedWriter w=Files.newBufferedWriter(output,StandardCharsets.UTF_8)) {
                    ps.setQueryTimeout(180);int i=1;for(String l:leagues)ps.setString(i++,l);
                    w.write('\ufeff');line(w,HEADERS);
                    try(ResultSet r=ps.executeQuery()) {
                        while(r.next()) {
                            if(++count>10000)throw new IllegalStateException("Demasiados pendientes (>10000). Reducir ligas");
                            boolean bkDone=yes(r,"book_done"),btDone=yes(r,"bet_done");
                            boolean bkValid=yes(r,"book_json_ok"),btValid=yes(r,"bet_json_ok");
                            String covStatus=val(r,"cov_job_status"),covVersion=val(r,"cov_job_version");
                            String summaryVersion=val(r,"summary_version");
                            String tcStatus=val(r,"tc_status");
                            boolean tcGoals=r.getObject("tc_home_goals")!=null&&r.getObject("tc_away_goals")!=null;
                            boolean tcFinal=Arrays.asList("full","ft","ended").contains(tcStatus.toLowerCase(Locale.ROOT));
                            String reason,action;
                            if(!bkDone||!btDone) {
                                reason=(!bkDone&&!btDone)?"FALTAN_AMBOS_JOBS_B3":!bkDone?"FALTA_BOOKMAKER_B3":"FALTA_BET365_B3";
                                action="Consultar B3 y revisar los jobs; ejecutar descarga si el partido es elegible";
                                needsB3++;
                            } else if(!bkValid||!btValid) {
                                reason=(!bkValid&&!btValid)?"AMBOS_JSON_INVALIDOS":!bkValid?"JSON_BOOKMAKER_INVALIDO":"JSON_BET365_INVALIDO";
                                action="Comprobar respuesta HTTP, endpoint y JSON archivado; revisar B3 antes de reintentar";
                                invalidJson++;
                            } else if("DONE".equals(covStatus)&&"v2downloaded".equals(covVersion)&&!"v2downloaded".equals(summaryVersion)) {
                                reason="DESCUADRE_VERSION_CONTROL_BUILDER";
                                action="El contador exige version de resumen actual; Builder no selecciona este caso. Revisar correccion";
                                blocked++;
                            } else if(("PENDING".equals(covStatus)||"RETRY".equals(covStatus)) &&
                                (number(r,"attempt_count")>=number(r,"max_attempts") || afterNow(r.getTimestamp("next_run_at")))) {
                                reason="JOB_B4_EN_ESPERA_O_AGOTADO";
                                action="Revisar next_run_at e intentos de tc_cov_job; no forzar a ciegas";
                                blocked++;
                            } else if("RUNNING".equals(covStatus)&&afterNow(r.getTimestamp("leased_until"))) {
                                reason="JOB_B4_BLOQUEADO_POR_LEASE";
                                action="Esperar al lease o revisar proceso B4 anterior";
                                blocked++;
                            } else if(!covStatus.isEmpty() && !Arrays.asList("PENDING","RETRY","RUNNING","DONE").contains(covStatus)) {
                                reason="JOB_B4_ESTADO_NO_ELEGIBLE";
                                action="Revisar estado de tc_cov_job (no modificar sin comprobar)";
                                blocked++;
                            } else {
                                reason="FUENTES_OK_VERIFICAR_BUILDER";
                                action="Ambos JSON cumplen requisitos; revisar seleccion SQL/estado B4 antes de actuar";
                                ready++;
                            }
                            String extras="";
                            if(!tcFinal)extras="TC_ESTADO_NO_FINAL";
                            if(!tcGoals)extras+=(extras.isEmpty()?"":"|")+"TC_SIN_MARCADOR";
                            String[] values = {reason,action,val(r,"ls_match_id"),val(r,"tc_match_pk"),val(r,"tc_api_id"),
                                val(r,"tc_league_id"),val(r,"league_name"),val(r,"matchDate"),val(r,"home_name"),val(r,"away_name"),
                                val(r,"ls_score"),tcStatus,val(r,"tc_score"),status(r,"book_latest_status"),val(r,"book_latest_http"),
                                bkDone?"SI":"NO",bkValid?"SI":"NO",status(r,"bet_latest_status"),val(r,"bet_latest_http"),
                                btDone?"SI":"NO",btValid?"SI":"NO",status(r,"cov_job_status"),covVersion,
                                summaryVersion,val(r,"next_run_at"),val(r,"attempt_count"),val(r,"max_attempts"),
                                val(r,"leased_until"),extras};
                            line(w,values);
                            System.out.println("B4_DIAG_ROW\t"+values[2]+"\t"+values[7]+"\t"+reason+
                                "\t"+values[15]+"/"+values[19]+"\t"+values[16]+"/"+values[20]+"\t"+extras);
                        }
                    }
                }
            }
            System.out.println("B4_DIAG_DONE\t"+count+"\t"+needsB3+"\t"+invalidJson+"\t"+blocked+"\t"+ready+"\t"+output);
        }catch(Throwable e) {
            String msg=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            if(e instanceof SQLException){SQLException s=(SQLException)e;msg="SQLState="+s.getSQLState()+" codigo="+s.getErrorCode()+" "+msg;}
            System.err.println("B4_DIAG_ERROR\t"+text(msg));System.exit(1);
        }
    }

    private static final String SQL=
        "WITH base AS ("+
        " SELECT l.tc_league_id, l.name AS league_name, lm.id AS ls_match_id, lm.matchDate, "+
        " CONCAT(COALESCE(lm.ftHomeTeamGoals,'?'),'-',COALESCE(lm.ftAwayTeamGoals,'?')) AS ls_score, "+
        " home.name AS home_name, away.name AS away_name, "+
        " tm.id AS tc_match_pk, tm.tc_match_id AS tc_api_id, tm.match_status AS tc_status, "+
        " tm.home_goals AS tc_home_goals, tm.away_goals AS tc_away_goals, "+
        " CONCAT(COALESCE(tm.home_goals,'?'),'-',COALESCE(tm.away_goals,'?')) AS tc_score, "+
        " c.ls_match_id AS summary_id, c.tc_match_pk AS summary_tc_pk, c.ls_kickoff_madrid AS summary_date, "+
        " c.builder_version AS summary_version, c.calculated_at, "+
        " j.status AS cov_job_status, j.completed_builder_version AS cov_job_version, j.next_run_at, "+
        " j.attempt_count, j.max_attempts, j.leased_until, "+
        " jb.id AS book_done_id, jt.id AS bet_done_id, jb.finished_at AS bookmaker_terminado, jt.finished_at AS bet365_terminado, "+
        " CASE WHEN jb.id IS NULL THEN 0 ELSE 1 END AS book_done, "+
        " CASE WHEN jt.id IS NULL THEN 0 ELSE 1 END AS bet_done, "+
        " CASE WHEN rb.id IS NOT NULL AND rb.match_id=tm.id AND rb.http_status=200 "+
        " AND rb.payload_size_bytes>0 AND rb.payload_gzip_size_bytes>0 "+
        " AND rb.endpoint=CONCAT('/match/bookmaker_odds/',tm.tc_match_id) THEN 1 ELSE 0 END AS book_json_ok, "+
        " CASE WHEN rt.id IS NOT NULL AND rt.match_id=tm.id AND rt.http_status=200 "+
        " AND rt.payload_size_bytes>0 AND rt.payload_gzip_size_bytes>0 "+
        " AND rt.endpoint=CONCAT('/match/odds/',tm.tc_match_id) THEN 1 ELSE 0 END AS bet_json_ok, "+
        " jbl.status AS book_latest_status, jbl.last_http_status AS book_latest_http, "+
        " jtl.status AS bet_latest_status, jtl.last_http_status AS bet_latest_http "+
        " FROM tc_league l "+
        " JOIN tc_match tm ON tm.league_id=l.id "+
        " JOIN tc_match_link tl ON tl.tc_match_pk=tm.id AND tl.review_status='VERIFIED' "+
        " JOIN ls_match lm ON lm.id=tl.ls_match_id AND lm.pendingFinalResult=b'0' "+
        " LEFT JOIN ls_team home ON home.id=lm.lsHomeTeam_id LEFT JOIN ls_team away ON away.id=lm.lsAwayTeam_id "+
        " JOIN tc_cov_scope_season sc ON sc.ls_league_season_id=lm.lsLeague_season_id AND sc.enabled=1 "+
        " LEFT JOIN tc_cov_match c ON c.ls_match_id=lm.id LEFT JOIN tc_cov_job j ON j.ls_match_id=lm.id "+
        " LEFT JOIN tc_download_job jb ON jb.id=(SELECT MAX(q.id) FROM tc_download_job q "+
        " WHERE q.match_id=tm.id AND q.job_type='MATCH_BOOKMAKER_ODDS' AND q.status='DONE' AND q.last_response_id IS NOT NULL) "+
        " LEFT JOIN tc_download_job jt ON jt.id=(SELECT MAX(q.id) FROM tc_download_job q "+
        " WHERE q.match_id=tm.id AND q.job_type='MATCH_BET365_HISTORY' AND q.status='DONE' AND q.last_response_id IS NOT NULL) "+
        " LEFT JOIN tc_api_response rb ON rb.id=jb.last_response_id "+
        " LEFT JOIN tc_api_response rt ON rt.id=jt.last_response_id "+
        " LEFT JOIN tc_download_job jbl ON jbl.id=(SELECT MAX(q.id) FROM tc_download_job q "+
        " WHERE q.match_id=tm.id AND q.job_type='MATCH_BOOKMAKER_ODDS') "+
        " LEFT JOIN tc_download_job jtl ON jtl.id=(SELECT MAX(q.id) FROM tc_download_job q "+
        " WHERE q.match_id=tm.id AND q.job_type='MATCH_BET365_HISTORY') "+
        " WHERE l.tc_league_id IN (__IN__) "+
        " AND NOT EXISTS (SELECT 1 FROM tc_match_exception ex WHERE ex.ls_match_id=lm.id "+
        " AND ex.is_active=1 AND ex.exception_code='NOT_FOUND_IN_TC') "+
        ") SELECT * FROM base WHERE "+
        " summary_id IS NULL OR cov_job_status IS NULL OR cov_job_status<>'DONE' "+
        " OR COALESCE(cov_job_version,'')<>'v2downloaded' "+
        " OR COALESCE(summary_version,'')<>'v2downloaded' "+
        " OR NOT(summary_tc_pk <=> tc_match_pk) OR NOT(summary_date <=> matchDate) "+
        " OR (bookmaker_terminado IS NOT NULL AND bookmaker_terminado>calculated_at) "+
        " OR (bet365_terminado IS NOT NULL AND bet365_terminado>calculated_at) "+
        " ORDER BY matchDate DESC,ls_match_id LIMIT 10001";
}
