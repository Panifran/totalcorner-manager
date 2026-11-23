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
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/** Diagnostico B3 por partido: solo lectura, API 0, CSV Excel con comas. Java 8. */
public final class B3PendingDiagnosticRunner {
    private B3PendingDiagnosticRunner() {}
    private static String env(String key, String defaultValue) {
        String s=System.getenv(key);return s==null||s.trim().isEmpty()?defaultValue:s;
    }
    private static String clean(String s) {
        return s==null?"":s.replace('\t',' ').replace('\n',' ').replace('\r',' ');
    }
    private static void csv(BufferedWriter out,String... cells) throws Exception {
        for(int n=0;n<cells.length;n++) {
            if(n>0) out.write(',');
            out.write('"'); out.write((cells[n]==null?"":cells[n]).replace("\"","\"\"")); out.write('"');
        }
        out.write("\r\n");
    }
    private static String status(String s) { return s==null?"SIN_JOB":s; }
    private static boolean isDone(String s) { return "DONE".equals(s); }
    private static String reason(boolean hasUnfinishedOtherLs,boolean hasException,String bookmaker,String bet365,
                                 boolean bookmakerReady,boolean bet365Ready) {
        if(hasUnfinishedOtherLs) return "BLOQUEO_ENLACE_TC_OTRO_LS_NO_FINAL";
        if(hasException) return "BLOQUEO_EXCEPCION_NOT_FOUND_IN_TC";
        if(!bookmakerReady&&!isDone(bookmaker)||!bet365Ready&&!isDone(bet365)) return "JOBS_NO_LISTOS_PARA_REINTENTO";
        return "ELEGIBLE_PARA_B3";
    }
    public static void main(String[] args) {
        try {
            Map<String,String> options=new LinkedHashMap<String,String>();
            for(String arg:args) {
                int p=arg.indexOf('=');if(!arg.startsWith("--")||p<3) throw new IllegalArgumentException("Parametro incorrecto: "+arg);
                options.put(arg.substring(2,p),arg.substring(p+1));
            }
            String ids=options.get("league-ids");
            if(ids==null||!ids.matches("[0-9]+(,[0-9]+)*")) throw new IllegalArgumentException("--league-ids invalido");
            int oldest=Integer.parseInt(options.get("oldest-year")),newest=Integer.parseInt(options.get("newest-year"));
            if(oldest<2015||newest<oldest||newest>2100) throw new IllegalArgumentException("Temporadas incorrectas");
            String password=System.getenv("TOTALCORNER_DB_PASSWORD");
            if(password==null||password.isEmpty()) throw new IllegalArgumentException("Falta TOTALCORNER_DB_PASSWORD");
            String[] leagueIds=ids.split(","); StringBuilder placeholders=new StringBuilder();
            for(int i=0;i<leagueIds.length;i++) {if(i>0)placeholders.append(',');placeholders.append('?');}
            // Deliberadamente se parte de los mismos partidos elegibles de B3ControlRunner.
            String sql="SELECT ml.ls_match_id,m.id,m.tc_match_id,l.tc_league_id,m.kickoff_utc,"+
                "m.home_team_name,m.away_team_name,COALESCE(m.match_status,''),"+
                "(SELECT j.status FROM tc_download_job j WHERE j.match_id=m.id AND j.job_type='MATCH_BOOKMAKER_ODDS' LIMIT 1),"+
                "(SELECT j.status FROM tc_download_job j WHERE j.match_id=m.id AND j.job_type='MATCH_BET365_HISTORY' LIMIT 1),"+
                "EXISTS(SELECT 1 FROM tc_match_link lk JOIN ls_match f2 ON f2.id=lk.ls_match_id "+
                "WHERE lk.tc_match_pk=m.id AND lk.review_status='VERIFIED' AND (f2.pendingFinalResult IS NULL OR f2.pendingFinalResult<>b'0')) AS ls_conflicto,"+
                "EXISTS(SELECT 1 FROM tc_match_link lk JOIN tc_match_exception ex ON ex.ls_match_id=lk.ls_match_id "+
                "WHERE lk.tc_match_pk=m.id AND lk.review_status='VERIFIED' AND ex.is_active=1 AND ex.exception_code='NOT_FOUND_IN_TC') AS excepcion,"+
                "(NOT EXISTS(SELECT 1 FROM tc_download_job j WHERE j.match_id=m.id AND j.job_type='MATCH_BOOKMAKER_ODDS') OR "+
                " EXISTS(SELECT 1 FROM tc_download_job j WHERE j.match_id=m.id AND j.job_type='MATCH_BOOKMAKER_ODDS' AND j.attempt_count<j.max_attempts "+
                " AND ((j.status IN ('PENDING','RETRY') AND j.next_run_at<=UTC_TIMESTAMP(3)) OR (j.status='RUNNING' AND j.leased_until<UTC_TIMESTAMP(3))))) AS book_ready,"+
                "(NOT EXISTS(SELECT 1 FROM tc_download_job j WHERE j.match_id=m.id AND j.job_type='MATCH_BET365_HISTORY') OR "+
                " EXISTS(SELECT 1 FROM tc_download_job j WHERE j.match_id=m.id AND j.job_type='MATCH_BET365_HISTORY' AND j.attempt_count<j.max_attempts "+
                " AND ((j.status IN ('PENDING','RETRY') AND j.next_run_at<=UTC_TIMESTAMP(3)) OR (j.status='RUNNING' AND j.leased_until<UTC_TIMESTAMP(3))))) AS bet_ready "+
                "FROM tc_match m JOIN tc_league l ON l.id=m.league_id "+
                "JOIN tc_match_link ml ON ml.tc_match_pk=m.id AND ml.review_status='VERIFIED' "+
                "JOIN ls_match fm ON fm.id=ml.ls_match_id AND fm.pendingFinalResult=b'0' "+
                "WHERE l.enabled=1 AND l.tc_league_id IN ("+placeholders+") "+
                "AND m.kickoff_utc IS NOT NULL AND m.kickoff_utc<UTC_TIMESTAMP(3) "+
                "AND m.kickoff_utc>=CAST(? AS DATETIME) AND m.kickoff_utc<CAST(? AS DATETIME) "+
                "AND NOT EXISTS(SELECT 1 FROM tc_match_exception ex WHERE ex.ls_match_id=ml.ls_match_id "+
                "AND ex.is_active=1 AND ex.exception_code='NOT_FOUND_IN_TC') "+
                "AND (NOT EXISTS(SELECT 1 FROM tc_download_job j WHERE j.match_id=m.id AND j.job_type='MATCH_BOOKMAKER_ODDS' AND j.status='DONE') "+
                " OR NOT EXISTS(SELECT 1 FROM tc_download_job j WHERE j.match_id=m.id AND j.job_type='MATCH_BET365_HISTORY' AND j.status='DONE')) "+
                "ORDER BY m.kickoff_utc DESC,m.id DESC LIMIT 5000";
            String url=env("TOTALCORNER_DB_URL","jdbc:mysql://localhost:3307/freefootstats?serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8&connectTimeout=10000&socketTimeout=30000");
            Class.forName("com.mysql.cj.jdbc.Driver");DriverManager.setLoginTimeout(12);
            Path dir=Paths.get("reports","totalcorner");Files.createDirectories(dir);
            Path report=dir.resolve("b3_diagnostico_"+new SimpleDateFormat("yyyyMMdd-HHmmssSSS").format(new Date())+".csv");
            int total=0,ready=0,blocked=0;
            System.out.println("B3_DIAG_INICIO\tligas="+ids+"\tSQL_SOLO_LECTURA\tAPI=0");
            try(Connection db=DriverManager.getConnection(url,env("TOTALCORNER_DB_USER","root"),password);
                BufferedWriter writer=Files.newBufferedWriter(report,StandardCharsets.UTF_8)) {
                if(!"freefootstats".equalsIgnoreCase(db.getCatalog()))throw new IllegalStateException("Base de datos inesperada");
                db.setReadOnly(true);
                // BOM para que Excel reconozca UTF-8 al abrir por doble clic.
                writer.write('\ufeff');
                csv(writer,"ls_match_id","tc_match_pk","tc_match_id","tc_league_id","kickoff_utc","partido_tc",
                    "tc_estado","job_bookmaker","job_bet365","otro_enlace_ls_no_final","excepcion_tc","book_ready","bet_ready","resultado_b3");
                try(PreparedStatement ps=db.prepareStatement(sql)){
                    ps.setQueryTimeout(180);int p=1;
                    for(String leagueId:leagueIds)ps.setInt(p++,Integer.parseInt(leagueId));
                    ps.setString(p++,oldest+"-07-01 00:00:00");ps.setString(p++,(newest+1)+"-07-01 00:00:00");
                    try(ResultSet rs=ps.executeQuery()) {
                        while(rs.next()) {
                            String ls=rs.getString(1),tc=rs.getString(2),tcId=rs.getString(3),league=rs.getString(4);
                            String date=rs.getString(5),teams=clean(rs.getString(6))+" - "+clean(rs.getString(7)),tcStatus=rs.getString(8);
                            String book=status(rs.getString(9)),bet=status(rs.getString(10));
                            boolean conflict=rs.getBoolean(11),exception=rs.getBoolean(12),bookReady=rs.getBoolean(13),betReady=rs.getBoolean(14);
                            String result=reason(conflict,exception,book,bet,bookReady,betReady);
                            if("ELEGIBLE_PARA_B3".equals(result))ready++;else blocked++;
                            csv(writer,ls,tc,tcId,league,date,teams,tcStatus,book,bet,
                                String.valueOf(conflict),String.valueOf(exception),String.valueOf(bookReady),String.valueOf(betReady),result);
                            System.out.println("B3_DIAG_ROW\t"+ls+"\t"+tc+"\t"+date+"\t"+result+"\t"+book+"/"+bet);
                            total++;
                        }
                    }
                }
            }
            System.out.println("B3_DIAG_RESULT\t"+total+"\t"+ready+"\t"+blocked);
            System.out.println("B3_DIAG_CSV\t"+report.toAbsolutePath());
        } catch(Exception e){System.err.println("B3_DIAG_ERROR\t"+clean(e.getMessage()));System.exit(1);}
    }
}
