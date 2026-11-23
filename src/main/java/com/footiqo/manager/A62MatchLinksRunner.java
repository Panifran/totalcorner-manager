package com.footiqo.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/** A6.2: Aplica EXCLUSIVAMENTE los pares LISTO_PARA_INSERTAR de A6.1, con snapshot actual y transaccion atomica. */
public final class A62MatchLinksRunner {
    private A62MatchLinksRunner() {}
    static final class Pair {
        final long ls,tc,seconds;
        Pair(long ls,long tc,long seconds){this.ls=ls;this.tc=tc;this.seconds=seconds;}
        String key(){return ls+":"+tc;}
    }
    static final class CsvResult {
        final LinkedHashMap<String,Pair> ready=new LinkedHashMap<String,Pair>();
        final Set<Long> usedLs=new HashSet<Long>(),usedTc=new HashSet<Long>();
        int totalRows,otherRows;
    }
    private static String param(String arg,String key) {if(!arg.startsWith(key))throw new IllegalArgumentException("Falta "+key);return arg.substring(key.length());}
    private static long id(String s){if(!s.matches("[0-9]{1,15}"))throw new IllegalArgumentException("ID no numerico: "+s);long n=Long.parseLong(s);if(n<=0)throw new IllegalArgumentException("ID <=0");return n;}
    private static int number(String s,int min,int max){if(!s.matches("[0-9]{1,7}"))throw new IllegalArgumentException("Numero invalido: "+s);int n=Integer.parseInt(s);if(n<min||n>max)throw new IllegalArgumentException("Numero fuera de rango: "+n);return n;}
    private static String env(String key,String fallback){String x=System.getenv(key);return x==null||x.trim().isEmpty()?fallback:x;}
    private static String scrub(String s){return s==null?"":s.replace('\n',' ').replace('\r',' ').replace('\t',' ');}
    private static void writeCsvLine(Writer w,String... cols) throws IOException {for(int i=0;i<cols.length;i++){if(i>0)w.write(',');w.write('"');w.write(cols[i].replace("\"","\"\""));w.write('"');}w.write("\r\n");}
    /** RFC 4180: comillas dobles, separador coma, CRLF y campos multilinea. */
    static List<List<String>> parseCsv(Path p)throws IOException {
        List<List<String>> rows=new ArrayList<List<String>>();
        try(PushbackReader r=new PushbackReader(new BufferedReader(new InputStreamReader(Files.newInputStream(p),StandardCharsets.UTF_8)),2)) {
            List<String> cols=new ArrayList<String>();StringBuilder cell=new StringBuilder();boolean quoted=false,afterQuote=false,any=false;
            int ch;
            while((ch=r.read())!=-1){
                if(!any&&cols.isEmpty()&&cell.length()==0&&ch==0xFEFF)continue;
                any=true;
                if(quoted){if(ch=='"'){int next=r.read();if(next=='"')cell.append('"');else{quoted=false;afterQuote=true;if(next!=-1)r.unread(next);}}else cell.append((char)ch);continue;}
                if(afterQuote&&ch!=','&&ch!='\r'&&ch!='\n')throw new IOException("CSV mal escapado tras comillas");
                if(ch=='"'){if(cell.length()!=0||afterQuote)throw new IOException("CSV comillas invalidas");quoted=true;continue;}
                if(ch==','){cols.add(cell.toString());cell.setLength(0);afterQuote=false;continue;}
                if(ch=='\r'||ch=='\n'){if(ch=='\r'){int next=r.read();if(next!='\n'&&next!=-1)r.unread(next);}cols.add(cell.toString());rows.add(cols);cols=new ArrayList<String>();cell.setLength(0);afterQuote=false;any=false;continue;}
                cell.append((char)ch);
            }
            if(quoted)throw new IOException("CSV contiene campo con comillas sin cerrar");
            if(any||!cols.isEmpty()){cols.add(cell.toString());rows.add(cols);}
        }
        return rows;
    }
    static CsvResult load(Path path) throws Exception {
        if(!Files.isRegularFile(path))throw new IOException("Falta CSV A6.1: "+path);
        List<List<String>> rows=parseCsv(path);
        if(rows.isEmpty())throw new IOException("CSV A6.1 vacio");
        List<String> header=rows.get(0);
        int state=header.indexOf("evaluacion"),ls=header.indexOf("ls_match_id"),tc=header.indexOf("tc_match_pk");
        if(state<0||ls<0||tc<0||header.size()!=17||header.indexOf("marcador_evaluacion")<0||header.indexOf("zona_tc")<0||header.indexOf("diferencia_segundos")<0)throw new IOException("Formato CSV A6.1 no reconocido");
        CsvResult result=new CsvResult();
        for(int i=1;i<rows.size();i++){
            List<String> cols=rows.get(i);
            if(cols.size()!=header.size())throw new IOException("Columnas inconsistentes en fila "+(i+1));
            result.totalRows++;String s=cols.get(state);
            if(!"LISTO_PARA_INSERTAR".equals(s)){result.otherRows++;continue;}
            Pair p=new Pair(id(cols.get(ls)),id(cols.get(tc)),Long.parseLong(cols.get(header.indexOf("diferencia_segundos"))));
            if(result.ready.putIfAbsent(p.key(),p)!=null||!result.usedLs.add(p.ls)||!result.usedTc.add(p.tc))
                throw new IOException("CSV A6.1 no es 1:1 para "+p.key());
            if(!"MARCADOR_OK".equals(cols.get(header.indexOf("marcador_evaluacion"))) ||
               !"PROFILE_VERIFIED".equals(cols.get(header.indexOf("zona_tc"))))
                throw new IOException("CSV A6.1 contiene falso candidato seguro: "+p.key());
        }
        return result;
    }
    private static boolean uniqueSingleColumn(Connection db,String column) throws SQLException {
        String sql="SELECT index_name,COUNT(*) AS cols,MAX(CASE WHEN column_name=? THEN 1 ELSE 0 END) AS hits "
            +"FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='tc_match_link' "
            +"AND non_unique=0 GROUP BY index_name HAVING cols=1 AND hits=1";
        try(PreparedStatement st=db.prepareStatement(sql)){st.setString(1,column);try(ResultSet r=st.executeQuery()){return r.next();}}
    }
    private static void ensureTransactionalSchema(Connection db)throws SQLException{
        try(PreparedStatement st=db.prepareStatement("SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='tc_match_link'");ResultSet r=st.executeQuery()){
            if(!r.next()||!"InnoDB".equalsIgnoreCase(r.getString(1)))throw new SQLException("A6.2 requiere tc_match_link en InnoDB (ROLLBACK)");
        }
        if(!uniqueSingleColumn(db,"tc_match_pk")||!uniqueSingleColumn(db,"ls_match_id"))
            throw new SQLException("A6.2 requiere indices UNIQUE independientes para tc_match_pk y ls_match_id en tc_match_link. Revisar esquema antes de aplicar.");
    }
    private static final String APPLY_SQL=
        "INSERT INTO tc_match_link (tc_match_pk,ls_match_id,match_method,confidence,review_status,checked_at) "
       +"SELECT t.id,m.id,CASE WHEN ABS(TIMESTAMPDIFF(SECOND,m.matchDate,CONVERT_TZ(t.kickoff_utc,'+00:00','Europe/Madrid')))<=300 "
       +"THEN 'MATCH_5MIN_SCORE' WHEN ABS(TIMESTAMPDIFF(SECOND,m.matchDate,CONVERT_TZ(t.kickoff_utc,'+00:00','Europe/Madrid')))<=900 "
       +"THEN 'MATCH_15MIN_SCORE' ELSE 'MATCH_EXT_SCORE_REVIEW' END, NULL,'VERIFIED',UTC_TIMESTAMP(3) "
       +"FROM tc_match t JOIN tc_league tl ON tl.id=t.league_id JOIN ls_match m ON m.id=? "
       +"JOIN ls_league_season s ON s.id=m.lsLeague_season_id "
       +"WHERE t.id=? AND tl.tc_league_id=? AND s.league_id=? AND m.pendingFinalResult=b'0' "
       +"AND m.matchDate >= ? AND m.matchDate < ? AND t.kickoff_utc >= CONVERT_TZ(?,'Europe/Madrid','+00:00') "
       +"AND t.kickoff_utc < CONVERT_TZ(?,'Europe/Madrid','+00:00') "
       +"AND t.kickoff_timezone_status='PROFILE_VERIFIED' "
       +"AND ABS(TIMESTAMPDIFF(SECOND,m.matchDate,CONVERT_TZ(t.kickoff_utc,'+00:00','Europe/Madrid')))<=? "
       +"AND m.ftHomeTeamGoals IS NOT NULL AND m.ftAwayTeamGoals IS NOT NULL "
       +"AND t.home_goals=m.ftHomeTeamGoals AND t.away_goals=m.ftAwayTeamGoals "
       +"AND EXISTS (SELECT 1 FROM tc_team_link ht WHERE ht.tc_team_pk=t.home_team_id "
       +"AND ht.ls_team_id=m.lsHomeTeam_id AND ht.review_status='VERIFIED') "
       +"AND EXISTS (SELECT 1 FROM tc_team_link at WHERE at.tc_team_pk=t.away_team_id "
       +"AND at.ls_team_id=m.lsAwayTeam_id AND at.review_status='VERIFIED') "
       +"AND NOT EXISTS (SELECT 1 FROM tc_match_link x WHERE x.tc_match_pk=t.id OR x.ls_match_id=m.id)";

    private static void run(String[] args) throws Exception {
        if(args.length==1&&"--self-test".equals(args[0])){
            Path p=Files.createTempFile("a62-test-",".csv");
            try(Writer w=Files.newBufferedWriter(p,StandardCharsets.UTF_8)){
                w.write('\uFEFF');writeCsvLine(w,"evaluacion","ls_match_id","tc_match_pk","marcador_evaluacion","zona_tc","diferencia_segundos","x2","x3","x4","x5","x6","x7","x8","x9","x10","x11","x12");
                writeCsvLine(w,"LISTO_PARA_INSERTAR","1","2","MARCADOR_OK","PROFILE_VERIFIED","0","\"comillas\"","", "", "", "", "", "", "", "", "", "");
            }
            CsvResult result=load(p);Files.deleteIfExists(p);
            if(result.ready.size()!=1||!"1:2".equals(result.ready.values().iterator().next().key()))throw new AssertionError("CSV safe");
            System.out.println("A62_SELF_TEST_OK | CSV RFC4180 | unicidad 1:1 | Java8");return;
        }
        if(args.length!=9)throw new IllegalArgumentException("Uso: --mode=PREVIEW|APPLY --ls-league-id=N --tc-league-id=N --from=YYYY-MM-DD --to-exclusive=YYYY-MM-DD --tolerance-minutes=N --csv=path --expected=N --audit=path|- ");
        String mode=param(args[0],"--mode=");if(!"PREVIEW".equals(mode)&&!"APPLY".equals(mode))throw new IllegalArgumentException("Modo invalido");
        long ls=id(param(args[1],"--ls-league-id="));String tc=Long.toString(id(param(args[2],"--tc-league-id=")));
        String from=LocalDate.parse(param(args[3],"--from=")).toString(),to=LocalDate.parse(param(args[4],"--to-exclusive=")).toString();
        if(!LocalDate.parse(to).isAfter(LocalDate.parse(from)))throw new IllegalArgumentException("Rango fechas invalido");
        int tolerance=number(param(args[5],"--tolerance-minutes="),0,1440);
        if(tolerance>15)throw new IllegalArgumentException("A6.2 solo autoenlaza con tolerancia hasta 15 min. Ampliar requiere revision individual A6.3.");
        Path csv=Paths.get(param(args[6],"--csv=")).toAbsolutePath().normalize();
        int expected=number(param(args[7],"--expected="),1,1000000);
        String auditArg=param(args[8],"--audit=");Path audit="-".equals(auditArg)?null:Paths.get(auditArg).toAbsolutePath().normalize();
        if("APPLY".equals(mode)&&(audit==null||Files.exists(audit)))throw new IOException("La aplicacion necesita un CSV de auditoria NUEVO");
        if("PREVIEW".equals(mode)&&audit!=null)throw new IllegalArgumentException("No crear auditoria en simulacion");
        if(env("TOTALCORNER_DB_PASSWORD","").isEmpty())throw new IOException("Falta TOTALCORNER_DB_PASSWORD");
        CsvResult approved=load(csv);
        if(approved.ready.size()!=expected)throw new IOException("CSV actual tiene "+approved.ready.size()+" seguros, esperado="+expected);
        System.out.println("=== A6.2 "+mode+" | LS="+ls+" TC="+tc+" | CSV seguros="+approved.ready.size()+" | API=0 ===");
        // Recalcular completamente A6.1 desde MySQL; nunca confiar en el CSV aprobado sin revalidacion.
        Path fresh=Files.createTempFile("totalcorner-a62-live-",".csv");Files.deleteIfExists(fresh);
        CsvResult current;
        try {
            A61MatchPreviewRunner.run(new String[]{"--ls-league-id="+ls,"--tc-league-id="+tc,"--from="+from,
                "--to-exclusive="+to,"--tolerance-minutes="+tolerance,"--output="+fresh});
            current=load(fresh);
        } finally {Files.deleteIfExists(fresh);}
        if(!approved.ready.keySet().equals(current.ready.keySet())){
            Set<String> missing=new HashSet<String>(approved.ready.keySet());missing.removeAll(current.ready.keySet());
            Set<String> added=new HashSet<String>(current.ready.keySet());added.removeAll(approved.ready.keySet());
            throw new IOException("CSV A6.1 desactualizado: "+missing.size()+" pares ya no seguros y "+added.size()
                +" nuevos. Repetir A6.1 y revisar antes de aplicar.");
        }
        System.out.println("A62_PLAN\t"+expected+"\t"+current.otherRows+"\t"+tolerance);
        String url=env("TOTALCORNER_DB_URL","jdbc:mysql://localhost:3307/freefootstats?serverTimezone=UTC&useUnicode=true");
        String user=env("TOTALCORNER_DB_USER","root");
        DriverManager.setLoginTimeout(15);Class.forName("com.mysql.cj.jdbc.Driver");
        try(Connection db=DriverManager.getConnection(url,user,env("TOTALCORNER_DB_PASSWORD",""))){
            ensureTransactionalSchema(db);
            if("PREVIEW".equals(mode)){
                System.out.println("A62_PREVIEW_OK | CSV y MySQL coinciden; InnoDB e indices UNIQUE comprobados | 0 escrituras");
                return;
            }
            db.setAutoCommit(false);boolean committed=false;
            try(PreparedStatement ins=db.prepareStatement(APPLY_SQL)){
                ins.setQueryTimeout(120);int done=0;
                for(Pair p:current.ready.values()){
                    ins.setLong(1,p.ls);ins.setLong(2,p.tc);ins.setString(3,tc);ins.setLong(4,ls);
                    ins.setString(5,from);ins.setString(6,to);ins.setString(7,from);ins.setString(8,to);
                    ins.setLong(9,tolerance*60L);
                    int count=ins.executeUpdate();
                    if(count!=1)throw new SQLException("No se puede insertar de forma segura "+p.key()+": inserciones="+count+". ROLLBACK completo.");
                    done++;
                    if(done%500==0)System.out.println("A62_PROGRESS\t"+done+"/"+expected);
                }
                if(done!=expected)throw new SQLException("Diferencia de conteo, ROLLBACK");
                db.commit();committed=true;
                System.out.println("A62_APPLIED\t"+done);
            } finally {if(!committed)db.rollback();db.setAutoCommit(true);}
        }
        try {
            Path parent=audit.getParent();if(parent!=null)Files.createDirectories(parent);
            try(Writer w=new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(audit,StandardOpenOption.CREATE_NEW),StandardCharsets.UTF_8))){
                w.write('\uFEFF');writeCsvLine(w,"ls_match_id","tc_match_pk","review_status","match_method","accion");
                for(Pair p:current.ready.values())writeCsvLine(w,Long.toString(p.ls),Long.toString(p.tc),"VERIFIED",
                    Math.abs(p.seconds)<=300?"MATCH_5MIN_SCORE":Math.abs(p.seconds)<=900?"MATCH_15MIN_SCORE":"MATCH_EXT_SCORE_REVIEW","INSERTADO");
            }
            System.out.println("A62_AUDIT\t"+audit);
        }catch(Exception ex){System.out.println("A62_AUDIT_WARNING: COMMIT realizado, pero no se guardo CSV: "+scrub(ex.getMessage()));}
        System.out.println("A62_DONE | COMMIT confirmado. Repetir A6.1 para actualizar pendientes.");
    }
    public static void main(String[] args){try{run(args);}catch(Throwable ex){System.err.println("A62_ERROR: "+scrub(ex.getMessage()));System.exit(2);}}
}
