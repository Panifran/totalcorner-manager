package com.footiqo.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.text.Normalizer;
import java.util.*;

/** A6.3: investigar ambos sentidos; A6.4: simular y aplicar solo las parejas aprobadas manualmente. Java 8. */
public final class A63ManualMatchRunner {
    private A63ManualMatchRunner() {}
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] HEADER={"tipo","ls_match_id","tc_match_pk","tc_match_id_api","fecha_ls_madrid","fecha_tc_madrid", "diferencia_minutos","temporada_ls","equipo_ls_local","equipo_ls_visitante","marcador_ls","equipo_tc_local","equipo_tc_visitante","marcador_tc","evidencia","equipo_local_verificado","equipo_visitante_verificado","zona_tc","estado_tc","excepcion","huella"};
    static final class Match {
        long id,api,home,away;String date="",utc="",homeName="",awayName="",score="",zone="",state="",season="";boolean finalResult,hasScore;
        int hg,ag;
    }
    static final class Row {
        String kind,quality="",hint="",fingerprint="";Match ls,tc;long seconds;boolean homeVerified,awayVerified;
        String[] cells(){return new String[]{kind,ls==null?"":Long.toString(ls.id),tc==null?"":Long.toString(tc.id),tc==null?"":Long.toString(tc.api),
            ls==null?"":ls.date,tc==null?"":tc.date,ls==null||tc==null?"":Double.toString(seconds/60.0),ls==null?"":ls.season,
            ls==null?"":ls.homeName,ls==null?"":ls.awayName,ls==null?"":ls.score,tc==null?"":tc.homeName,tc==null?"":tc.awayName,tc==null?"":tc.score,
            quality,homeVerified?"SI":"NO",awayVerified?"SI":"NO",tc==null?"":tc.zone,tc==null?"":tc.state,hint,fingerprint};}
        String key(){return ls.id+":"+tc.id;}
    }
    static final class Report {
        final List<Row> rows=new ArrayList<Row>();final Map<String,Row> pairs=new LinkedHashMap<String,Row>();
        int lsMissing,tcMissing,pairsTotal,lsPending,tcPending,lsWithCandidates;boolean truncated;
    }
    private static String param(String value,String prefix){if(!value.startsWith(prefix))throw new IllegalArgumentException("Falta "+prefix);return value.substring(prefix.length());}
    private static int number(String s,int min,int max){if(!s.matches("[0-9]{1,9}"))throw new IllegalArgumentException("Numero invalido: "+s);
        int n=Integer.parseInt(s);if(n<min||n>max)throw new IllegalArgumentException("Valor fuera de rango "+min+".."+max+": "+n);return n;}
    private static String env(String n,String fallback){String v=System.getenv(n);return v==null||v.trim().isEmpty()?fallback:v;}
    private static String clean(String s){return s==null?"":s.replace('\r',' ').replace('\n',' ').replace('\t',' ');}
    private static String str(ResultSet r,String key)throws SQLException{String s=r.getString(key);return s==null?"":s;}
    private static String id(ResultSet r,String key)throws SQLException{return str(r,key);}
    private static String norm(String s){String v=Normalizer.normalize(s==null?"":s,Normalizer.Form.NFD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT);
        return v.replaceAll("[^a-z0-9]","");}
    private static boolean sameName(String a,String b){return !a.isEmpty()&&!b.isEmpty()&&norm(a).equals(norm(b));}
    private static LocalDateTime dt(String s){return s.isEmpty()?null:LocalDateTime.parse(s,TIME);}
    private static String hash(String s){try{byte[] raw=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder b=new StringBuilder();for(byte x:raw)b.append(String.format("%02x",x&255));return b.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private static String fingerprint(Row r){String[] c=r.cells();StringBuilder s=new StringBuilder();for(int i=0;i<c.length-1;i++)s.append(c[i]).append('\u0001');
        s.append(r.ls==null?"":r.ls.home+":"+r.ls.away).append('|').append(r.tc==null?"":r.tc.home+":"+r.tc.away).append('|');
        if(r.ls!=null)s.append(r.ls.utc);if(r.tc!=null)s.append('|').append(r.tc.utc);return hash(s.toString());}
    private static String quoted(String s){return "\""+s.replace("\"","\"\"")+"\"";}
    private static void csv(Writer w,String[] cells)throws IOException{for(int i=0;i<cells.length;i++){if(i>0)w.write(',');w.write(quoted(cells[i]==null?"":cells[i]));}w.write("\r\n");}
    private static String scores(Object h,Object a){return h==null||a==null?"?-?":h.toString()+"-"+a.toString();}
    private static void dates(Connection db,String from,String to)throws SQLException{
        try(PreparedStatement p=db.prepareStatement("SELECT CONVERT_TZ(?,'Europe/Madrid','+00:00'),CONVERT_TZ(?,'Europe/Madrid','+00:00')")){
            p.setString(1,from);p.setString(2,to);try(ResultSet r=p.executeQuery()){if(!r.next()||r.getString(1)==null||r.getString(2)==null)throw new SQLException("MySQL sin conversion horaria Europe/Madrid");}}
    }
    private static List<Match> loadLs(Connection db,long lsLeague,String from,String to)throws SQLException{
        String sql="SELECT m.id,m.lsHomeTeam_id,m.lsAwayTeam_id,m.ftHomeTeamGoals,m.ftAwayTeamGoals,"
            +"DATE_FORMAT(m.matchDate,'%Y-%m-%d %H:%i:%s') AS dt_local,"
            +"DATE_FORMAT(CONVERT_TZ(m.matchDate,'Europe/Madrid','+00:00'),'%Y-%m-%d %H:%i:%s') AS dt_utc,"
            +"COALESCE(h.name,'') home_name,COALESCE(a.name,'') away_name,COALESCE(ss.name,'') season "
            +"FROM ls_match m JOIN ls_league_season s ON s.id=m.lsLeague_season_id "
            +"JOIN ls_season ss ON ss.id=s.season_id "
            +"LEFT JOIN ls_team h ON h.id=m.lsHomeTeam_id LEFT JOIN ls_team a ON a.id=m.lsAwayTeam_id "
            +"WHERE s.league_id=? AND m.pendingFinalResult=b'0' AND m.matchDate>=? AND m.matchDate<? "
            +"AND NOT EXISTS(SELECT 1 FROM tc_match_link l WHERE l.ls_match_id=m.id AND l.review_status='VERIFIED') "
            +"ORDER BY m.matchDate,m.id";
        List<Match> out=new ArrayList<Match>();try(PreparedStatement p=db.prepareStatement(sql)){p.setLong(1,lsLeague);p.setString(2,from);p.setString(3,to);p.setQueryTimeout(120);
            try(ResultSet r=p.executeQuery()){while(r.next()){Match m=new Match();m.id=r.getLong("id");m.home=r.getLong("lsHomeTeam_id");m.away=r.getLong("lsAwayTeam_id");
                m.homeName=str(r,"home_name");m.awayName=str(r,"away_name");m.date=str(r,"dt_local");m.utc=str(r,"dt_utc");m.season=str(r,"season");
                Object h=r.getObject("ftHomeTeamGoals"),a=r.getObject("ftAwayTeamGoals");m.hasScore=h!=null&&a!=null;m.score=scores(h,a);
                if(m.hasScore){m.hg=((Number)h).intValue();m.ag=((Number)a).intValue();}out.add(m);}}}return out;
    }
    private static List<Match> loadTc(Connection db,String tcLeague,String from,String to,int minutes)throws SQLException{
        String sql="SELECT m.id,m.tc_match_id,m.home_team_id,m.away_team_id,m.home_goals,m.away_goals,"
            +"COALESCE(m.home_team_name,'') home_name,COALESCE(m.away_team_name,'') away_name,"
            +"COALESCE(m.match_status,'') match_state,COALESCE(m.kickoff_timezone_status,'') zone,"
            +"DATE_FORMAT(CONVERT_TZ(m.kickoff_utc,'+00:00','Europe/Madrid'),'%Y-%m-%d %H:%i:%s') dt_local,"
            +"DATE_FORMAT(m.kickoff_utc,'%Y-%m-%d %H:%i:%s') dt_utc "
            +"FROM tc_match m JOIN tc_league l ON l.id=m.league_id "
            +"WHERE l.tc_league_id=? AND (m.kickoff_utc IS NULL OR (m.kickoff_utc >= CONVERT_TZ(?,'Europe/Madrid','+00:00')-INTERVAL ? MINUTE "
            +"AND m.kickoff_utc < CONVERT_TZ(?,'Europe/Madrid','+00:00')+INTERVAL ? MINUTE)) "
            +"AND NOT EXISTS(SELECT 1 FROM tc_match_link x WHERE x.tc_match_pk=m.id AND x.review_status='VERIFIED') ORDER BY m.id";
        List<Match> out=new ArrayList<Match>();try(PreparedStatement p=db.prepareStatement(sql)){p.setString(1,tcLeague);p.setString(2,from);p.setInt(3,minutes);p.setString(4,to);p.setInt(5,minutes);p.setQueryTimeout(120);
            try(ResultSet r=p.executeQuery()){while(r.next()){Match m=new Match();m.id=r.getLong("id");m.api=r.getLong("tc_match_id");m.home=r.getLong("home_team_id");m.away=r.getLong("away_team_id");
                m.homeName=str(r,"home_name");m.awayName=str(r,"away_name");m.date=str(r,"dt_local");m.utc=str(r,"dt_utc");m.zone=str(r,"zone");m.state=str(r,"match_state");
                Object h=r.getObject("home_goals"),a=r.getObject("away_goals");m.hasScore=h!=null&&a!=null;m.score=scores(h,a);
                if(m.hasScore){m.hg=((Number)h).intValue();m.ag=((Number)a).intValue();}out.add(m);}}}return out;
    }
    private static Map<Long,Long> loadTeams(Connection db,String league)throws SQLException{
        Map<Long,Long> out=new HashMap<Long,Long>();
        String sql="SELECT tl.tc_team_pk,tl.ls_team_id FROM tc_team_link tl JOIN ("
          +"SELECT m.home_team_id AS id FROM tc_match m JOIN tc_league l ON l.id=m.league_id WHERE l.tc_league_id=? "
          +"UNION SELECT m.away_team_id AS id FROM tc_match m JOIN tc_league l ON l.id=m.league_id WHERE l.tc_league_id=?) scoped ON scoped.id=tl.tc_team_pk "
          +"WHERE tl.review_status='VERIFIED'";
        try(PreparedStatement p=db.prepareStatement(sql)){p.setString(1,league);p.setString(2,league);
            try(ResultSet r=p.executeQuery()){while(r.next()){long tc=r.getLong(1),ls=r.getLong(2);Long old=out.putIfAbsent(tc,ls);
                if(old!=null&&old.longValue()!=ls)throw new SQLException("Mapping TC contradictorio dentro de liga TC="+league+" para equipo "+tc);}}}return out;
    }
    private static void loadBlocked(Connection db,long lsLeague,String tcLeague,Set<Long> blockedLs,Set<Long> blockedTc)throws SQLException{
        String sql="SELECT x.ls_match_id,x.tc_match_pk FROM tc_match_link x JOIN ls_match lm ON lm.id=x.ls_match_id "
            +"JOIN ls_league_season s ON s.id=lm.lsLeague_season_id WHERE s.league_id=?";
        try(PreparedStatement p=db.prepareStatement(sql)){p.setLong(1,lsLeague);try(ResultSet r=p.executeQuery()){
            while(r.next()){blockedLs.add(r.getLong(1));blockedTc.add(r.getLong(2));}}}
        String other="SELECT x.ls_match_id,x.tc_match_pk FROM tc_match_link x JOIN tc_match tm ON tm.id=x.tc_match_pk "
            +"JOIN tc_league l ON l.id=tm.league_id WHERE l.tc_league_id=?";
        try(PreparedStatement p=db.prepareStatement(other)){p.setString(1,tcLeague);try(ResultSet r=p.executeQuery()){
            while(r.next()){blockedLs.add(r.getLong(1));blockedTc.add(r.getLong(2));}}}
    }
    private static final Comparator<Row> PAIR_ORDER=new Comparator<Row>(){public int compare(Row a,Row b){
        int evA=(a.homeVerified?1:0)+(a.awayVerified?1:0),evB=(b.homeVerified?1:0)+(b.awayVerified?1:0);
        if(evA!=evB)return evB-evA;
        boolean sA=a.ls.hasScore&&a.tc.hasScore&&a.ls.hg==a.tc.hg&&a.ls.ag==a.tc.ag;
        boolean sB=b.ls.hasScore&&b.tc.hasScore&&b.ls.hg==b.tc.hg&&b.ls.ag==b.tc.ag;
        if(sA!=sB)return sA?-1:1;int d=Long.compare(Math.abs(a.seconds),Math.abs(b.seconds));if(d!=0)return d;return Long.compare(a.tc.id,b.tc.id);
    }};
    private static String classify(boolean homeVerified,boolean awayVerified,boolean sameScore,int lsCandidates,int tcCandidates){
        if(!homeVerified||!awayVerified)return "REVISAR_MAPPING";
        if(!sameScore)return "REVISAR_MARCADOR";
        if(lsCandidates!=1||tcCandidates!=1)return "AMBIGUO";
        return "CANDIDATO";
    }
    private static Report report(Connection db,long lsLeague,String tcLeague,String from,String to,int tolerance,int maxProposals)throws SQLException{
        dates(db,from,to);
        List<Match> ls=loadLs(db,lsLeague,from,to),tc=loadTc(db,tcLeague,from,to,tolerance);
        Map<Long,Long> teams=loadTeams(db,tcLeague);
        Set<Long> blockedLs=new HashSet<Long>(),blockedTc=new HashSet<Long>();loadBlocked(db,lsLeague,tcLeague,blockedLs,blockedTc);
        Report out=new Report();out.lsPending=ls.size();out.tcPending=tc.size();Set<Long> tcWithPair=new HashSet<Long>();
        // Unicidad global, no solo entre los primeros maxProposals mostrados.
        // Cada pareja con indicios cuenta, incluso si tiene equipos no VERIFED.
        Map<Long,Integer> countLs=new HashMap<Long,Integer>(),countTc=new HashMap<Long,Integer>();
        for(Match a:ls){List<Row> found=new ArrayList<Row>();LocalDateTime utcA=dt(a.utc);
            if(!blockedLs.contains(a.id)&&utcA!=null)for(Match b:tc){if(blockedTc.contains(b.id))continue;LocalDateTime utcB=dt(b.utc);if(utcB==null)continue;
                long seconds=Duration.between(utcA,utcB).getSeconds();if(Math.abs(seconds)>tolerance*60L)continue;
                boolean home=Objects.equals(teams.get(b.home),a.home),away=Objects.equals(teams.get(b.away),a.away);
                boolean nh=sameName(a.homeName,b.homeName),na=sameName(a.awayName,b.awayName);
                boolean score=a.hasScore&&b.hasScore&&a.hg==b.hg&&a.ag==b.ag;
                // Un indicio no es prueba: se muestran incluso candidatos de calidad baja para revision humana.
                if(!(home||away||nh||na||(score&&Math.abs(seconds)<=3600)))continue;
                Row r=new Row();r.kind="REVISAR_MAPPING";r.ls=a;r.tc=b;r.seconds=seconds;r.homeVerified=home;r.awayVerified=away;
                r.quality=home&&away?(score?"ALTA: EQUIPOS + MARCADOR":"REVISAR: EQUIPOS, MARCADOR DISTINTO"):
                    (home||away)?(score?"MEDIA: EQUIPO + MARCADOR":"REVISAR: UN EQUIPO"):
                    nh&&na&&score?"MEDIA: NOMBRES + MARCADOR":score?"BAJA: MARCADOR + FECHA":"REVISAR: NOMBRES";
                r.hint=(!"PROFILE_VERIFIED".equals(b.zone)?"ZONA_TC_NO_VERIFICADA | ":"")+
                    (!a.hasScore?"SIN_MARCADOR_LS | ":!b.hasScore?"SIN_MARCADOR_TC | ":!score?"MARCADOR_DISTINTO | ":"")+
                    (home&&away?"":"EQUIPOS_NO_VERIFICADOS_2_DE_2 | ");
                found.add(r);
                countLs.put(a.id,countLs.containsKey(a.id)?countLs.get(a.id)+1:1);
                countTc.put(b.id,countTc.containsKey(b.id)?countTc.get(b.id)+1:1);
                tcWithPair.add(b.id);
            }
            if(found.isEmpty()){Row r=new Row();r.kind="SOLO_LS";r.ls=a;r.quality="SIN_CANDIDATO";
                r.hint=blockedLs.contains(a.id)?"ENLACE_PREEXISTENTE_NO_VERIFIED":"No se encontro pareja con indicios dentro del margen";out.rows.add(r);out.lsMissing++;}
            else{out.lsWithCandidates++;Collections.sort(found,PAIR_ORDER);if(found.size()>maxProposals)out.truncated=true;
                for(int i=0;i<Math.min(maxProposals,found.size());i++){Row r=found.get(i);out.rows.add(r);out.pairs.put(r.key(),r);tcWithPair.add(r.tc.id);out.pairsTotal++;}}
        }
        // Etiquetas independientes del ranking: un candidato seguro exige AMBOS
        // equipos VERIFIED y marcador identico. Unico = exactamente una pareja
        // posible en cada sentido, contando incluso las ocultas por maxProposals.
        for(Row r:out.pairs.values()){
            boolean score=r.ls.hasScore&&r.tc.hasScore&&r.ls.hg==r.tc.hg&&r.ls.ag==r.tc.ag;
            r.kind=classify(r.homeVerified,r.awayVerified,score,countLs.get(r.ls.id),countTc.get(r.tc.id));
            r.fingerprint=fingerprint(r);
        }
        for(Match b:tc)if(!tcWithPair.contains(b.id)){
            Row r=new Row();r.kind="SOLO_TC";r.tc=b;r.quality="SIN_CANDIDATO";
            r.hint=blockedTc.contains(b.id)?"ENLACE_PREEXISTENTE_NO_VERIFIED":b.date.isEmpty()?"SIN_FECHA_TC":"Sin pareja LS con indicios dentro del margen";out.rows.add(r);out.tcMissing++;}
        return out;
    }
    private static void outputCsv(Path out,Report result)throws IOException{
        if(Files.exists(out))throw new IOException("No sobrescribir informe anterior: "+out);
        if(out.getParent()!=null)Files.createDirectories(out.getParent());
        try(Writer w=new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(out,StandardOpenOption.CREATE_NEW),StandardCharsets.UTF_8))){
            w.write('\uFEFF');csv(w,HEADER);for(Row row:result.rows)csv(w,row.cells());}
    }
    /** Lector de las filas del CSV A6.3 para la tabla Swing. Nombres con coma/comillas. */
    public static List<String[]> loadReport(Path path)throws IOException{
        List<String[]> list=new ArrayList<String[]>();
        try(BufferedReader b=Files.newBufferedReader(path,StandardCharsets.UTF_8)){
            String line;int n=0;while((line=b.readLine())!=null){
                List<String> cells=new ArrayList<String>();StringBuilder cell=new StringBuilder();boolean quoted=false;
                if(n==0&&line.startsWith("\uFEFF"))line=line.substring(1);
                for(int i=0;i<line.length();i++){char c=line.charAt(i);
                    if(c=='"'){if(quoted&&i+1<line.length()&&line.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=!quoted;}
                    else if(c==','&&!quoted){cells.add(cell.toString());cell.setLength(0);}else cell.append(c);
                }
                if(quoted)throw new IOException("Comillas sin cerrar en CSV A6.3 linea "+(n+1));
                cells.add(cell.toString());if(cells.size()!=HEADER.length)throw new IOException("CSV A6.3 columnas invalidas en linea "+(n+1));
                String[] row=cells.toArray(new String[0]);
                if(n++==0){if(!Arrays.equals(row,HEADER))throw new IOException("Cabecera CSV A6.3 no coincide con la version actual");}
                else list.add(row);
            }
            if(n==0)throw new IOException("CSV A6.3 vacio");
        }
        return list;
    }
    private static Map<String,String> selected(Path path)throws IOException{
        if(!Files.isRegularFile(path))throw new IOException("No existe seleccion: "+path);
        Map<String,String> map=new LinkedHashMap<String,String>();
        try(BufferedReader r=Files.newBufferedReader(path,StandardCharsets.UTF_8)){
            String line;int n=0;while((line=r.readLine())!=null){n++;if(n==1&&line.equals("ls_match_id,tc_match_pk,huella"))continue;
                String[] p=line.split(",",-1);if(p.length!=3||!p[0].matches("[0-9]+")||!p[1].matches("[0-9]+")||!p[2].matches("[a-f0-9]{64}"))
                    throw new IOException("Seleccion invalida en linea "+n);
                if(map.putIfAbsent(p[0]+":"+p[1],p[2])!=null)throw new IOException("Pareja duplicada en seleccion: "+p[0]+":"+p[1]);
            }
        }
        if(map.isEmpty())throw new IOException("No hay partidos seleccionados");
        Set<String> ls=new HashSet<String>(),tc=new HashSet<String>();for(String k:map.keySet()){
            String[] p=k.split(":");if(!ls.add(p[0])||!tc.add(p[1]))throw new IOException("Conflicto: un mismo LS o TC aparece en varias parejas seleccionadas");}
        return map;
    }
    private static void schema(Connection db)throws SQLException{
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT ENGINE FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tc_match_link'")){
            if(!r.next()||!"InnoDB".equalsIgnoreCase(r.getString(1)))throw new SQLException("tc_match_link debe usar InnoDB para ROLLBACK");}
        for(String name:new String[]{"ls_match_id","tc_match_pk"}){
            try(PreparedStatement p=db.prepareStatement("SELECT INDEX_NAME FROM information_schema.statistics WHERE TABLE_SCHEMA=DATABASE() "
                 +"AND TABLE_NAME='tc_match_link' AND NON_UNIQUE=0 GROUP BY INDEX_NAME HAVING COUNT(*)=1 AND MAX(CASE WHEN COLUMN_NAME=? THEN 1 ELSE 0 END)=1")){
                p.setString(1,name);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Falta UNIQUE independiente sobre "+name);}}
        }
    }
    private static void validateSelection(Map<String,String> selection,Report live)throws IOException{
        for(Map.Entry<String,String> e:selection.entrySet()){Row r=live.pairs.get(e.getKey());
            if(r==null||!e.getValue().equals(r.fingerprint))throw new IOException("Candidato desactualizado para "+e.getKey()+". Reconsultar A6.3 y marcar de nuevo.");}
    }
    private static void apply(Connection db,Map<String,String> selection,Report live,long lsLeague,String tcLeague,String from,String to,int tolerance)throws Exception{
        schema(db);db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);db.setAutoCommit(false);boolean committed=false;
        String verify="SELECT m.id FROM ls_match m JOIN ls_league_season s ON s.id=m.lsLeague_season_id "
          +"JOIN tc_match t ON t.id=? JOIN tc_league l ON l.id=t.league_id "
          +"WHERE m.id=? AND s.league_id=? AND l.tc_league_id=? AND m.pendingFinalResult=b'0' "
          +"AND m.matchDate>=? AND m.matchDate<? AND t.kickoff_utc IS NOT NULL "
          +"AND ABS(TIMESTAMPDIFF(SECOND,CONVERT_TZ(m.matchDate,'Europe/Madrid','+00:00'),t.kickoff_utc))<=? "
          +"AND NOT EXISTS(SELECT 1 FROM tc_match_link x WHERE x.ls_match_id=m.id OR x.tc_match_pk=t.id) FOR UPDATE";
        try(PreparedStatement check=db.prepareStatement(verify);
            PreparedStatement insert=db.prepareStatement("INSERT INTO tc_match_link(tc_match_pk,ls_match_id,match_method,confidence,review_status,checked_at) VALUES(?,?,'MATCH_MANUAL_REVIEW',NULL,'VERIFIED',UTC_TIMESTAMP(3))")){
            check.setQueryTimeout(90);insert.setQueryTimeout(90);
            // Validamos cada pareja en el mismo snapshot transaccional y bloqueamos filas base antes de INSERT.
            for(String key:selection.keySet()){
                Row row=live.pairs.get(key);check.setLong(1,row.tc.id);check.setLong(2,row.ls.id);check.setLong(3,lsLeague);check.setString(4,tcLeague);
                check.setString(5,from);check.setString(6,to);check.setLong(7,tolerance*60L);
                try(ResultSet r=check.executeQuery()){if(!r.next()||r.next())throw new SQLException("Conflicto o fecha/estado cambiado: "+key);}
            }
            int count=0;for(String key:selection.keySet()){
                Row row=live.pairs.get(key);insert.setLong(1,row.tc.id);insert.setLong(2,row.ls.id);
                if(insert.executeUpdate()!=1)throw new SQLException("Fallo al insertar "+key);count++;}
            if(count!=selection.size())throw new SQLException("Recuento inesperado");
            db.commit();committed=true;System.out.println("A64_APPLIED\t"+count);
        }finally{if(!committed)db.rollback();db.setAutoCommit(true);}
    }
    private static void run(String[] args)throws Exception{
        if(args.length==1&&"--self-test".equals(args[0])){
            Row r=new Row();r.kind="CANDIDATO";r.ls=new Match();r.tc=new Match();r.ls.id=2;r.tc.id=3;r.ls.home=1;r.tc.home=1;r.quality="BAJA: MARCADOR + FECHA";r.ls.homeName="a, \"b\"";
            r.fingerprint=fingerprint(r);if(!r.fingerprint.matches("[a-f0-9]{64}"))throw new AssertionError("Huella");
            Path test=Files.createTempFile("a63-selftest-",".csv");try(Writer w=Files.newBufferedWriter(test,StandardCharsets.UTF_8)){csv(w,HEADER);csv(w,r.cells());}
            if(!new String(Files.readAllBytes(test),StandardCharsets.UTF_8).contains("\"a, \"\"b\"\"\""))throw new AssertionError("CSV RFC4180");Files.delete(test);
            if(!sameName("CF Montréal","cf montreal"))throw new AssertionError("Alias normalizado");
            if(!"REVISAR_MAPPING".equals(classify(false,true,true,1,1)))throw new AssertionError("No VERIFIED");
            if(!"REVISAR_MARCADOR".equals(classify(true,true,false,1,1)))throw new AssertionError("Marcador");
            if(!"AMBIGUO".equals(classify(true,true,true,1,2)))throw new AssertionError("Duplicado TC");
            if(!"AMBIGUO".equals(classify(true,true,true,2,1)))throw new AssertionError("Duplicado LS");
            if(!"CANDIDATO".equals(classify(true,true,true,1,1)))throw new AssertionError("Unico verificado");
            System.out.println("A63_SELF_TEST_OK | CSV UTF-8 RFC4180, nombres, evidencia y huella Java8");return;}
        if(args.length!=11)throw new IllegalArgumentException("Uso: --mode=REPORT|PREVIEW|APPLY --ls-league-id=N --tc-league-id=N --from=YYYY-MM-DD --to-exclusive=YYYY-MM-DD --tolerance-minutes=N --max-proposals=N --output=path|- --selection=path|- --expected=N --audit=path|-");
        String mode=param(args[0],"--mode=");if(!Arrays.asList("REPORT","PREVIEW","APPLY").contains(mode))throw new IllegalArgumentException("Modo invalido");
        long ls=number(param(args[1],"--ls-league-id="),1,10000000);String tc=Long.toString(number(param(args[2],"--tc-league-id="),1,10000000));
        String from=LocalDate.parse(param(args[3],"--from=")).toString(),to=LocalDate.parse(param(args[4],"--to-exclusive=")).toString();
        if(!LocalDate.parse(to).isAfter(LocalDate.parse(from)))throw new IllegalArgumentException("Rango fechas invalido");
        int tolerance=number(param(args[5],"--tolerance-minutes="),1,14400),max=number(param(args[6],"--max-proposals="),1,30);
        String o=param(args[7],"--output="),selectedArg=param(args[8],"--selection=");int expected=number(param(args[9],"--expected="),0,1000000);
        String auditArg=param(args[10],"--audit=");
        if("APPLY".equals(mode)&&"-".equals(auditArg))throw new IllegalArgumentException("APPLY necesita auditoria CSV");
        if(!"APPLY".equals(mode)&&!"-".equals(auditArg))throw new IllegalArgumentException("REPORT/PREVIEW sin auditoria");
        if("REPORT".equals(mode)&&("-".equals(o)||!"-".equals(selectedArg)||expected!=0))throw new IllegalArgumentException("REPORT requiere --output=path --selection=- --expected=0");
        if(!"REPORT".equals(mode)&&(!"-".equals(o)||"-".equals(selectedArg)||expected<1))throw new IllegalArgumentException("PREVIEW/APPLY requieren seleccion y expected>0");
        Path path="-".equals(o)?null:Paths.get(o).toAbsolutePath().normalize();
        Map<String,String> selection="REPORT".equals(mode)?Collections.<String,String>emptyMap():selected(Paths.get(selectedArg));
        if(!"REPORT".equals(mode)&&selection.size()!=expected)throw new IOException("Recuento seleccionado distinto al esperado");
        String pw=env("TOTALCORNER_DB_PASSWORD","");if(pw.isEmpty())throw new IOException("Falta TOTALCORNER_DB_PASSWORD");
        String url=env("TOTALCORNER_DB_URL","jdbc:mysql://localhost:3307/freefootstats?serverTimezone=UTC&useUnicode=true"),user=env("TOTALCORNER_DB_USER","root");
        DriverManager.setLoginTimeout(15);Class.forName("com.mysql.cj.jdbc.Driver");
        try(Connection db=DriverManager.getConnection(url,user,pw)){
            db.setReadOnly(!"APPLY".equals(mode));
            System.out.println("=== A6.3/4 "+mode+" | LS="+ls+" TC="+tc+" | margen_min="+tolerance+" | SQL "+("APPLY".equals(mode)?"TRANSACCIONAL":"LECTURA")+" | API=0 ===");
            Report report=report(db,ls,tc,from,to,tolerance,max);
            System.out.println("A63_RESULT\t"+report.pairsTotal+"\t"+report.lsMissing+"\t"+report.tcMissing+"\t"+report.rows.size()+"\t"+(report.truncated?"PROPOSALS_LIMIT":"OK")+
                "\t"+report.lsPending+"\t"+report.tcPending+"\t"+report.lsWithCandidates);
            Map<String,Integer> typeCounts=new TreeMap<String,Integer>();
            for(Row row:report.rows)typeCounts.put(row.kind,typeCounts.containsKey(row.kind)?typeCounts.get(row.kind)+1:1);
            for(Map.Entry<String,Integer> item:typeCounts.entrySet())System.out.println("A63_TIPO\t"+item.getKey()+"\t"+item.getValue());
            if("REPORT".equals(mode)){
                outputCsv(path,report);System.out.println("A63_CSV\t"+path);System.out.println("A63_REPORT_OK | Solo lectura. Revisar candidatos y marcar pares individuales.");return;}
            validateSelection(selection,report);schema(db);
            System.out.println("A64_PLAN\t"+expected+"\t"+tolerance+"\t0_CONFLICTOS");
            if("PREVIEW".equals(mode)){System.out.println("A64_PREVIEW_OK | Sin escrituras. Revision manual obligatoria.");return;}
            Path audit=Paths.get(auditArg).toAbsolutePath().normalize();
            if(Files.exists(audit))throw new IOException("Auditoria ya existe; no sobrescribir: "+audit);
            apply(db,selection,report,ls,tc,from,to,tolerance);
            try {
                if(audit.getParent()!=null)Files.createDirectories(audit.getParent());
                try(Writer w=new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(audit,StandardOpenOption.CREATE_NEW),StandardCharsets.UTF_8))){
                    w.write('\uFEFF');csv(w,new String[]{"ls_match_id","tc_match_pk","match_method","review_status","tipo_a63","fecha_ls","fecha_tc","diferencia_minutos","marcador_ls","marcador_tc","evidencia","avisos","huella"});
                    for(String key:selection.keySet()){Row x=report.pairs.get(key);csv(w,new String[]{Long.toString(x.ls.id),Long.toString(x.tc.id),"MATCH_MANUAL_REVIEW","VERIFIED",x.kind,
                         x.ls.date,x.tc.date,Double.toString(x.seconds/60.0),x.ls.score,x.tc.score,x.quality,x.hint,x.fingerprint});}
                }
                System.out.println("A64_AUDIT\t"+audit);
            }catch(Exception ex){System.out.println("A64_AUDIT_WARNING: COMMIT realizado pero CSV no disponible: "+clean(ex.getMessage()));}
            System.out.println("A64_DONE | COMMIT confirmado; repetir A6.3 para recalcular pendientes.");
        }
    }
    public static void main(String[] args){try{run(args);}catch(Throwable e){System.err.println("A63_ERROR: "+clean(e.getMessage()));System.exit(2);}}
}
