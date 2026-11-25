package com.footiqo.manager;

import java.sql.*;
import java.util.*;

/** Auditoria global: denominador ls_match de una liga LS, union de todas las TC asociadas. */
public final class A8CoverageAuditRunner {
    private A8CoverageAuditRunner() {}
    private static String env(String n,String d){String s=System.getenv(n);return s==null||s.trim().isEmpty()?d:s;}
    private static String clean(String s){return s==null?"":s.replace('|','/').replace('\n',' ').replace('\r',' ').replace('\t',' ');}
    private static final class Match {
        long ls,pk,links,observations,books,markets;String season,date,fixture,status="",bm="-",b365="-";
    }
    private static final class Count {
        long total, linked, noLink, duplicate, withOdds, noOdds, done, partial, notChecked,empty,observations;
        void add(Match m){total++;if(m.links==0){noLink++;return;}if(m.links>1){duplicate++;return;}
            linked++;if(m.observations>0){withOdds++;observations+=m.observations;}else noOdds++;
            if("COMPLETO".equals(m.status))done++;if("PARCIAL".equals(m.status))partial++;
            if("NO_CONSULTADO".equals(m.status))notChecked++;if("RESPUESTA_SIN_CUOTAS".equals(m.status))empty++;
        }
    }
    private static long arg(String s,String prefix){if(!s.startsWith(prefix)||!s.substring(prefix.length()).matches("[1-9][0-9]{0,10}"))throw new IllegalArgumentException("Parametro incorrecto "+s);return Long.parseLong(s.substring(prefix.length()));}
    private static List<Long> tcList(String s){LinkedHashSet<Long> vals=new LinkedHashSet<Long>();for(String e:s.split(",")){if(!e.matches("[1-9][0-9]{0,10}"))throw new IllegalArgumentException("TC invalida "+e);vals.add(Long.parseLong(e));}if(vals.isEmpty())throw new IllegalArgumentException("No hay ligas TC asociadas");return new ArrayList<Long>(vals);}
    private static String placeholders(int n){StringJoiner s=new StringJoiner(",");for(int i=0;i<n;i++)s.add("?");return s.toString();}
    private static boolean indexed(Connection db,String table,String column)throws SQLException{
        String sql="SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND column_name=? AND seq_in_index=1 LIMIT 1";
        try(PreparedStatement p=db.prepareStatement(sql)){p.setString(1,table);p.setString(2,column);try(ResultSet r=p.executeQuery()){return r.next();}}
    }
    public static void main(String[] args){
        if(args.length==1&&"--self-test".equals(args[0])){if(tcList("2,85,2").size()!=2||!placeholders(3).equals("?,?,?"))throw new AssertionError();System.out.println("A8_SELF_TEST_OK");return;}
        try{
            if(args.length!=2)throw new IllegalArgumentException("--ls-league-id=N --tc-leagues=ID,ID");
            long ls=arg(args[0],"--ls-league-id=");if(!args[1].startsWith("--tc-leagues="))throw new IllegalArgumentException("Falta --tc-leagues");
            List<Long> tc=tcList(args[1].substring("--tc-leagues=".length()));
            String password=System.getenv("TOTALCORNER_DB_PASSWORD");if(password==null||password.isEmpty())throw new IllegalArgumentException("Falta TOTALCORNER_DB_PASSWORD");
            Class.forName("com.mysql.cj.jdbc.Driver");DriverManager.setLoginTimeout(12);
            try(Connection db=DriverManager.getConnection(env("TOTALCORNER_DB_URL","jdbc:mysql://localhost:3307/freefootstats?serverTimezone=UTC&useUnicode=true"),env("TOTALCORNER_DB_USER","root"),password)){
                db.setReadOnly(true);
                if(!indexed(db,"tc_match_link","ls_match_id")||!indexed(db,"tc_odds_observation","match_id")||!indexed(db,"tc_download_job","match_id"))
                    throw new SQLException("Faltan indices de acceso por partido en tc_match_link(ls_match_id), tc_odds_observation(match_id) o tc_download_job(match_id). Comprobar SHOW INDEX antes de continuar.");
                List<Long> tcInternal=new ArrayList<Long>();String lookup="SELECT id FROM tc_league WHERE tc_league_id IN ("+placeholders(tc.size())+")";
                try(PreparedStatement p=db.prepareStatement(lookup)){int i=1;for(Long v:tc)p.setLong(i++,v);p.setQueryTimeout(20);try(ResultSet r=p.executeQuery()){while(r.next())tcInternal.add(r.getLong(1));}}
                if(tcInternal.isEmpty())throw new SQLException("Ninguna liga TC existe en tc_league");
                System.out.println("A8_START|"+ls+"|"+tc.toString());
                String sql="SELECT lm.id AS ls_id, DATE_FORMAT(lm.matchDate,'%Y-%m-%d %H:%i:%s') AS ls_date, " +
                    "CONCAT(COALESCE(h.name,'?'),' - ',COALESCE(a.name,'?')) AS fixture,s.name AS season, " +
                    "COUNT(DISTINCT tm.id) AS links,MIN(tm.id) AS tc_pk " +
                    "FROM ls_match lm JOIN ls_league_season ss ON ss.id=lm.lsLeague_season_id " +
                    "JOIN ls_season s ON s.id=ss.season_id LEFT JOIN ls_team h ON h.id=lm.lsHomeTeam_id " +
                    "LEFT JOIN ls_team a ON a.id=lm.lsAwayTeam_id " +
                    "LEFT JOIN tc_match_link l ON l.ls_match_id=lm.id AND l.review_status='VERIFIED' " +
                    "LEFT JOIN tc_match tm ON tm.id=l.tc_match_pk AND tm.league_id IN ("+placeholders(tcInternal.size())+") " +
                    "WHERE ss.league_id=? GROUP BY lm.id,lm.matchDate,h.name,a.name,s.name ORDER BY s.name,lm.matchDate,lm.id";
                List<Match> matches=new ArrayList<Match>();
                try(PreparedStatement p=db.prepareStatement(sql)){int i=1;for(Long v:tcInternal)p.setLong(i++,v);p.setLong(i,ls);p.setQueryTimeout(35);
                    try(ResultSet r=p.executeQuery()){while(r.next()){Match m=new Match();m.ls=r.getLong("ls_id");m.date=r.getString("ls_date");m.fixture=r.getString("fixture");m.season=r.getString("season");m.links=r.getLong("links");m.pk=r.getLong("tc_pk");matches.add(m);}}}
                String odds="SELECT COUNT(*) AS n,COUNT(DISTINCT bookmaker_id) AS books,COUNT(DISTINCT market_id) AS markets FROM tc_odds_observation WHERE match_id=?";
                String jobs="SELECT j.job_type,j.status,r.http_status,(r.payload_gzip IS NOT NULL) AS has_payload FROM tc_download_job j LEFT JOIN tc_api_response r ON r.id=j.last_response_id AND r.match_id=j.match_id WHERE j.match_id=? AND j.job_type IN ('MATCH_BOOKMAKER_ODDS','MATCH_BET365_HISTORY') ORDER BY j.id DESC";
                Map<String,Count> seasons=new LinkedHashMap<String,Count>();Count total=new Count();
                try(PreparedStatement o=db.prepareStatement(odds);PreparedStatement j=db.prepareStatement(jobs)){
                    o.setQueryTimeout(20);j.setQueryTimeout(20);
                    int index=0;
                    for(Match m:matches){
                        if(m.links==0)m.status="SIN_ENLACE";
                        else if(m.links>1)m.status="ENLACE_MULTIPLE";
                        else{
                            o.setLong(1,m.pk);try(ResultSet r=o.executeQuery()){if(r.next()){m.observations=r.getLong(1);m.books=r.getLong(2);m.markets=r.getLong(3);}}
                            j.setLong(1,m.pk);boolean seenBm=false,seen365=false,doneBm=false,done365=false,checked=false,valid=false;
                            try(ResultSet r=j.executeQuery()){while(r.next()){
                                String type=r.getString(1);boolean good="DONE".equals(r.getString(2))&&r.getInt(3)==200&&r.getInt(4)==1;
                                checked=true;if(r.getInt(3)==200&&r.getInt(4)==1)valid=true;
                                if("MATCH_BOOKMAKER_ODDS".equals(type)&&!seenBm){seenBm=true;doneBm=good;}
                                if("MATCH_BET365_HISTORY".equals(type)&&!seen365){seen365=true;done365=good;}
                            }}
                            m.bm=doneBm?"DONE":"-";m.b365=done365?"DONE":"-";
                            m.status=doneBm&&done365?"COMPLETO":doneBm||done365?"PARCIAL":m.observations>0?"CUOTAS_SIN_JOBS_COMPLETOS":!checked?"NO_CONSULTADO":valid?"RESPUESTA_SIN_CUOTAS":"ERROR_O_PENDIENTE";
                        }
                        seasons.computeIfAbsent(m.season,k->new Count()).add(m);total.add(m);
                        System.out.println("A8_MATCH|"+m.ls+"|"+clean(m.season)+"|"+clean(m.date)+"|"+clean(m.fixture)+"|"+(m.pk==0?"":m.pk)+"|"+m.links+"|"+m.status+"|"+m.observations+"|"+m.books+"|"+m.markets+"|"+m.bm+"|"+m.b365);
                        if(++index%200==0)System.out.println("A8_PROGRESS|"+index+"|"+matches.size());
                    }
                }
                for(Map.Entry<String,Count> e:seasons.entrySet()){
                    Count c=e.getValue();System.out.println("A8_SEASON|"+clean(e.getKey())+"|"+c.total+"|"+c.linked+"|"+c.noLink+"|"+c.duplicate+"|"+c.withOdds+"|"+c.noOdds+"|"+c.done+"|"+c.partial);
                }
                // Bet365: un partido cuenta como cubierto si tiene al menos una seleccion numerica positiva.
                // 9 combinaciones de mercado/periodo constatadas en OddsJsonImporter.
                final String[] marketCodes={"1X2","GOALS_OU","ASIAN_HANDICAP","CORNERS_OU","BTTS","1X2","GOALS_OU","ASIAN_HANDICAP","CORNERS_OU"};
                final String[] periods={"FT","FT","FT","FT","FT","1H","1H","1H","1H"};
                Map<String,int[]> seasonMarketCounts=new LinkedHashMap<String,int[]>();
                for(String season:seasons.keySet())seasonMarketCounts.put(season,new int[9]);
                Map<Long,List<Match>> eligible=new LinkedHashMap<Long,List<Match>>();
                for(Match m:matches)if(m.links==1&&m.pk>0)
                    eligible.computeIfAbsent(m.pk,k->new ArrayList<Match>()).add(m);
                Long bookmakerId=null;
                try(PreparedStatement ps=db.prepareStatement("SELECT id FROM tc_bookmaker WHERE LOWER(slug)='bet365' LIMIT 1")){
                    ps.setQueryTimeout(15);
                    try(ResultSet rs=ps.executeQuery()){if(rs.next())bookmakerId=rs.getLong(1);}
                }
                if(bookmakerId!=null&&!eligible.isEmpty()){
                    // Resolver los IDs de mercado UNA sola vez. No se recorre toda la historia
                    // de cada partido: cada EXISTS se detiene al hallar una seleccion valida.
                    Map<String,List<Long>> marketIds=new HashMap<String,List<Long>>();
                    try(PreparedStatement ps=db.prepareStatement("SELECT id,market_code FROM tc_market")){
                        ps.setQueryTimeout(15);
                        try(ResultSet rs=ps.executeQuery()){
                            while(rs.next()){
                                String code=rs.getString(2);
                                marketIds.computeIfAbsent(code,k->new ArrayList<Long>()).add(rs.getLong(1));
                            }
                        }
                    }
                    // Nueve EXISTS indexables en una unica consulta por partido TC.
                    // El JOIN a selections conserva el requisito de odd_decimal > 0.
                    StringBuilder probeSql=new StringBuilder("SELECT ");
                    for(int k=0;k<9;k++){
                        if(k>0)probeSql.append(',');
                        List<Long> ids=marketIds.get(marketCodes[k]);
                        if(ids==null||ids.isEmpty()){
                            probeSql.append("0 AS c").append(k);
                        }else{
                            probeSql.append("EXISTS (SELECT 1 FROM tc_odds_observation o ")
                                .append("JOIN tc_odds_selection sel ON sel.observation_id=o.id AND sel.odd_decimal>0 ")
                                .append("WHERE o.match_id=? AND o.bookmaker_id=? AND o.market_id IN (")
                                .append(placeholders(ids.size())).append(") AND o.period_code=?) AS c").append(k);
                        }
                    }
                    try(PreparedStatement probe=db.prepareStatement(probeSql.toString())){
                        probe.setQueryTimeout(25);
                        int processed=0;
                        long startMs=System.nanoTime();
                        for(Map.Entry<Long,List<Match>> entry:eligible.entrySet()){
                            int param=1;
                            for(int k=0;k<9;k++){
                                List<Long> ids=marketIds.get(marketCodes[k]);
                                if(ids==null||ids.isEmpty())continue;
                                probe.setLong(param++,entry.getKey());
                                probe.setLong(param++,bookmakerId);
                                for(Long id:ids)probe.setLong(param++,id);
                                probe.setString(param++,periods[k]);
                            }
                            try(ResultSet rs=probe.executeQuery()){
                                if(rs.next()){
                                    // Un mismo partido TC podria estar enlazado a mas de un LS.
                                    // Contar una vez cada LS elegible, sin perder enlaces.
                                    for(Match linked:entry.getValue()){
                                        int[] counts=seasonMarketCounts.get(linked.season);
                                        if(counts!=null)for(int k=0;k<9;k++)if(rs.getInt(k+1)==1)counts[k]++;
                                    }
                                }
                            }catch(SQLException ex){
                                throw new SQLException("Bet365 fallo al comprobar TC PK="+entry.getKey()+
                                    ", LS ID="+entry.getValue().get(0).ls+": "+ex.getMessage(),ex);
                            }
                            if(++processed%25==0||processed==eligible.size()){
                                long elapsedMs=(System.nanoTime()-startMs)/1000000L;
                                System.out.println("A8_B365_PROGRESS|"+processed+"|"+eligible.size());
                                System.out.println("A8_B365_TIMING|"+processed+"|"+elapsedMs);
                            }
                        }
                    }
                }
                for(Map.Entry<String,Count> entry:seasons.entrySet()){
                    String season=entry.getKey();long denominator=entry.getValue().total;
                    int[] counts=seasonMarketCounts.get(season);StringBuilder row=new StringBuilder("A8_B365|").append(clean(season)).append('|').append(denominator);
                    for(int k=0;k<9;k++)row.append('|').append(denominator==0?"0.0%":String.format(Locale.ROOT,"%.1f%%",100.0*counts[k]/denominator));
                    System.out.println(row.toString());
                }
                System.out.println("A8_TOTAL|"+total.total+"|"+total.linked+"|"+total.noLink+"|"+total.duplicate+"|"+total.withOdds+"|"+total.noOdds+"|"+total.done+"|"+total.partial+"|"+total.notChecked+"|"+total.empty+"|"+total.observations);
                System.out.println("A8_REPORT_OK|SOLO_LECTURA|API_0");
            }
        }catch(Exception ex){System.err.println("A8_ERROR: "+ex.getClass().getSimpleName()+": "+ex.getMessage());System.exit(1);}
    }
}
