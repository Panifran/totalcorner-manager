package com.footiqo.manager;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Enlace excepcional por IDs, compartido entre Bloque 01 (A6.4) y Bloque 02 (B2.2).
 * PREVIEW sin escrituras; APPLY requiere huella de PREVIEW, locks y unicidad 1:1.
 * Java 8. Nunca cambia fechas, marcadores ni registros ls_match/tc_match.
 */
public final class ManualMatchByIdsRunner {
    private ManualMatchByIdsRunner() {}
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String LS_SQL="SELECT m.id,s.league_id AS ls_league,"
        +"COALESCE(h.name,'') AS home_name,COALESCE(a.name,'') AS away_name,"
        +"m.lsHomeTeam_id,m.lsAwayTeam_id,"
        +"DATE_FORMAT(m.matchDate,'%Y-%m-%d %H:%i:%s') AS kick,"
        +"m.ftHomeTeamGoals,m.ftAwayTeamGoals,m.pendingFinalResult "
        +"FROM ls_match m JOIN ls_league_season s ON s.id=m.lsLeague_season_id "
        +"LEFT JOIN ls_team h ON h.id=m.lsHomeTeam_id LEFT JOIN ls_team a ON a.id=m.lsAwayTeam_id WHERE m.id=?";
    private static final String TC_SQL="SELECT t.id,t.tc_match_id,l.tc_league_id,"
        +"COALESCE(t.home_team_name,'') AS home_name,COALESCE(t.away_team_name,'') AS away_name,"
        +"t.home_team_id,t.away_team_id,"
        +"DATE_FORMAT(CONVERT_TZ(t.kickoff_utc,'+00:00','Europe/Madrid'),'%Y-%m-%d %H:%i:%s') AS kick,"
        +"DATE_FORMAT(t.kickoff_utc,'%Y-%m-%d %H:%i:%s') AS kick_utc,"
        +"t.home_goals,t.away_goals,COALESCE(t.match_status,'') AS match_status "
        +"FROM tc_match t JOIN tc_league l ON l.id=t.league_id WHERE t.id=?";
    private static final String LINKS_SQL="SELECT ls_match_id,tc_match_pk,review_status FROM tc_match_link "
        +"WHERE ls_match_id=? OR tc_match_pk=?";
    private static final String TEAMS_SQL="SELECT COUNT(*) FROM tc_team_link WHERE tc_team_pk=? "
        +"AND ls_team_id=? AND review_status='VERIFIED'";
    private static final String INSERT="INSERT INTO tc_match_link(tc_match_pk,ls_match_id,match_method,confidence,review_status,checked_at) "
        +"VALUES(?,?,'MATCH_MANUAL_REVIEW',NULL,'VERIFIED',UTC_TIMESTAMP(3))";

    static final class Pair {
        long lsId, tcPk, lsLeague, lsHome, lsAway, tcHome, tcAway;
        String tcLeague="",tcApi="",lsDate="",tcDate="",tcUtc="",lsHomeName="",lsAwayName="",tcHomeName="",tcAwayName="";
        String lsScore="",tcScore="",tcState="",lsFinal="",linkInfo="";
        boolean homeVerified,awayVerified, linked;
        long minutes;
        String warning="",fingerprint="";
    }
    private static long number(String raw) {
        if(raw==null||!raw.matches("[1-9][0-9]{0,14}"))throw new IllegalArgumentException("ID debe ser positivo: "+raw);
        return Long.parseLong(raw);
    }
    private static String option(String[] args,int pos,String prefix) {
        if(pos>=args.length||!args[pos].startsWith(prefix))throw new IllegalArgumentException("Falta "+prefix);
        return args[pos].substring(prefix.length());
    }
    private static String clean(Object s) {return s==null?"":s.toString().replace('\r',' ').replace('\n',' ').replace('\t',' ');}
    private static String score(ResultSet r,String h,String a)throws SQLException{
        Object x=r.getObject(h),y=r.getObject(a);return x==null||y==null?"SIN_MARCADOR":x+"-"+y;
    }
    private static String sha(String s)throws Exception{
        byte[] data=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder b=new StringBuilder();for(byte v:data)b.append(String.format("%02x",v&255));return b.toString();
    }
    private static String csv(String s) {return "\""+s.replace("\"","\"\"")+"\"";}
    private static String key(Pair p){return p.lsId+":"+p.tcPk;}

    private static Pair inspect(Connection db,long ls,long tc,String allowed)throws Exception {
        Pair p=new Pair();
        try(PreparedStatement q=db.prepareStatement(LS_SQL)){
            q.setLong(1,ls);q.setQueryTimeout(30);
            try(ResultSet r=q.executeQuery()){
                if(!r.next())throw new SQLException("No existe ls_match.id="+ls);
                p.lsId=ls;p.lsLeague=r.getLong("ls_league");p.lsHome=r.getLong("lsHomeTeam_id");p.lsAway=r.getLong("lsAwayTeam_id");
                p.lsHomeName=clean(r.getString("home_name"));p.lsAwayName=clean(r.getString("away_name"));p.lsDate=clean(r.getString("kick"));
                p.lsScore=score(r,"ftHomeTeamGoals","ftAwayTeamGoals");p.lsFinal=r.getBoolean("pendingFinalResult")?"NO":"SI";
            }
        }
        try(PreparedStatement q=db.prepareStatement(TC_SQL)){
            q.setLong(1,tc);q.setQueryTimeout(30);
            try(ResultSet r=q.executeQuery()){
                if(!r.next())throw new SQLException("No existe tc_match.id="+tc+" (usar PK interno, NO ID API)");
                p.tcPk=tc;p.tcApi=clean(r.getString("tc_match_id"));p.tcLeague=clean(r.getString("tc_league_id"));
                p.tcHome=r.getLong("home_team_id");p.tcAway=r.getLong("away_team_id");
                p.tcHomeName=clean(r.getString("home_name"));p.tcAwayName=clean(r.getString("away_name"));
                p.tcDate=clean(r.getString("kick"));p.tcUtc=clean(r.getString("kick_utc"));
                p.tcScore=score(r,"home_goals","away_goals");p.tcState=clean(r.getString("match_status"));
            }
        }
        if(!allowed.equals("*")){
            boolean found=false;for(String id:allowed.split(","))if(p.tcLeague.equals(id.trim()))found=true;
            if(!found)throw new SQLException("TC="+p.tcLeague+" no esta entre las competiciones seleccionadas: "+allowed);
        }
        LeagueMappingRegistry.Association assoc=LeagueMappingRegistry.load().get(p.tcLeague);
        if(assoc==null||!Long.toString(p.lsLeague).equals(assoc.lsId))
            throw new SQLException("Correspondencia de liga incorrecta: LS="+p.lsLeague+", TC="+p.tcLeague+". Registra asociacion LS -> TC primero.");
        if(!"SI".equals(p.lsFinal))throw new SQLException("El partido LS="+ls+" no figura como finalizado; no se permite enlace excepcional.");
        try(PreparedStatement q=db.prepareStatement(LINKS_SQL)){
            q.setLong(1,ls);q.setLong(2,tc);q.setQueryTimeout(30);
            try(ResultSet r=q.executeQuery()){
                while(r.next()){
                    p.linked=true;p.linkInfo+="LS="+r.getLong(1)+" TC="+r.getLong(2)+" ESTADO="+clean(r.getString(3))+"; ";
                }
            }
        }
        try(PreparedStatement q=db.prepareStatement(TEAMS_SQL)){
            q.setLong(1,p.tcHome);q.setLong(2,p.lsHome);
            try(ResultSet r=q.executeQuery()){r.next();p.homeVerified=r.getInt(1)>0;}
            q.setLong(1,p.tcAway);q.setLong(2,p.lsAway);
            try(ResultSet r=q.executeQuery()){r.next();p.awayVerified=r.getInt(1)>0;}
        }
        if(!p.lsDate.isEmpty()&&!p.tcDate.isEmpty()){
            p.minutes=Math.abs(Duration.between(LocalDateTime.parse(p.lsDate,DATE),LocalDateTime.parse(p.tcDate,DATE)).toMinutes());
        }
        StringBuilder warn=new StringBuilder();
        if(p.tcUtc.isEmpty())warn.append("TC_SIN_FECHA|");
        else if(p.minutes>100)warn.append("DIFERENCIA_FECHA_"+p.minutes+"_MIN|");
        if("SIN_MARCADOR".equals(p.tcScore))warn.append("TC_SIN_MARCADOR|");
        else if(!p.lsScore.equals(p.tcScore))warn.append("MARCADOR_DISTINTO|");
        if(!p.homeVerified||!p.awayVerified)warn.append("EQUIPOS_NO_VERIFIED|");
        if(p.linked)warn.append("ENLACE_EXISTENTE|");
        p.warning=warn.length()==0?"NINGUNA":warn.toString();
        p.fingerprint=sha(key(p)+"|"+p.lsLeague+"|"+p.tcLeague+"|"+p.tcApi+"|"+p.lsHome+"|"+p.lsAway+"|"+p.tcHome+"|"+p.tcAway+
            "|"+p.lsHomeName+"|"+p.lsAwayName+"|"+p.tcHomeName+"|"+p.tcAwayName+"|"+p.lsDate+"|"+p.tcDate+
            "|"+p.tcUtc+"|"+p.lsScore+"|"+p.tcScore+"|"+p.lsFinal+"|"+p.tcState+"|"+p.homeVerified+"|"+p.awayVerified+"|"+p.linkInfo);
        return p;
    }
    private static void print(Pair p){
        System.out.println("MIDS_SCOPE\tLS_LIGA="+p.lsLeague+"\tTC_LIGA="+p.tcLeague+"\tTC_API_ID="+p.tcApi);
        System.out.println("MIDS_LS\t"+p.lsId+"\t"+p.lsDate+"\t"+clean(p.lsHomeName)+"\t"+clean(p.lsAwayName)+"\t"+p.lsScore+"\tFINAL="+p.lsFinal);
        System.out.println("MIDS_TC\t"+p.tcPk+"\t"+(p.tcDate.isEmpty()?"SIN_FECHA":p.tcDate)+"\t"+clean(p.tcHomeName)+"\t"+clean(p.tcAwayName)+"\t"+p.tcScore+"\tESTADO="+clean(p.tcState));
        System.out.println("MIDS_TEAMS\tLOCAL="+(p.homeVerified?"VERIFIED":"NO_VERIFIED")+"\tVISITANTE="+(p.awayVerified?"VERIFIED":"NO_VERIFIED"));
        System.out.println("MIDS_WARNING\t"+p.warning+"\tDELTA_MIN="+(p.tcDate.isEmpty()?"DESCONOCIDO":Long.toString(p.minutes)));
        if(p.linked)System.out.println("MIDS_EXISTING\t"+clean(p.linkInfo));
    }
    private static void checkSchema(Connection db)throws SQLException{
        try(PreparedStatement p=db.prepareStatement("SELECT ENGINE FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tc_match_link' ");
            ResultSet r=p.executeQuery()){
            if(!r.next()||!"InnoDB".equalsIgnoreCase(r.getString(1)))throw new SQLException("tc_match_link debe usar InnoDB");
        }
        for(String col:new String[]{"ls_match_id","tc_match_pk"}){
            try(PreparedStatement p=db.prepareStatement("SELECT INDEX_NAME FROM information_schema.statistics WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tc_match_link' AND NON_UNIQUE=0 GROUP BY INDEX_NAME HAVING COUNT(*)=1 AND MAX(CASE WHEN COLUMN_NAME=? THEN 1 ELSE 0 END)=1")){
                p.setString(1,col);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Falta UNIQUE independiente sobre "+col);}
            }
        }
    }
    private static void insert(Connection db,Pair seen,long ls,long tc,String allowed,String expected)throws Exception {
        checkSchema(db);
        db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);db.setAutoCommit(false);
        boolean committed=false;
        try {
            try(PreparedStatement a=db.prepareStatement("SELECT id FROM ls_match WHERE id=? FOR UPDATE");
                PreparedStatement b=db.prepareStatement("SELECT id FROM tc_match WHERE id=? FOR UPDATE")){
                a.setLong(1,ls);b.setLong(1,tc);
                try(ResultSet r=a.executeQuery()){if(!r.next())throw new SQLException("LS ya no existe");}
                try(ResultSet r=b.executeQuery()){if(!r.next())throw new SQLException("TC ya no existe");}
            }
            Pair fresh=inspect(db,ls,tc,allowed);
            if(!expected.equals(fresh.fingerprint)||!seen.fingerprint.equals(fresh.fingerprint))
                throw new SQLException("Los datos han cambiado desde la simulacion. Repite PREVIEW.");
            if(fresh.linked)throw new SQLException("El partido LS o TC ya tiene enlace. "+fresh.linkInfo);
            try(PreparedStatement stmt=db.prepareStatement(INSERT)){
                stmt.setLong(1,tc);stmt.setLong(2,ls);
                if(stmt.executeUpdate()!=1)throw new SQLException("INSERT no confirmo 1 fila");
            }
            db.commit();committed=true;
            System.out.println("MIDS_APPLIED\t"+ls+"\t"+tc+"\tMATCH_MANUAL_REVIEW\tVERIFIED");
        }finally{
            if(!committed)db.rollback();db.setAutoCommit(true);
        }
    }
    private static void audit(Path file,Pair p)throws Exception{
        if(file.getParent()!=null)Files.createDirectories(file.getParent());
        try(BufferedWriter w=Files.newBufferedWriter(file,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)){
            w.write('\uFEFF');w.write("ls_match_id,tc_match_pk,ls_league_id,tc_league_id,ls_kick,tc_kick,ls_score,tc_score,local_verified,visitante_verified,warning,match_method,review_status,huella\r\n");
            String[] row={Long.toString(p.lsId),Long.toString(p.tcPk),Long.toString(p.lsLeague),p.tcLeague,p.lsDate,p.tcDate,p.lsScore,p.tcScore,
                Boolean.toString(p.homeVerified),Boolean.toString(p.awayVerified),p.warning,"MATCH_MANUAL_REVIEW","VERIFIED",p.fingerprint};
            for(int i=0;i<row.length;i++){if(i>0)w.write(',');w.write(csv(row[i]));}w.write("\r\n");
        }
    }
    private static void run(String[] args)throws Exception {
        if(args.length==1&&"--self-test".equals(args[0])){
            if(!"3".equals(Long.toString(number("3"))) || !csv("a,\"b").equals("\"a,\"\"b\"") ||
               !"MATCH_MANUAL_REVIEW".equals("MATCH_MANUAL_REVIEW"))throw new AssertionError("Datos invalidos");
            System.out.println("MIDS_SELF_TEST_OK | Java8 | unico motor A6.4 y B2.2");return;
        }
        if(args.length!=6)throw new IllegalArgumentException("Uso: --mode=PREVIEW|APPLY --ls-match-id=N --tc-match-pk=N --allowed-tc-ids=*|CSV --fingerprint=-|SHA256 --audit=-|PATH");
        String mode=option(args,0,"--mode=");if(!"PREVIEW".equals(mode)&&!"APPLY".equals(mode))throw new IllegalArgumentException("Modo invalido");
        long ls=number(option(args,1,"--ls-match-id=")),tc=number(option(args,2,"--tc-match-pk="));
        String allowed=option(args,3,"--allowed-tc-ids=");
        if(!allowed.equals("*")&&!allowed.matches("[1-9][0-9]*(,[1-9][0-9]*)*"))throw new IllegalArgumentException("Lista TC invalida");
        String expected=option(args,4,"--fingerprint="),audit=option(args,5,"--audit=");
        if("APPLY".equals(mode)&&(!expected.matches("[a-f0-9]{64}")||"-".equals(audit)))throw new IllegalArgumentException("APPLY necesita huella PREVIEW y auditoria");
        if("PREVIEW".equals(mode)&&(!expected.equals("-")||!audit.equals("-")))throw new IllegalArgumentException("PREVIEW no necesita huella/auditoria");
        String pass=System.getenv("TOTALCORNER_DB_PASSWORD");if(pass==null||pass.isEmpty())throw new IOException("Falta TOTALCORNER_DB_PASSWORD");
        String url=System.getenv("TOTALCORNER_DB_URL"),user=System.getenv("TOTALCORNER_DB_USER");
        if(url==null||url.isEmpty())url="jdbc:mysql://localhost:3307/freefootstats?serverTimezone=UTC&useUnicode=true";
        if(user==null||user.isEmpty())user="root";
        DriverManager.setLoginTimeout(15);Class.forName("com.mysql.cj.jdbc.Driver");
        try(Connection db=DriverManager.getConnection(url,user,pass)){
            db.setReadOnly("PREVIEW".equals(mode));
            Pair p=inspect(db,ls,tc,allowed);print(p);
            if(p.linked)throw new SQLException("ENLACE EXISTENTE: no se permite sobreescribir ni insertar otra pareja");
            if("PREVIEW".equals(mode)){
                checkSchema(db);
                System.out.println("MIDS_PREVIEW_OK\t"+p.fingerprint+"\t0_ESCRITURAS");return;
            }
            if(!p.fingerprint.equals(expected))throw new SQLException("Datos diferentes de PREVIEW: repetir simulacion");
            Path auditFile=Paths.get(audit).toAbsolutePath().normalize();
            if(Files.exists(auditFile))throw new IOException("Auditoria ya existe: "+auditFile);
            insert(db,p,ls,tc,allowed,expected);
            try{audit(auditFile,p);System.out.println("MIDS_AUDIT\t"+auditFile);}catch(Exception ex){
                System.out.println("MIDS_AUDIT_WARNING\tCOMMIT REALIZADO; error CSV: "+clean(ex.getMessage()));}
            System.out.println("MIDS_DONE\tCOMMIT confirmado. Reconsultar B2.1/A6.3.");
        }
    }
    public static void main(String[] args){try{run(args);}catch(Throwable e){
        System.err.println("MIDS_ERROR\t"+clean(e.getClass().getSimpleName()+": "+e.getMessage()));System.exit(2);
    }}
}
