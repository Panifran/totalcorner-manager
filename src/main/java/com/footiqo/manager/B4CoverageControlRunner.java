package com.footiqo.manager;

import java.sql.*;
import java.util.*;

/** B4: consulta de estado de cobertura (solo lectura), conforme al SQL de la guia operativa. */
public final class B4CoverageControlRunner {
    private B4CoverageControlRunner() { }
    private static String env(String k,String fallback) {
        String s=System.getenv(k);return s==null||s.trim().isEmpty()?fallback:s;
    }
    private static String clean(String s) {
        return s==null?"":s.replace('\t',' ').replace('\n',' ').replace('\r',' ');
    }
    private static String required(Map<String,String> opts,String name) {
        String x=opts.get(name);if(x==null||x.trim().isEmpty())throw new IllegalArgumentException("Falta --"+name);return x;
    }
    public static void main(String[] args) {
        try {
            if(args.length==1&&"--self-test".equals(args[0])) {
                String check=sql(3);
                if(!check.contains("v2downloaded")||!check.contains("tc_cov_scope_season")||!check.contains("tc_cov_job")
                    ||!check.contains("review_status='VERIFIED'") || !check.contains("builder_elegible")
                    ||!check.contains("fuentes_ok"))throw new IllegalStateException("SQL B4 incompleto");
                System.out.println("B4_SELF_TEST_OK");return;
            }
            Map<String,String> opts=new HashMap<String,String>();
            for(String arg:args){int i=arg.indexOf('=');if(!arg.startsWith("--")||i<3||opts.put(arg.substring(2,i),arg.substring(i+1))!=null)
                throw new IllegalArgumentException("Argumento invalido/duplicado");}
            List<String> ids=B11ControlRunner.parseIds(new String[]{"--league-ids="+required(opts,"league-ids")});
            if(opts.size()!=1)throw new IllegalArgumentException("Parametros B4 no reconocidos");
            String password=System.getenv("TOTALCORNER_DB_PASSWORD");
            if(password==null||password.isEmpty())throw new IllegalArgumentException("Falta TOTALCORNER_DB_PASSWORD");
            Class.forName("com.mysql.cj.jdbc.Driver");
            DriverManager.setLoginTimeout(12);
            try(Connection db=DriverManager.getConnection(
                    env("TOTALCORNER_DB_URL","jdbc:mysql://localhost:3307/freefootstats?serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8&connectTimeout=10000&socketTimeout=30000"),
                    env("TOTALCORNER_DB_USER","root"),password)) {
                if(!"freefootstats".equalsIgnoreCase(db.getCatalog()))throw new IllegalStateException("Base de datos inesperada");
                // Sincroniza SOLO las temporadas de las ligas LS asociadas a las TC seleccionadas.
                // Inserciones faltantes; no modifica ni reactiva filas existentes.
                int created=CovScopeSynchronizer.synchronize(db,ids);
                System.out.println("B4_SCOPE_SYNC\t"+created);
                db.setReadOnly(true);
                try(PreparedStatement st=db.prepareStatement(sql(ids.size()))) {
                    st.setQueryTimeout(180);
                    int i=1;for(String id:ids)st.setString(i++,id);
                    for(String id:ids)st.setString(i++,id);
                    int rows=0;long pending=0,updated=0,total=0,ready=0,waiting=0,blocked=0;
                    try(ResultSet rs=st.executeQuery()) {
                        while(rs.next()) {
                            StringBuilder sb=new StringBuilder("B4_ROW");
                            for(int c=1;c<=9;c++)sb.append('\t').append(clean(rs.getString(c)));
                            System.out.println(sb.toString());
                            rows++;total+=rs.getLong(3);updated+=rs.getLong(4);pending+=rs.getLong(5);
                            ready+=rs.getLong(7);waiting+=rs.getLong(8);blocked+=rs.getLong(9);
                        }
                    }
                    if(rows!=ids.size())throw new IllegalStateException("Control B4 incompleto: "+rows+"/"+ids.size());
                    if(pending!=ready+waiting+blocked)
                        throw new IllegalStateException("B4 incoherente: pendientes != procesables + sin JSON + bloqueados");
                    System.out.println("B4_DONE\t"+rows+"\t"+pending+"\t"+updated+"\t"+total+
                        "\t"+ready+"\t"+waiting+"\t"+blocked);
                }
            }
        } catch(Throwable e){
            String m=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            if(e instanceof SQLException){SQLException x=(SQLException)e;m="SQLState="+x.getSQLState()+" codigo="+x.getErrorCode();}
            System.err.println("B4_ERROR\t"+clean(m));System.exit(1);
        }
    }
    static String sql(int n) {
        if(n<1||n>50)throw new IllegalArgumentException("Numero de ligas incorrecto");
        StringJoiner marks=new StringJoiner(",");for(int i=0;i<n;i++)marks.add("?");
        return SQL.replace("__IN__",marks.toString()).replace("__ORDER__",marks.toString());
    }
    private static final String SQL = 
            "WITH ligas AS ( " +
            "SELECT id AS tc_league_pk, tc_league_id AS tc_id, name AS liga " +
            "FROM tc_league " +
            "WHERE tc_league_id IN (__IN__) " +
            "), alcance AS ( " +
            "SELECT l.tc_id, l.liga, lm.id AS ls_match_id, lm.matchDate, " +
            "tl.tc_match_pk, c.ls_match_id AS tiene_resumen, c.tc_match_pk AS resumen_tc_pk, " +
            "c.ls_kickoff_madrid, c.builder_version, c.calculated_at, " +
            "j.status AS builder_status, j.completed_builder_version, " +
            "CASE WHEN jb.id IS NOT NULL AND rb.id IS NOT NULL AND rb.match_id=tm.id " +
            "AND rb.http_status=200 AND rb.payload_size_bytes>0 AND rb.payload_gzip_size_bytes>0 " +
            "AND rb.endpoint=CONCAT('/match/bookmaker_odds/',tm.tc_match_id) " +
            "AND jt.id IS NOT NULL AND rt.id IS NOT NULL AND rt.match_id=tm.id " +
            "AND rt.http_status=200 AND rt.payload_size_bytes>0 AND rt.payload_gzip_size_bytes>0 " +
            "AND rt.endpoint=CONCAT('/match/odds/',tm.tc_match_id) THEN 1 ELSE 0 END AS fuentes_ok, " +
            // 'jobReady' follows exactly OddsCoverageBuilder selectMatches criteria.
            "CASE WHEN j.ls_match_id IS NULL " +
            "OR (j.status IN ('PENDING','RETRY') AND j.next_run_at<=UTC_TIMESTAMP(3) AND j.attempt_count<j.max_attempts) " +
            "OR (j.status='RUNNING' AND j.leased_until<UTC_TIMESTAMP(3) AND j.attempt_count<j.max_attempts) " +
            "OR (j.status='DONE' AND (j.completed_builder_version IS NULL OR j.completed_builder_version<>'v2downloaded' " +
            "OR c.ls_match_id IS NULL OR c.tc_match_pk<>tm.id OR c.ls_kickoff_madrid<>lm.matchDate " +
            "OR (jb.finished_at IS NOT NULL AND jb.finished_at>c.calculated_at) " +
            "OR (jt.finished_at IS NOT NULL AND jt.finished_at>c.calculated_at))) " +
            "THEN 1 ELSE 0 END AS builder_elegible, " +
            "jb.finished_at AS bookmaker_terminado, " +
            "jt.finished_at AS bet365_terminado " +
            "FROM ligas l " +
            "JOIN tc_match tm ON tm.league_id=l.tc_league_pk " +
            "JOIN tc_match_link tl ON tl.tc_match_pk=tm.id AND tl.review_status='VERIFIED' " +
            "JOIN ls_match lm ON lm.id=tl.ls_match_id AND lm.pendingFinalResult=b'0' " +
            "JOIN tc_cov_scope_season sc " +
            "ON sc.ls_league_season_id=lm.lsLeague_season_id AND sc.enabled=1 " +
            "LEFT JOIN tc_cov_match c ON c.ls_match_id=lm.id " +
            "LEFT JOIN tc_cov_job j ON j.ls_match_id=lm.id " +
            "LEFT JOIN tc_download_job jb ON jb.id=( " +
            "SELECT MAX(q.id) FROM tc_download_job q " +
            "WHERE q.match_id=tm.id AND q.job_type='MATCH_BOOKMAKER_ODDS' " +
            "AND q.status='DONE' AND q.last_response_id IS NOT NULL " +
            ") " +
            "LEFT JOIN tc_download_job jt ON jt.id=( " +
            "SELECT MAX(q.id) FROM tc_download_job q " +
            "WHERE q.match_id=tm.id AND q.job_type='MATCH_BET365_HISTORY' " +
            "AND q.status='DONE' AND q.last_response_id IS NOT NULL " +
            ") " +
            "LEFT JOIN tc_api_response rb ON rb.id=jb.last_response_id " +
            "LEFT JOIN tc_api_response rt ON rt.id=jt.last_response_id " +
            "WHERE NOT EXISTS ( " +
            "SELECT 1 FROM tc_match_exception ex " +
            "WHERE ex.ls_match_id=lm.id AND ex.is_active=1 " +
            "AND ex.exception_code='NOT_FOUND_IN_TC' " +
            ") " +
            "), clasificados AS ( " +
            "SELECT *, CASE WHEN " +
            "tiene_resumen IS NULL " +
            "OR builder_status IS NULL OR builder_status <> 'DONE' " +
            "OR COALESCE(completed_builder_version,'') <> 'v2downloaded' " +
            "OR COALESCE(builder_version,'') <> 'v2downloaded' " +
            "OR NOT (resumen_tc_pk <=> tc_match_pk) " +
            "OR NOT (ls_kickoff_madrid <=> matchDate) " +
            "OR (bookmaker_terminado IS NOT NULL AND bookmaker_terminado>calculated_at) " +
            "OR (bet365_terminado IS NOT NULL AND bet365_terminado>calculated_at) " +
            "THEN 1 ELSE 0 END AS necesita_cobertura " +
            "FROM alcance " +
            ") " +
            "SELECT l.tc_id, l.liga, " +
            "COUNT(c.ls_match_id) AS finalizados_enlazados_en_alcance, " +
            "COALESCE(SUM(CASE WHEN c.necesita_cobertura=0 THEN 1 ELSE 0 END),0) AS cobertura_al_dia, " +
            "COALESCE(SUM(CASE WHEN c.necesita_cobertura=1 THEN 1 ELSE 0 END),0) AS cobertura_pendiente_o_desactualizada, " +
            "MAX(CASE WHEN c.necesita_cobertura=1 THEN c.matchDate END) AS ultimo_partido_pendiente, " +
            "COALESCE(SUM(CASE WHEN c.necesita_cobertura=1 AND c.fuentes_ok=1 AND c.builder_elegible=1 THEN 1 ELSE 0 END),0) AS procesables, " +
            "COALESCE(SUM(CASE WHEN c.necesita_cobertura=1 AND c.fuentes_ok=0 THEN 1 ELSE 0 END),0) AS esperando_b3, " +
            "COALESCE(SUM(CASE WHEN c.necesita_cobertura=1 AND c.fuentes_ok=1 AND c.builder_elegible=0 THEN 1 ELSE 0 END),0) AS bloqueados " +
            "FROM ligas l " +
            "LEFT JOIN clasificados c ON c.tc_id=l.tc_id " +
            "GROUP BY l.tc_id,l.liga " +
            "ORDER BY FIELD(l.tc_id,__ORDER__); ";
}
