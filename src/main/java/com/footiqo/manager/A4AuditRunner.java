package com.footiqo.manager;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A4 de la Guia Operativa: lectura de partidos TC y temporadas Footiqo.
 * No hace llamadas a la API, no crea temporadas ni realiza INSERT/UPDATE/DELETE.
 * Ejecutar con Java 8 y con el driver MySQL del classpath de totalcorner-downloader.
 */
public final class A4AuditRunner {
    private static final String TC_LEAGUE =
            "SELECT id,name FROM freefootstats.tc_league WHERE tc_league_id=?";
    private static final String LS_LEAGUE =
            "SELECT name FROM freefootstats.ls_league WHERE id=?";
    private static final String MATCHES =
            "SELECT COUNT(*) AS partidos_tc, "
            + "COALESCE(SUM(CASE WHEN home_team_id IS NULL OR away_team_id IS NULL THEN 1 ELSE 0 END),0) AS sin_equipos, "
            + "COALESCE(SUM(CASE WHEN kickoff_utc IS NULL THEN 1 ELSE 0 END),0) AS sin_fecha, "
            + "DATE_FORMAT(MIN(kickoff_utc), '%Y-%m-%d %H:%i:%s') AS primero, "
            + "DATE_FORMAT(MAX(kickoff_utc), '%Y-%m-%d %H:%i:%s') AS ultimo "
            + "FROM freefootstats.tc_match WHERE league_id=?";
    private static final String SEASONS =
            "SELECT s.id AS season_id, t.name AS temporada, COUNT(m.id) AS partidos, "
            + "COALESCE(SUM(CASE WHEN m.pendingFinalResult = b'0' THEN 1 ELSE 0 END),0) AS finalizados "
            + "FROM freefootstats.ls_league_season s "
            + "JOIN freefootstats.ls_season t ON t.id=s.season_id "
            + "LEFT JOIN freefootstats.ls_match m ON m.lsLeague_season_id=s.id "
            + "WHERE s.league_id=? GROUP BY s.id,t.name ORDER BY t.name,s.id";

    private A4AuditRunner() { }

    private static long id(String arg, String prefix) {
        if (!arg.startsWith(prefix)) throw new IllegalArgumentException("Expected " + prefix);
        String value=arg.substring(prefix.length());
        if (!value.matches("[0-9]{1,15}")) throw new IllegalArgumentException("Invalid ID for " + prefix);
        long result=Long.parseLong(value);
        if (result<=0) throw new IllegalArgumentException("ID must be positive for " + prefix);
        return result;
    }

    public static void main(String[] args) {
        try {
            if(args.length==1 && "--self-test".equals(args[0])) {
                if(id("--tc-league-id=85","--tc-league-id=")!=85 ||
                   id("--ls-league-id=7","--ls-league-id=")!=7 ||
                   !MATCHES.contains("kickoff_utc IS NULL") ||
                   !SEASONS.contains("lsLeague_season_id") ||
                   !SEASONS.contains("pendingFinalResult = b'0'") ||
                   !auditEligible(1,1,1) || auditEligible(0,1,1) || auditEligible(1,0,1) ||
                   !"ADVERTENCIAS".equals(auditStatus(5371,12,5268,0,10)) ||
                   !"OK".equals(auditStatus(1,1,1,0,0)) ||
                   !"BLOQUEADO".equals(auditStatus(0,1,1,0,0)))
                    throw new AssertionError("A4 SQL structure");
                for(String query:Arrays.asList(TC_LEAGUE,LS_LEAGUE,MATCHES,SEASONS)) {
                    String lower=query.toLowerCase(java.util.Locale.ROOT);
                    if(!lower.startsWith("select ") || lower.contains(" insert ") || lower.contains(" update ") || lower.contains(" delete "))
                        throw new AssertionError("A4 must be read-only");
                }
                System.out.println("A4_SELF_TEST_OK | Java 8 | SQL=SOLO LECTURA | liga TC y LS parametrizadas");
                return;
            }
            if(args.length!=2)throw new IllegalArgumentException("Uso: --ls-league-id=N --tc-league-id=N");
            long ls=id(args[0],"--ls-league-id=");
            long tc=id(args[1],"--tc-league-id=");
            String pass=System.getenv("TOTALCORNER_DB_PASSWORD");
            if(pass==null || pass.isEmpty())throw new IllegalArgumentException("Falta TOTALCORNER_DB_PASSWORD");
            String url=env("TOTALCORNER_DB_URL","jdbc:mysql://localhost:3307/freefootstats?serverTimezone=UTC&useUnicode=true");
            String user=env("TOTALCORNER_DB_USER","root");
            DriverManager.setLoginTimeout(15);
            Class.forName("com.mysql.cj.jdbc.Driver");
            try(Connection db=DriverManager.getConnection(url,user,pass)) {
                db.setReadOnly(true);
                db.setAutoCommit(true);
                long internalTc=0;
                String tcName="";
                int tcRows=0;
                try(PreparedStatement p=db.prepareStatement(TC_LEAGUE)) {
                    p.setLong(1,tc);p.setQueryTimeout(60);
                    try(ResultSet rs=p.executeQuery()) {
                        while(rs.next()) {tcRows++;internalTc=rs.getLong(1);tcName=clean(rs.getString(2));}
                    }
                }
                if(tcRows!=1)throw new IllegalStateException("A4: liga TC="+tc+" no existe de forma unica en tc_league.");
                String lsName=null;
                try(PreparedStatement p=db.prepareStatement(LS_LEAGUE)) {
                    p.setLong(1,ls);p.setQueryTimeout(60);
                    try(ResultSet rs=p.executeQuery()) {if(rs.next())lsName=clean(rs.getString(1));}
                }
                if(lsName==null)throw new IllegalStateException("A4: liga LS="+ls+" no existe en ls_league.");
                System.out.println("=== A4 CONTROL DE PARTIDOS Y TEMPORADAS | SQL SOLO LECTURA | API=0 ===");
                System.out.println("A4_LIGAS\t"+tc+"\t"+tcName+"\t"+ls+"\t"+lsName);
                long total=0,missingTeams=0,missingDates=0;
                String first="",last="";
                try(PreparedStatement p=db.prepareStatement(MATCHES)) {
                    p.setLong(1,internalTc);p.setQueryTimeout(120);
                    try(ResultSet rs=p.executeQuery()) {
                        if(rs.next()) {
                            total=rs.getLong("partidos_tc");
                            missingTeams=rs.getLong("sin_equipos");
                            missingDates=rs.getLong("sin_fecha");
                            first=clean(rs.getString("primero"));last=clean(rs.getString("ultimo"));
                        }
                    }
                }
                System.out.println("A4_TC\t"+total+"\t"+missingTeams+"\t"+missingDates+"\t"+first+"\t"+last);
                int seasonCount=0;
                long lsMatches=0, finished=0;
                List<String> rows=new ArrayList<String>();
                try(PreparedStatement p=db.prepareStatement(SEASONS)) {
                    p.setLong(1,ls);p.setQueryTimeout(180);
                    try(ResultSet rs=p.executeQuery()) {
                        while(rs.next()) {
                            seasonCount++;
                            long matches=rs.getLong("partidos");
                            long done=rs.getLong("finalizados");
                            if(done>matches)throw new IllegalStateException("A4: finalizados superan partidos en temporada " + rs.getString("temporada"));
                            lsMatches+=matches;finished+=done;
                            rows.add("A4_TEMPORADA\t"+rs.getLong("season_id")+"\t"+clean(rs.getString("temporada"))
                                    +"\t"+matches+"\t"+done);
                        }
                    }
                }
                for(String row:rows)System.out.println(row);
                String status=auditStatus(total,seasonCount,lsMatches,missingTeams,missingDates);
                boolean ok=!"BLOQUEADO".equals(status);
                boolean warnings="ADVERTENCIAS".equals(status);
                System.out.println("A4_RESULT\t"+status+"\t"+total+"\t"+missingTeams+"\t"+missingDates
                        +"\t"+seasonCount+"\t"+lsMatches+"\t"+finished);
                System.out.println(!ok
                    ? "A4 BLOQUEADO: falta calendario TC o faltan partidos/temporadas en Footiqo."
                    : warnings
                    ? "A4 ADVERTENCIAS: hay registros TC incompletos. No bloquear A5. Comparar pendientes de ls_match al enlazar en A6."
                    : "A4 CONTROL OK: partidos TC y temporadas Footiqo disponibles.");
                System.out.println("A4 FIN | no se escribio en tc_league_season, tc_match_link ni ls_.");
            }
        }catch(Throwable e){
            String message=e instanceof SQLException
                    ? "Fallo SQL ("+((SQLException)e).getSQLState()+", codigo "+((SQLException)e).getErrorCode()+"): "+e.getMessage()
                    : e.getMessage();
            System.err.println("A4_ERROR: "+clean(message));
            System.exit(2);
        }
    }
    // El universo comercial es ls_match; incidencias internas TC no bloquean A4.
    static boolean auditEligible(long tcMatches,int lsSeasons,long lsMatches) {
        return tcMatches>0 && lsSeasons>0 && lsMatches>0;
    }
    static String auditStatus(long tcMatches,int lsSeasons,long lsMatches,long withoutTeams,long withoutDate) {
        if(!auditEligible(tcMatches,lsSeasons,lsMatches))return "BLOQUEADO";
        return withoutTeams>0 || withoutDate>0 ? "ADVERTENCIAS" : "OK";
    }
    private static String env(String key,String fallback){String v=System.getenv(key);return v==null||v.trim().isEmpty()?fallback:v;}
    private static String clean(String s){return s==null?"":s.replace('\t',' ').replace('\n',' ').replace('\r',' ');}
}
