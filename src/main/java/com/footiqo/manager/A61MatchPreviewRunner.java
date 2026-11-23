package com.footiqo.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** A6.1. Footiqo es referencia. Solo lectura; salida CSV de los partidos Footiqo sin enlace VERIFIED. */
public final class A61MatchPreviewRunner {
    private A61MatchPreviewRunner() {}
    private static String param(String raw,String key){if(!raw.startsWith(key)||raw.length()<=key.length())throw new IllegalArgumentException("Falta "+key);return raw.substring(key.length());}
    private static long positive(String v){if(!v.matches("[0-9]{1,15}")||Long.parseLong(v)<1)throw new IllegalArgumentException("ID invalido: "+v);return Long.parseLong(v);}
    private static String env(String k,String fallback){String s=System.getenv(k);return s==null||s.trim().isEmpty()?fallback:s;}
    private static String clean(String s){return s==null?"":s.replace('\n',' ').replace('\r',' ').replace('\t',' ');}
    private static String str(ResultSet r,String c)throws SQLException{String s=r.getString(c);return s==null?"":s;}
    private static LocalDateTime date(String v){return v==null?null:LocalDateTime.parse(v.replace(' ','T'));}
    private static String fmt(LocalDateTime v){return v==null?"":v.toString().replace('T',' ');}
    private static String csv(String s){return "\""+(s==null?"":s.replace("\"","\"\""))+"\"";}
    private static void line(Writer w,String... cells)throws IOException{for(int i=0;i<cells.length;i++){if(i>0)w.write(',');w.write(csv(cells[i]));}w.write("\r\n");}
    private static final class Ls {
        long id,home,away;int hg,ag;boolean hasScore;String hname,aname;LocalDateTime date;
    }
    private static final class Tc {
        long id,api,home,away;int hg,ag;boolean hasScore;String hname,aname,zone;LocalDateTime date;
    }
    private static final class Link {long tc,ls;String status;Link(long t,long l,String s){tc=t;ls=l;status=s;}}
    private static final class Pair {Ls ls;Tc tc;long seconds;String marker,state;Pair(Ls l,Tc t,long seconds){ls=l;tc=t;this.seconds=seconds;}}
    private static String teamKey(long h,long a){return h+"|"+a;}

    private static List<Ls> loadLs(Connection db,long league,String from,String to)throws SQLException{
        String q="SELECT m.id,m.lsHomeTeam_id,m.lsAwayTeam_id,m.ftHomeTeamGoals,m.ftAwayTeamGoals,"
            +"DATE_FORMAT(m.matchDate,'%Y-%m-%d %H:%i:%s') AS kickoff,"
            +"COALESCE(h.name,'') AS home_name,COALESCE(a.name,'') AS away_name "
            +"FROM ls_match m JOIN ls_league_season s ON s.id=m.lsLeague_season_id "
            +"LEFT JOIN ls_team h ON h.id=m.lsHomeTeam_id LEFT JOIN ls_team a ON a.id=m.lsAwayTeam_id "
            +"WHERE s.league_id=? AND m.matchDate >= ? AND m.matchDate < ? "
            +"AND m.pendingFinalResult=b'0' ORDER BY m.matchDate,m.id";
        List<Ls> out=new ArrayList<Ls>();try(PreparedStatement p=db.prepareStatement(q)){
            p.setLong(1,league);p.setString(2,from);p.setString(3,to);p.setQueryTimeout(240);
            try(ResultSet r=p.executeQuery()){while(r.next()){
                Ls x=new Ls();x.id=r.getLong("id");x.home=r.getLong("lsHomeTeam_id");x.away=r.getLong("lsAwayTeam_id");
                x.date=date(r.getString("kickoff")); x.hname=str(r,"home_name");x.aname=str(r,"away_name");
                Object h=r.getObject("ftHomeTeamGoals"),a=r.getObject("ftAwayTeamGoals");x.hasScore=h!=null&&a!=null;
                if(x.hasScore){x.hg=((Number)h).intValue();x.ag=((Number)a).intValue();}out.add(x);
            }}
        }return out;
    }
    private static List<Tc> loadTc(Connection db,String tc,String from,String to,int minutes)throws SQLException{
        String q="SELECT m.id,m.tc_match_id,m.home_team_id,m.away_team_id,m.home_goals,m.away_goals,"
            +"COALESCE(m.home_team_name,'') AS home_name,COALESCE(m.away_team_name,'') AS away_name,"
            +"m.kickoff_timezone_status,DATE_FORMAT(CONVERT_TZ(m.kickoff_utc,'+00:00','Europe/Madrid'),'%Y-%m-%d %H:%i:%s') AS kickoff "
            +"FROM tc_match m JOIN tc_league l ON l.id=m.league_id "
            +"WHERE l.tc_league_id=? AND m.kickoff_utc >= CONVERT_TZ(?,'Europe/Madrid','+00:00') - INTERVAL ? MINUTE "
            +"AND m.kickoff_utc < CONVERT_TZ(?,'Europe/Madrid','+00:00') + INTERVAL ? MINUTE ORDER BY m.id";
        List<Tc> out=new ArrayList<Tc>();try(PreparedStatement p=db.prepareStatement(q)){
            p.setString(1,tc);p.setString(2,from);p.setInt(3,minutes+1440);p.setString(4,to);p.setInt(5,minutes+1440);p.setQueryTimeout(240);
            try(ResultSet r=p.executeQuery()){while(r.next()){
                Tc x=new Tc();x.id=r.getLong("id");x.api=r.getLong("tc_match_id");
                x.home=r.getLong("home_team_id");x.away=r.getLong("away_team_id");
                x.hname=str(r,"home_name");x.aname=str(r,"away_name");x.zone=str(r,"kickoff_timezone_status");x.date=date(r.getString("kickoff"));
                Object h=r.getObject("home_goals"),a=r.getObject("away_goals");x.hasScore=h!=null&&a!=null;
                if(x.hasScore){x.hg=((Number)h).intValue();x.ag=((Number)a).intValue();}out.add(x);
            }}
        }return out;
    }
    // A6.1 se ejecuta para UNA liga. No deben bloquearla enlaces incompatibles de
    // equipos que solo aparecen en otras ligas (p. ej., TC team PK=1 no pertenece a MLS).
    private static final String VERIFIED_TEAMS_SQL =
        "SELECT lk.tc_team_pk,lk.ls_team_id FROM tc_team_link lk "
        +"JOIN (SELECT m.home_team_id AS tc_team_pk FROM tc_match m "
        +"JOIN tc_league l ON l.id=m.league_id WHERE l.tc_league_id=? "
        +"UNION SELECT m.away_team_id AS tc_team_pk FROM tc_match m "
        +"JOIN tc_league l ON l.id=m.league_id WHERE l.tc_league_id=?) scoped "
        +"ON scoped.tc_team_pk=lk.tc_team_pk WHERE lk.review_status='VERIFIED'";
    private static Map<Long,Long> verifiedTeams(Connection db,String tcLeague)throws SQLException{
        Map<Long,Long> out=new HashMap<Long,Long>();
        try(PreparedStatement p=db.prepareStatement(VERIFIED_TEAMS_SQL)){
            p.setString(1,tcLeague);
            p.setString(2,tcLeague);
            try(ResultSet r=p.executeQuery()){
                while(r.next()){
                    long tcTeam=r.getLong(1),lsTeam=r.getLong(2);
                    Long previous=out.putIfAbsent(tcTeam,lsTeam);
                    if(previous!=null&&previous.longValue()!=lsTeam)
                        throw new SQLException("Mapping TC contradictorio DENTRO de liga TC="
                            +tcLeague+" para tc_team_pk="+tcTeam+" (LS="+previous+" y LS="+lsTeam+")");
                }
            }
        }
        System.out.println("A61_TEAM_MAPPINGS\t"+out.size()+"\tTC_LIGA="+tcLeague);
        return out;
    }
    private static void loadLinksQuery(Connection db,String sql,Object value,Map<Long,List<Link>> byTc,Map<Long,List<Link>> byLs)throws SQLException{
        try(PreparedStatement p=db.prepareStatement(sql)){
            if(value instanceof Long)p.setLong(1,((Long)value).longValue());else p.setString(1,(String)value);
            try(ResultSet r=p.executeQuery()){while(r.next()){
                long tc=r.getLong(1),ls=r.getLong(2);String status=str(r,"review_status");
                // Dos consultas se solapan: deduplicar enlaces identicos.
                List<Link> known=byTc.get(tc);boolean present=false;
                if(known!=null)for(Link a:known)if(a.ls==ls&&a.status.equals(status)){present=true;break;}
                if(!present){Link link=new Link(tc,ls,status);byTc.computeIfAbsent(tc,k->new ArrayList<Link>()).add(link);
                    byLs.computeIfAbsent(ls,k->new ArrayList<Link>()).add(link);}
            }}
        }
    }
    private static boolean verified(List<Link> links){if(links!=null)for(Link l:links)if("VERIFIED".equals(l.status))return true;return false;}
    private static String classification(Pair p,int cLs,int cTc,Map<Long,List<Link>> byTc,Map<Long,List<Link>> byLs){
        List<Link> tl=byTc.get(p.tc.id),ll=byLs.get(p.ls.id);
        // Cada par debe estar libre de referencias existentes, aunque no sean VERIFIED.
        if(tl!=null||ll!=null){
            if(tl!=null&&ll!=null&&tl.size()==1&&ll.size()==1){Link a=tl.get(0),b=ll.get(0);
                if(a.tc==p.tc.id&&a.ls==p.ls.id&&b.tc==a.tc&&b.ls==a.ls&&"VERIFIED".equals(a.status)&&"VERIFIED".equals(b.status))return "YA_VERIFICADO";
            }
            return "REVISAR_ENLACE_EXISTENTE";
        }
        if(cLs!=1||cTc!=1)return "REVISAR_AMBIGUO";
        if(!"PROFILE_VERIFIED".equals(p.tc.zone))return "REVISAR_ZONA_HORARIA";
        if(!"MARCADOR_OK".equals(p.marker))return "REVISAR_MARCADOR";
        return "LISTO_PARA_INSERTAR";
    }
    private static int[] analyze(List<Ls> ls,List<Tc> tc,Map<Long,Long> teams,Map<Long,List<Link>> byTc,Map<Long,List<Link>> byLs,int tolerance,Writer w)throws IOException{
        Map<String,List<Tc>> tcByTeams=new HashMap<String,List<Tc>>();
        for(Tc t:tc){Long home=teams.get(t.home),away=teams.get(t.away);
            if(home!=null&&away!=null&&t.date!=null)tcByTeams.computeIfAbsent(teamKey(home,away),k->new ArrayList<Tc>()).add(t);
        }
        Map<Long,List<Pair>> byLsPair=new HashMap<Long,List<Pair>>();Map<Long,Integer> countTc=new HashMap<Long,Integer>();
        for(Ls l:ls){if(verified(byLs.get(l.id)))continue;
            List<Tc> options=tcByTeams.getOrDefault(teamKey(l.home,l.away),Collections.<Tc>emptyList());
            for(Tc t:options){if(l.date==null)continue;long diff=Duration.between(l.date,t.date).getSeconds();
                if(Math.abs(diff)>tolerance*60L)continue;
                Pair pair=new Pair(l,t,diff);
                pair.marker=!l.hasScore?"SIN_MARCADOR_LS":!t.hasScore?"SIN_MARCADOR_TC":
                    (l.hg==t.hg&&l.ag==t.ag)?"MARCADOR_OK":"MARCADOR_DISTINTO";
                byLsPair.computeIfAbsent(l.id,k->new ArrayList<Pair>()).add(pair);
                countTc.put(t.id,countTc.getOrDefault(t.id,0)+1);
            }
        }
        // finalizados, YA_VERIFICADOS, seguros, revisar, sin candidato, filas CSV, TC distintos
        int[] counts=new int[6];counts[0]=ls.size();counts[5]=tc.size();
        line(w,"evaluacion","ls_match_id","fecha_ls_madrid","local_ls","visitante_ls","marcador_ls", 
             "tc_match_pk","tc_partido_api","fecha_tc_madrid","local_tc","visitante_tc","marcador_tc",
             "diferencia_segundos","candidatos_tc_para_ls","candidatos_ls_para_tc","marcador_evaluacion","zona_tc");
        Map<String,Integer> statusCount=new TreeMap<String,Integer>();
        for(Ls l:ls){if(verified(byLs.get(l.id))){counts[1]++;continue;}
            List<Pair> pairs=byLsPair.getOrDefault(l.id,Collections.<Pair>emptyList());
            if(pairs.isEmpty()){
                counts[4]++;statusCount.put("SIN_CANDIDATO_VERIFICADO",statusCount.getOrDefault("SIN_CANDIDATO_VERIFICADO",0)+1);
                line(w,"SIN_CANDIDATO_VERIFICADO",Long.toString(l.id),fmt(l.date),l.hname,l.aname,score(l.hasScore,l.hg,l.ag),
                    "","","","","","","","0","0","","");continue;
            }
            for(Pair p:pairs){p.state=classification(p,pairs.size(),countTc.get(p.tc.id),byTc,byLs);
                String st=p.state;statusCount.put(st,statusCount.getOrDefault(st,0)+1);
                line(w,st,Long.toString(l.id),fmt(l.date),l.hname,l.aname,score(l.hasScore,l.hg,l.ag),
                    Long.toString(p.tc.id),Long.toString(p.tc.api),fmt(p.tc.date),p.tc.hname,p.tc.aname,
                    score(p.tc.hasScore,p.tc.hg,p.tc.ag),Long.toString(p.seconds),Integer.toString(pairs.size()),
                    Integer.toString(countTc.get(p.tc.id)),p.marker,p.tc.zone);
            }
            if(pairs.size()==1&&"LISTO_PARA_INSERTAR".equals(pairs.get(0).state))counts[2]++;
            else counts[3]++;
        }
        for(Map.Entry<String,Integer> e:statusCount.entrySet())System.out.println("A61_STATUS\t"+e.getKey()+"\t"+e.getValue());
        return counts;
    }
    private static String score(boolean has,int h,int a){return has?h+"-"+a:"";}
    static void run(String[] args)throws Exception{
        if(args.length==1&&"--self-test".equals(args[0])){
            Ls a=new Ls();a.id=1;a.home=10;a.away=11;a.date=LocalDateTime.parse("2026-01-01T20:00:00");a.hasScore=true;a.hg=2;a.ag=1;
            Tc b=new Tc();b.id=2;b.home=3;b.away=4;b.date=a.date;b.zone="PROFILE_VERIFIED";b.hasScore=true;b.hg=2;b.ag=1;
            Map<Long,Long> teams=new HashMap<Long,Long>();teams.put(3L,10L);teams.put(4L,11L);
            StringWriter out=new StringWriter();int[] totals=analyze(Arrays.asList(a),Arrays.asList(b),teams,new HashMap<Long,List<Link>>(),new HashMap<Long,List<Link>>(),5,out);
            if(totals[2]!=1||!out.toString().contains("LISTO_PARA_INSERTAR"))throw new AssertionError("A61 candidato seguro");
            out=new StringWriter();b.zone="UNVERIFIED";totals=analyze(Arrays.asList(a),Arrays.asList(b),teams,new HashMap<Long,List<Link>>(),new HashMap<Long,List<Link>>(),5,out);
            if(totals[2]!=0||totals[3]!=1||!out.toString().contains("REVISAR_ZONA_HORARIA"))throw new AssertionError("A61 zona horaria");
            out=new StringWriter();b.zone="PROFILE_VERIFIED";b.hg=3;totals=analyze(Arrays.asList(a),Arrays.asList(b),teams,new HashMap<Long,List<Link>>(),new HashMap<Long,List<Link>>(),5,out);
            if(totals[2]!=0||!out.toString().contains("REVISAR_MARCADOR"))throw new AssertionError("A61 marcador");
            out=new StringWriter();b.hg=2;Map<Long,List<Link>> byTc=new HashMap<Long,List<Link>>(),byLs=new HashMap<Long,List<Link>>();byTc.put(2L,Arrays.asList(new Link(2,99,"VERIFIED")));
            totals=analyze(Arrays.asList(a),Arrays.asList(b),teams,byTc,byLs,5,out);
            if(totals[2]!=0||!out.toString().contains("REVISAR_ENLACE_EXISTENTE"))throw new AssertionError("A61 conflicto");
            if(!"\"x,y\"".equals(csv("x,y")))throw new AssertionError("A61 CSV coma");
            if(!VERIFIED_TEAMS_SQL.contains("l.tc_league_id=?") ||
               !VERIFIED_TEAMS_SQL.contains("UNION") ||
               !VERIFIED_TEAMS_SQL.contains("lk.review_status='VERIFIED'"))
                throw new AssertionError("A61 equipo VERIFIED filtrado por liga TC");
            System.out.println("A61_SELF_TEST_OK | Java8 | CSV coma | solo SELECT");return;
        }
        if(args.length!=6)throw new IllegalArgumentException("Uso: --ls-league-id=N --tc-league-id=N --from=YYYY-MM-DD --to-exclusive=YYYY-MM-DD --tolerance-minutes=N --output=CSV");
        long ls=positive(param(args[0],"--ls-league-id="));String tc=Long.toString(positive(param(args[1],"--tc-league-id=")));
        String from=LocalDate.parse(param(args[2],"--from=")).toString(),to=LocalDate.parse(param(args[3],"--to-exclusive=")).toString();
        if(!LocalDate.parse(to).isAfter(LocalDate.parse(from)))throw new IllegalArgumentException("Hasta debe ser posterior a Desde");
        String toleranceStr=param(args[4],"--tolerance-minutes=");if(!toleranceStr.matches("[0-9]{1,4}"))throw new IllegalArgumentException("Tolerancia no valida");
        int tolerance=Integer.parseInt(toleranceStr);if(tolerance<0||tolerance>1440)throw new IllegalArgumentException("Tolerancia debe estar entre 0 y 1440");
        Path output=Paths.get(param(args[5],"--output=")).toAbsolutePath().normalize();
        if(!output.toString().toLowerCase(Locale.ROOT).endsWith(".csv")||Files.exists(output))throw new IOException("CSV debe ser nuevo y terminar en .csv");
        String pass=env("TOTALCORNER_DB_PASSWORD","");if(pass.isEmpty())throw new IOException("Falta TOTALCORNER_DB_PASSWORD");
        String url=env("TOTALCORNER_DB_URL","jdbc:mysql://localhost:3307/freefootstats?serverTimezone=UTC&useUnicode=true");
        String user=env("TOTALCORNER_DB_USER","root");
        DriverManager.setLoginTimeout(15);Class.forName("com.mysql.cj.jdbc.Driver");
        System.out.println("=== A6.1 PREVISUALIZACION | LS BASE DE REFERENCIA | MYSQL SOLO SELECT | API=0 ===");
        System.out.println("A61_SCOPE\t"+ls+"\t"+tc+"\t"+from+"\t"+to+"\t"+tolerance);
        try(Connection db=DriverManager.getConnection(url,user,pass)){
            db.setReadOnly(true);
            try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT CONVERT_TZ('2026-11-01 12:00:00','+00:00','Europe/Madrid')")){
                if(!r.next()||r.getString(1)==null)throw new SQLException("CONVERT_TZ Europe/Madrid no funciona en MySQL");
            }
            List<Ls> matches=loadLs(db,ls,from,to);List<Tc> fixtures=loadTc(db,tc,from,to,tolerance);
            Map<Long,Long> teams=verifiedTeams(db,tc);
            Map<Long,List<Link>> byTc=new HashMap<Long,List<Link>>(),byLs=new HashMap<Long,List<Link>>();
            loadLinksQuery(db,"SELECT lk.tc_match_pk,lk.ls_match_id,lk.review_status FROM tc_match_link lk "
                 +"JOIN tc_match t ON t.id=lk.tc_match_pk JOIN tc_league l ON l.id=t.league_id WHERE l.tc_league_id=?",tc,byTc,byLs);
            loadLinksQuery(db,"SELECT lk.tc_match_pk,lk.ls_match_id,lk.review_status FROM tc_match_link lk "
                 +"JOIN ls_match m ON m.id=lk.ls_match_id JOIN ls_league_season s ON s.id=m.lsLeague_season_id WHERE s.league_id=?",Long.valueOf(ls),byTc,byLs);
            Path parent=output.getParent();if(parent!=null)Files.createDirectories(parent);
            try(Writer w=new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(output,StandardOpenOption.CREATE_NEW),StandardCharsets.UTF_8))){
                try{w.write('\uFEFF');int[] count=analyze(matches,fixtures,teams,byTc,byLs,tolerance,w);
                    System.out.println("A61_RESULT\t"+count[0]+"\t"+count[1]+"\t"+count[2]+"\t"+count[3]+"\t"+count[4]+"\t"+count[5]);
                }catch(Exception ex){Files.deleteIfExists(output);throw ex;}
            }
            System.out.println("A61_CSV\t"+output);
            System.out.println("A61_FIN | solo previsualizacion; ningun enlace insertado. Revisar CSV antes de A6.2.");
        }
    }
    public static void main(String[] args){try{run(args);}catch(Throwable ex){System.err.println("A61_ERROR: "+clean(ex.getMessage()));System.exit(2);}}
}
