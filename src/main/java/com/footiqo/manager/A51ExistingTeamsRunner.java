package com.footiqo.manager;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * A5.1: comprobar mappings VERIFIED existentes sin insertar ni sugerir nuevos enlaces.
 * Uso: --ls-league-id=N --tc-league-id=N
 * Muestra los equipos presentes en los partidos TC de la liga y si su mapping
 * VERIFIED pertenece a un equipo utilizado por los partidos LS de esa liga.
 */
public final class A51ExistingTeamsRunner {
    private static final String SQL =
            "WITH tc_teams AS ( " +
            " SELECT m.home_team_id AS tc_team_pk FROM freefootstats.tc_match m " +
            " JOIN freefootstats.tc_league l ON l.id=m.league_id " +
            " WHERE l.tc_league_id=? AND m.home_team_id IS NOT NULL " +
            " UNION " +
            " SELECT m.away_team_id AS tc_team_pk FROM freefootstats.tc_match m " +
            " JOIN freefootstats.tc_league l ON l.id=m.league_id " +
            " WHERE l.tc_league_id=? AND m.away_team_id IS NOT NULL " +
            "), ls_teams AS ( " +
            " SELECT lm.lsHomeTeam_id AS ls_team_id FROM freefootstats.ls_match lm " +
            " JOIN freefootstats.ls_league_season s ON s.id=lm.lsLeague_season_id " +
            " WHERE s.league_id=? AND lm.lsHomeTeam_id IS NOT NULL " +
            " UNION " +
            " SELECT lm.lsAwayTeam_id AS ls_team_id FROM freefootstats.ls_match lm " +
            " JOIN freefootstats.ls_league_season s ON s.id=lm.lsLeague_season_id " +
            " WHERE s.league_id=? AND lm.lsAwayTeam_id IS NOT NULL " +
            ") " +
            "SELECT t.id AS tc_team_pk,t.tc_team_id AS tc_team_id_api,t.name AS tc_team_name, " +
            "CASE WHEN COUNT(tl.id)>0 THEN 'VERIFIED' ELSE 'SIN_LINK_VERIFICADO' END AS estado, " +
            "COALESCE(GROUP_CONCAT(DISTINCT CONCAT(ft.id,' - ',ft.name) " +
            "ORDER BY ft.name SEPARATOR ' | '),'') AS teams_footiqo " +
            "FROM tc_teams e JOIN freefootstats.tc_team t ON t.id=e.tc_team_pk " +
            "LEFT JOIN freefootstats.tc_team_link tl ON tl.tc_team_pk=t.id " +
            " AND tl.review_status='VERIFIED' " +
            " AND tl.ls_team_id IN (SELECT ls_team_id FROM ls_teams) " +
            "LEFT JOIN freefootstats.ls_team ft ON ft.id=tl.ls_team_id " +
            "GROUP BY t.id,t.tc_team_id,t.name ORDER BY estado DESC,t.name,t.id";

    private A51ExistingTeamsRunner() {}

    private static long parse(String arg,String prefix) {
        if(!arg.startsWith(prefix) || !arg.substring(prefix.length()).matches("[0-9]{1,15}"))
            throw new IllegalArgumentException("Parametro invalido: "+arg);
        long n=Long.parseLong(arg.substring(prefix.length()));
        if(n<=0)throw new IllegalArgumentException("ID debe ser positivo");
        return n;
    }

    public static void main(String[] args) {
        try {
            if(args.length==1 && "--self-test".equals(args[0])) {
                if(parse("--tc-league-id=85","--tc-league-id=")!=85 ||
                   parse("--ls-league-id=7","--ls-league-id=")!=7 ||
                   !SQL.startsWith("WITH tc_teams") ||
                   !SQL.contains("tl.review_status='VERIFIED'") ||
                   !SQL.contains("s.league_id=?") ||
                   !SQL.contains("tc_team_link") ||
                   SQL.toLowerCase(Locale.ROOT).contains(" insert "))
                    throw new AssertionError("A5.1 SQL");
                System.out.println("A51_SELF_TEST_OK | Java 8 | SQL=SOLO LECTURA");
                return;
            }
            if(args.length!=2)throw new IllegalArgumentException("Uso: --ls-league-id=N --tc-league-id=N");
            long ls=parse(args[0],"--ls-league-id=");
            long tc=parse(args[1],"--tc-league-id=");
            String pass=System.getenv("TOTALCORNER_DB_PASSWORD");
            if(pass==null || pass.isEmpty())throw new IllegalArgumentException("Falta TOTALCORNER_DB_PASSWORD");
            String url=env("TOTALCORNER_DB_URL","jdbc:mysql://localhost:3307/freefootstats?serverTimezone=UTC&useUnicode=true");
            String user=env("TOTALCORNER_DB_USER","root");
            DriverManager.setLoginTimeout(15);
            Class.forName("com.mysql.cj.jdbc.Driver");
            try(Connection db=DriverManager.getConnection(url,user,pass)) {
                db.setReadOnly(true);
                db.setAutoCommit(true);
                int total=0,verified=0,unlinked=0;
                System.out.println("=== A5.1 COMPROBAR EQUIPOS EXISTENTES | SQL SOLO LECTURA | API=0 ===");
                try(PreparedStatement p=db.prepareStatement(SQL)) {
                    p.setString(1,Long.toString(tc));p.setString(2,Long.toString(tc));
                    p.setLong(3,ls);p.setLong(4,ls);
                    p.setQueryTimeout(180);
                    try(ResultSet r=p.executeQuery()) {
                        while(r.next()) {
                            String status=r.getString("estado");
                            total++;
                            if("VERIFIED".equals(status))verified++; else unlinked++;
                            System.out.println("A51_TEAM\t"+r.getLong("tc_team_pk")+"\t"+
                                clean(r.getString("tc_team_id_api"))+"\t"+
                                clean(r.getString("tc_team_name"))+"\t"+status+"\t"+
                                clean(r.getString("teams_footiqo")));
                        }
                    }
                }
                System.out.println("A51_RESULT\t"+total+"\t"+verified+"\t"+unlinked);
                System.out.println("A51 FIN | Diagnostico solamente. SIN_LINK_VERIFICADO NO autoriza insercion automatica.");
            }
        } catch(Throwable e) {
            String message=e instanceof SQLException
                ? "Fallo SQL ("+((SQLException)e).getSQLState()+", codigo "+((SQLException)e).getErrorCode()+"): "+e.getMessage()
                : e.getMessage();
            System.err.println("A51_ERROR: "+clean(message));
            System.exit(2);
        }
    }

    private static String env(String k,String fallback){String s=System.getenv(k);return s==null||s.trim().isEmpty()?fallback:s;}
    private static String clean(String s){return s==null?"":s.replace('\t',' ').replace('\r',' ').replace('\n',' ');}
}
