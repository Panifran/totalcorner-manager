package com.footiqo.manager;

import java.sql.*;
import java.util.*;

/** B3: conteos idempotentes de trabajos y respaldo de JSON por liga; solo lectura. */
public final class B3ControlRunner {
    private B3ControlRunner() {}
    private static String clean(String s) {return s==null?"":s.replace('\t',' ').replace('\r',' ').replace('\n',' ');}
    private static String env(String k,String fallback) {String s=System.getenv(k);return s==null||s.trim().isEmpty()?fallback:s;}
    public static void main(String[] args) {
        try {
            if(args.length==1 && "--self-test".equals(args[0])) {
                String query=sql(3);
                if (!query.contains("MATCH_BET365_HISTORY") || !query.contains("payload_gzip_size_bytes")
                    || !query.contains("review_status='VERIFIED'") || !query.contains("fm.pendingFinalResult=b'0'")
                    || !query.contains("AS tc_no_final") || query.contains("WHERE LOWER(m.match_status) IN")
                    || !query.contains("AND NOT EXISTS (SELECT 1 FROM tc_match_link link JOIN ls_match fm"))
                    throw new IllegalStateException("B3 SQL incompleto o filtra estado TC");
                System.out.println("B3_SELF_TEST_OK - filtros verificados y JSON");return;
            }
            Map<String,String> opts=new HashMap<String,String>();
            for(String arg:args) {int eq=arg.indexOf('=');if(!arg.startsWith("--")||eq<3||opts.put(arg.substring(2,eq),arg.substring(eq+1))!=null)
                throw new IllegalArgumentException("Opcion invalida/duplicada");}
            List<String> ids=B11ControlRunner.parseIds(new String[]{"--league-ids="+required(opts,"league-ids")});
            int oldest=Integer.parseInt(required(opts,"oldest-year")), newest=Integer.parseInt(required(opts,"newest-year"));
            if(oldest<2015||newest<oldest||newest>2100)throw new IllegalArgumentException("Temporadas incorrectas");
            String pwd=System.getenv("TOTALCORNER_DB_PASSWORD");if(pwd==null||pwd.isEmpty())throw new IllegalArgumentException("Falta TOTALCORNER_DB_PASSWORD");
            Class.forName("com.mysql.cj.jdbc.Driver");DriverManager.setLoginTimeout(12);
            try(Connection db=DriverManager.getConnection(env("TOTALCORNER_DB_URL","jdbc:mysql://localhost:3307/freefootstats?serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8&connectTimeout=10000&socketTimeout=30000"),
                env("TOTALCORNER_DB_USER","root"),pwd)) {
                if(!"freefootstats".equalsIgnoreCase(db.getCatalog()))throw new IllegalStateException("Base de datos inesperada");
                db.setReadOnly(true);
                try(PreparedStatement p=db.prepareStatement(sql(ids.size()))) {
                    p.setQueryTimeout(180);
                    int i=1;for(String id:ids)p.setString(i++,id);
                    p.setString(i++,oldest+"-07-01 00:00:00");p.setString(i++,(newest+1)+"-07-01 00:00:00");
                    int count=0;long pending=0,missingJson=0;
                    try(ResultSet r=p.executeQuery()){
                        while(r.next()){
                            StringBuilder line=new StringBuilder("B3_ROW");
                            for(int col=1;col<=9;col++)line.append('\t').append(clean(r.getString(col)));
                            System.out.println(line); count++;
                            pending+=r.getLong(5);missingJson+=Math.max(0,r.getLong(3)-r.getLong(7));
                        }
                    }
                    if(count!=ids.size())throw new IllegalStateException("Faltan ligas en B3: "+count+"/"+ids.size());
                    System.out.println("B3_DONE\t"+count+"\t"+pending+"\t"+missingJson);
                }
            }
        } catch(Throwable err) {
            String msg=err.getMessage()==null?err.getClass().getSimpleName():err.getMessage();
            if(err instanceof SQLException){SQLException s=(SQLException)err;msg="MySQL SQLState="+s.getSQLState()+" codigo="+s.getErrorCode();}
            System.err.println("B3_ERROR\t"+clean(msg));System.exit(1);
        }
    }
    private static String required(Map<String,String> m,String k){String s=m.get(k);if(s==null||s.trim().isEmpty())throw new IllegalArgumentException("Falta --"+k);return s;}
    static String sql(int n){
        StringBuilder where=new StringBuilder();for(int i=0;i<n;i++){if(i>0)where.append(',');where.append('?');}
        return "WITH ligas AS ("+
            " SELECT l.id AS tc_pk,l.tc_league_id AS tc_id,l.name AS liga FROM tc_league l"+
            " WHERE l.enabled=1 AND l.tc_league_id IN ("+where+")"+
            "), elegibles AS ("+
            " SELECT l.tc_id,m.id AS tc_match_pk,m.kickoff_utc,"+
            " CASE WHEN LOWER(COALESCE(m.match_status,'')) IN ('full','ft','ended')"+
            " AND m.home_goals IS NOT NULL AND m.away_goals IS NOT NULL THEN 0 ELSE 1 END AS tc_no_final,"+
            " EXISTS(SELECT 1 FROM tc_download_job j WHERE j.match_id=m.id AND j.job_type='MATCH_BOOKMAKER_ODDS' AND j.status='DONE') AS bookmaker_done,"+
            " EXISTS(SELECT 1 FROM tc_download_job j WHERE j.match_id=m.id AND j.job_type='MATCH_BET365_HISTORY' AND j.status='DONE') AS bet365_done,"+
            " EXISTS(SELECT 1 FROM tc_download_job j JOIN tc_api_response r ON r.id=j.last_response_id"+
            " WHERE j.match_id=m.id AND j.job_type='MATCH_BOOKMAKER_ODDS' AND j.status='DONE' AND j.last_http_status=200"+
            " AND r.match_id=m.id AND r.http_status=200 AND r.payload_size_bytes>0 AND r.payload_gzip_size_bytes>0) AS bookmaker_json_ok,"+
            " EXISTS(SELECT 1 FROM tc_download_job j JOIN tc_api_response r ON r.id=j.last_response_id"+
            " WHERE j.match_id=m.id AND j.job_type='MATCH_BET365_HISTORY' AND j.status='DONE' AND j.last_http_status=200"+
            " AND r.match_id=m.id AND r.http_status=200 AND r.payload_size_bytes>0 AND r.payload_gzip_size_bytes>0) AS bet365_json_ok"+
            " FROM ligas l JOIN tc_match m ON m.league_id=l.tc_pk"+
            // Las mismas condiciones que HistoricalDownloadRunner.oddsState:
            // usar EXISTS evita duplicar el partido TC si hay varios enlaces.
            " WHERE EXISTS (SELECT 1 FROM tc_match_link link JOIN ls_match fm ON fm.id=link.ls_match_id"+
            " WHERE link.tc_match_pk=m.id AND link.review_status='VERIFIED' AND fm.pendingFinalResult=b'0')"+
            " AND NOT EXISTS (SELECT 1 FROM tc_match_link link JOIN ls_match fm ON fm.id=link.ls_match_id"+
            " WHERE link.tc_match_pk=m.id AND link.review_status='VERIFIED'"+
            " AND (fm.pendingFinalResult IS NULL OR fm.pendingFinalResult<>b'0'))"+
            " AND m.kickoff_utc IS NOT NULL AND m.kickoff_utc<UTC_TIMESTAMP(3)"+
            " AND m.kickoff_utc>=CAST(? AS DATETIME) AND m.kickoff_utc<CAST(? AS DATETIME)"+
            " AND NOT EXISTS (SELECT 1 FROM tc_match_link link JOIN tc_match_exception ex"+
            " ON ex.ls_match_id=link.ls_match_id WHERE link.tc_match_pk=m.id"+
            " AND link.review_status='VERIFIED' AND ex.is_active=1 AND ex.exception_code='NOT_FOUND_IN_TC')"+
            ")"+
            " SELECT l.tc_id,l.liga,COUNT(e.tc_match_pk) AS elegibles,"+
            " COALESCE(SUM(CASE WHEN e.bookmaker_done=1 AND e.bet365_done=1 THEN 1 ELSE 0 END),0) AS dos_done,"+
            " COALESCE(SUM(CASE WHEN e.tc_match_pk IS NOT NULL AND NOT(e.bookmaker_done=1 AND e.bet365_done=1) THEN 1 ELSE 0 END),0) AS pendientes,"+
            " COALESCE(SUM(CASE WHEN e.tc_match_pk IS NOT NULL THEN (1-e.bookmaker_done)+(1-e.bet365_done) ELSE 0 END),0) AS peticiones,"+
            " COALESCE(SUM(CASE WHEN e.bookmaker_json_ok=1 AND e.bet365_json_ok=1 THEN 1 ELSE 0 END),0) AS dos_json_ok,"+
            " CAST(MAX(CASE WHEN e.tc_match_pk IS NOT NULL AND NOT(e.bookmaker_done=1 AND e.bet365_done=1) THEN e.kickoff_utc ELSE NULL END) AS CHAR) AS ultimo_pendiente_utc,"+
            " COALESCE(SUM(CASE WHEN e.tc_match_pk IS NOT NULL AND e.tc_no_final=1 THEN 1 ELSE 0 END),0) AS tc_no_final"+
            " FROM ligas l LEFT JOIN elegibles e ON e.tc_id=l.tc_id GROUP BY l.tc_id,l.liga"+
            " ORDER BY FIELD(l.tc_id,'1','14','12','16','25','137','138','2')";
    }
}
