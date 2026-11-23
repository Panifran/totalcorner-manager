package com.footiqo.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.time.Instant;
import java.util.*;

/** A5.3: CSV reviewed by operator; live A5.2 evidence revalidated before any INSERT. */
public final class A53TeamLinksRunner {
    private static final String RESOURCE="/com/footiqo/manager/a52-candidates.sql";
    private static final String[] HEADERS={"tc_team_pk","tc_team_api_id","equipo_totalcorner","ls_team_id",
        "equipo_footiqo","partidos_evidencia","candidatos_ls_para_tc","candidatos_tc_para_ls",
        "evaluacion","ejemplos_tc_match_pk:ls_match_id"};
    private static final String STRONG="CANDIDATO_CON_EVIDENCIA", ALIAS="POSIBLES_ALIAS_TC_REVISAR";
    private A53TeamLinksRunner(){}
    static String env(String name,String def){String x=System.getenv(name);return x==null||x.trim().isEmpty()?def:x;}
    static String arg(String[] args,String key){for(String a:args)if(a.startsWith(key))return a.substring(key.length());throw new IllegalArgumentException("Falta "+key);}
    static long positive(String x){if(x==null||!x.matches("[0-9]{1,15}")||Long.parseLong(x)<1)throw new IllegalArgumentException("ID invalido: "+x);return Long.parseLong(x);}
    static int nonnegative(String s,int max){if(s==null||!s.matches("[0-9]{1,5}")||Integer.parseInt(s)>max)throw new IllegalArgumentException("Numero invalido: "+s);return Integer.parseInt(s);}
    static String getSql()throws IOException{
        try(InputStream in=A53TeamLinksRunner.class.getResourceAsStream(RESOURCE)){
            if(in==null)throw new IOException("Falta SQL A5.2 en JAR");
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
            while((n=in.read(buf))!=-1)out.write(buf,0,n);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    /** Parses quoted CSV including double quotes, commas and CRLF. */
    static List<List<String>> parseCsv(Path p)throws IOException{
        List<List<String>> table=new ArrayList<List<String>>();List<String> row=new ArrayList<String>();StringBuilder field=new StringBuilder();
        boolean quote=false,ended=false;
        try(Reader in=new BufferedReader(new InputStreamReader(Files.newInputStream(p),StandardCharsets.UTF_8))){
            int cur;while((cur=in.read())!=-1){char c=(char)cur;
                if(table.isEmpty()&&row.isEmpty()&&field.length()==0&&c=='\uFEFF')continue;
                if(quote){if(c=='"'){in.mark(1);int next=in.read();if(next=='"')field.append('"');else{quote=false;if(next!=-1)in.reset();}}
                    else field.append(c);
                }else if(c=='"'){if(field.length()>0)throw new IOException("CSV con comillas mal ubicadas");quote=true;}
                else if(c==','){row.add(field.toString());field.setLength(0);}
                else if(c=='\r'||c=='\n'){if(c=='\r'){in.mark(1);int next=in.read();if(next!='\n'&&next!=-1)in.reset();}
                    row.add(field.toString());field.setLength(0);table.add(row);row=new ArrayList<String>();ended=true;
                }else{field.append(c);ended=false;}
            }
        }
        if(quote)throw new IOException("CSV: comillas sin cerrar");
        if(!row.isEmpty()||field.length()>0||!ended){row.add(field.toString());if(!row.isEmpty())table.add(row);}
        return table;
    }
    static String[] strings(ResultSet r)throws SQLException{
        return new String[]{str(r,"tc_team_pk"),str(r,"id_equipo_api"),str(r,"equipo_tc"),
            str(r,"ls_team_id"),str(r,"equipo_footiqo"),str(r,"partidos_evidencia"),
            str(r,"candidatos_ls_para_tc"),str(r,"candidatos_tc_para_ls"),str(r,"evaluacion"),str(r,"ejemplos_tc_ls")};
    }
    static String str(ResultSet r,String k)throws SQLException{String s=r.getString(k);return s==null?"":s;}
    static String key(String tc,String ls){return tc+":"+ls;}
    static void validate(String[] csv,String[] fresh){
        // The number of matching fixtures and the five sample IDs can change as
        // matches are completed, corrected or linked. They are not identities.
        // Recompute evidence from MySQL, but never ignore changes to IDs,
        // names, uniqueness, or evaluation type.
        for(int i=0;i<csv.length;i++){
            if(i==5||i==9)continue; // evidence count and illustrative sample only
            if(!csv[i].equals(fresh[i]))
                throw new IllegalStateException("Candidato "+key(csv[0],csv[3])+
                    " ha cambiado en MySQL/CSV: columna "+HEADERS[i]+". Regenera A5.2.");
        }
        int previousEvidence=nonnegative(csv[5],Integer.MAX_VALUE);
        String category=fresh[8];int n=nonnegative(fresh[5],Integer.MAX_VALUE),ct=nonnegative(fresh[6],Integer.MAX_VALUE),ls=nonnegative(fresh[7],Integer.MAX_VALUE);
        if(previousEvidence<1)throw new IllegalStateException("CSV A5.2 sin evidencia para "+key(csv[0],csv[3]));
        if(ct!=1||n<1||positive(fresh[0])<1||positive(fresh[3])<1)throw new IllegalStateException("No hay evidencia univoca para "+key(fresh[0],fresh[3]));
        if(STRONG.equals(category)){if(n<3||previousEvidence<3||ls!=1)throw new IllegalStateException("Candidato fuerte insuficiente o ambiguo");}
        else if(ALIAS.equals(category)){if(ls<=1)throw new IllegalStateException("Alias sin varios TC para un LS");}
        else throw new IllegalStateException("No autorizable: "+category);
    }
    static String csv(String x){return "\""+x.replace("\"","\"\"")+"\"";}
    static void writeRow(Writer w,String... fields)throws IOException{
        for(int i=0;i<fields.length;i++){if(i>0)w.write(',');w.write(csv(fields[i]));}w.write("\r\n");
    }
    static Map<String,String[]> loadCsv(Path path)throws IOException{
        List<List<String>> table=parseCsv(path);
        if(table.size()<2||table.get(0).size()!=HEADERS.length)throw new IOException("CSV A5.2 sin datos o columnas invalidas");
        for(int i=0;i<HEADERS.length;i++)if(!HEADERS[i].equals(table.get(0).get(i)))throw new IOException("La columna "+i+" del CSV A5.2 no coincide. Regeneralo.");
        Map<String,String[]> rows=new LinkedHashMap<String,String[]>();
        for(int ri=1;ri<table.size();ri++){
            List<String> r=table.get(ri);if(r.size()!=HEADERS.length)throw new IOException("CSV A5.2: columnas incorrectas en fila "+(ri+1));
            String[] cells=r.toArray(new String[HEADERS.length]);
            String k=cells[3].isEmpty()?cells[0]+":SIN_CANDIDATO_FILA_"+ri:key(cells[0],cells[3]);
            if(cells[0].isEmpty())throw new IOException("CSV A5.2 sin TC PK en fila "+(ri+1));
            if(rows.put(k,cells)!=null)throw new IOException("Pareja TC/LS repetida en CSV: "+k);
        }
        return rows;
    }
    static Map<String,String[]> live(Connection db,String tc,String ls,String from,String to,int tol)throws Exception{
        Map<String,String[]> found=new HashMap<String,String[]>();
        try(PreparedStatement p=db.prepareStatement(getSql())){
            p.setString(1,tc);p.setString(2,from);p.setString(3,to);p.setLong(4,positive(ls));
            p.setString(5,from);p.setString(6,to);p.setInt(7,tol);p.setInt(8,tol);
            p.setQueryTimeout(240);
            try(ResultSet r=p.executeQuery()){
                while(r.next()){String[] a=strings(r);if(!a[3].isEmpty())found.put(key(a[0],a[3]),a);}
            }
        }
        return found;
    }
    static void checkExisting(Connection db,String[] a)throws SQLException{
        try(PreparedStatement q=db.prepareStatement("SELECT tc_team_pk,ls_team_id,review_status FROM freefootstats.tc_team_link WHERE tc_team_pk=?")){
            q.setLong(1,positive(a[0]));try(ResultSet r=q.executeQuery()){
                if(r.next())throw new IllegalStateException("TC PK "+a[0]+" ya tiene un enlace; no se insertara ni reemplazara.");
            }
        }
        // Allow many TC aliases pointing to one Footiqo team only after explicit alias approval.
        if(STRONG.equals(a[8]))try(PreparedStatement q=db.prepareStatement(
            "SELECT tc_team_pk FROM freefootstats.tc_team_link WHERE ls_team_id=?")){
            q.setLong(1,positive(a[3]));try(ResultSet r=q.executeQuery()){
                if(r.next())throw new IllegalStateException("LS ID "+a[3]+" ya recibe un enlace; revisa si se trata de alias.");
            }
        }
    }
    static boolean checkZone(Connection db)throws SQLException{
        try(Statement q=db.createStatement();ResultSet r=q.executeQuery(
            "SELECT CONVERT_TZ('2026-11-01 12:00:00','+00:00','Europe/Madrid')")){return r.next()&&r.getString(1)!=null;}
    }
    public static void main(String[] args){try{run(args);}catch(Throwable ex){
        System.err.println("A53_ERROR: "+ex.getClass().getSimpleName()+": "+String.valueOf(ex.getMessage()).replace('\n',' ').replace('\r',' '));System.exit(2);
    }}
    static void run(String[] args)throws Exception{
        if(args.length==1&&"--self-test".equals(args[0])){
            Path tmp=Files.createTempFile("a53-test", ".csv");try{
                try(Writer w=Files.newBufferedWriter(tmp,StandardCharsets.UTF_8)){
                    w.write('\uFEFF');writeRow(w,HEADERS);writeRow(w,"123","99","Test, Team","456","Test \"LS\"","5","1","1",STRONG,"1:2|3:4");
                }
                Map<String,String[]> csv=loadCsv(tmp);
                if(csv.size()!=1||!csv.get("123:456")[2].equals("Test, Team")||!csv.get("123:456")[4].equals("Test \"LS\""))
                    throw new AssertionError("CSV parse/UTF8/BOM/comillas");
                validate(csv.get("123:456"),csv.get("123:456"));
                String[] fresh=csv.get("123:456").clone();fresh[5]="6";fresh[9]="99:77";
                validate(csv.get("123:456"),fresh); // evidence drift without identity drift is safe
                try{fresh[3]="789";validate(csv.get("123:456"),fresh);throw new AssertionError("Faltaba bloquear LS ID cambiado");}
                catch(IllegalStateException expected){}
                fresh=csv.get("123:456").clone();fresh[8]=ALIAS;
                try{validate(csv.get("123:456"),fresh);throw new AssertionError("Faltaba bloquear cambio de evaluacion");}
                catch(IllegalStateException expected){}
                fresh=csv.get("123:456").clone();fresh[5]="2";
                try{validate(csv.get("123:456"),fresh);throw new AssertionError("Faltaba bloquear evidencia insuficiente");}
                catch(IllegalStateException expected){}
                String[] alias=csv.get("123:456").clone();alias[8]=ALIAS;alias[5]="1";alias[7]="2";validate(alias,alias);
                try{alias[6]="2";validate(alias,alias);throw new AssertionError("Faltaba bloquear ambiguedad");}
                catch(IllegalStateException expected){};
            }finally{Files.deleteIfExists(tmp);}
            System.out.println("A53_SELF_TEST_OK | CSV, drift evidencia, IDs/evaluacion bloqueados, alias, Java8");return;
        }
        String mode=arg(args,"--mode=");boolean apply="APPLY".equals(mode);
        if(!apply&&!"PREVIEW".equals(mode))throw new IllegalArgumentException("Modo PREVIEW/APPLY");
        String tc=Long.toString(positive(arg(args,"--tc-league-id=")));
        String ls=Long.toString(positive(arg(args,"--ls-league-id=")));
        String from=LocalDate.parse(arg(args,"--from=")).toString();
        String to=LocalDate.parse(arg(args,"--to-exclusive=")).toString();
        if(!LocalDate.parse(to).isAfter(LocalDate.parse(from)))throw new IllegalArgumentException("Rango invalido");
        int tol=nonnegative(arg(args,"--tolerance-minutes="),1440);
        Path csv=Paths.get(arg(args,"--csv=")).toAbsolutePath().normalize();
        if(!Files.isRegularFile(csv))throw new IOException("No existe CSV A5.2");
        String pairs=arg(args,"--pairs=");Map<String,String[]> records=loadCsv(csv);
        LinkedHashSet<String> selected=new LinkedHashSet<String>();
        for(String token:pairs.split(",",-1)){
            if(!token.matches("[0-9]{1,15}:[0-9]{1,15}"))throw new IllegalArgumentException("Par TC:LS invalido: "+token);
            String[] ids=token.split(":");positive(ids[0]);positive(ids[1]);
            if(!selected.add(token))throw new IllegalArgumentException("Par seleccionado duplicado: "+token);
        }
        if(selected.isEmpty())throw new IllegalArgumentException("No hay pares seleccionados");
        int expected=nonnegative(arg(args,"--expected="),10000);
        if(expected!=selected.size())throw new IllegalArgumentException("Recuento esperado no coincide con los pares elegidos");
        Path audit=apply?Paths.get(arg(args,"--audit=")).toAbsolutePath().normalize():null;
        if(apply&&(audit==null||Files.exists(audit)||!audit.getFileName().toString().endsWith(".csv")))
            throw new IllegalArgumentException("CSV auditoria A5.3 debe ser una ruta nueva .csv");
        String password=env("TOTALCORNER_DB_PASSWORD","");if(password.isEmpty())throw new IllegalArgumentException("Falta TOTALCORNER_DB_PASSWORD");
        String url=env("TOTALCORNER_DB_URL","jdbc:mysql://localhost:3307/freefootstats?serverTimezone=UTC&useUnicode=true");
        String user=env("TOTALCORNER_DB_USER","root");
        Class.forName("com.mysql.cj.jdbc.Driver");DriverManager.setLoginTimeout(15);
        System.out.println("=== A5.3 "+mode+" | TC="+tc+" LS="+ls+" | "+selected.size()+" pares | API=0 ===");
        try(Connection db=DriverManager.getConnection(url,user,password)){
            db.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            db.setAutoCommit(false);
            try {
                if(!checkZone(db))throw new SQLException("Conversion timezone Europe/Madrid no disponible");
                Map<String,String[]> current=live(db,tc,ls,from,to,tol);
                List<String[]> approved=new ArrayList<String[]>();
                Set<String> seenTc=new HashSet<String>();int strong=0,alias=0;
                for(String pair:selected){
                    String[] source=records.get(pair),fresh=current.get(pair);
                    if(source==null||fresh==null)throw new IllegalStateException("Par no presente o evidencia cambio: "+pair+". Regenera A5.2.");
                    validate(source,fresh);
                    if(!source[5].equals(fresh[5]) || !source[9].equals(fresh[9]))
                        System.out.println("A53_EVIDENCE_UPDATED\t"+pair+"\tCSV="+source[5]+"\tMYSQL="+fresh[5]
                            +"\tIDs/uniqueness/evaluation unchanged; current SQL evidence used");
                    if(!seenTc.add(fresh[0]))throw new IllegalStateException("Un TC aparece con dos LS: "+fresh[0]);
                    checkExisting(db,fresh);
                    approved.add(fresh);
                    if(STRONG.equals(fresh[8]))strong++;else alias++;
                    System.out.println("A53_ROW\t"+fresh[0]+"\t"+fresh[2].replace('\t',' ')+"\t"+fresh[3]+"\t"+fresh[4].replace('\t',' ')+"\t"+fresh[5]+"\t"+fresh[8]);
                }
                System.out.println("A53_PLAN\t"+approved.size()+"\t"+strong+"\t"+alias);
                if(!apply){db.rollback();System.out.println("A53_PREVIEW_OK | 0 inserts | MySQL sin escrituras");return;}
                String stamp=Instant.now().toString();
                Path parent=audit.getParent();if(parent!=null)Files.createDirectories(parent);
                try(Writer w=new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(audit,StandardOpenOption.CREATE_NEW),StandardCharsets.UTF_8))){
                    w.write('\uFEFF');writeRow(w,"tc_team_pk","tc_team_api_id","equipo_totalcorner","ls_team_id","equipo_footiqo","evidencias","evaluacion_origen","aprobacion","fecha_utc");
                    for(String[] a:approved)writeRow(w,a[0],a[1],a[2],a[3],a[4],a[5],a[8],
                        STRONG.equals(a[8])?"A5.3 aprobado por seleccion usuario":"A5.3 ALIAS REVISADO MANUALMENTE",stamp);
                }
                int count=0;
                try(PreparedStatement p=db.prepareStatement("INSERT INTO freefootstats.tc_team_link (tc_team_pk,ls_team_id,match_method,review_status,matches_evidence,evidence_note,first_verified_at) VALUES (?,?,'MATCH_5MIN_SCORE','VERIFIED',?,?,UTC_TIMESTAMP(3))")){
                    for(String[] a:approved){
                        p.setLong(1,positive(a[0]));p.setLong(2,positive(a[3]));p.setInt(3,Integer.parseInt(a[5]));
                        String note="A5.3 selected and reviewed by operator; evaluation="+a[8]+"; tc_league="+tc+"; ls_league="+ls+
                            "; window=["+from+","+to+"); tolerance="+tol+"min; csv="+csv.getFileName();
                        p.setString(4,note);count+=p.executeUpdate();
                    }
                }
                if(count!=expected)throw new SQLException("Insertados "+count+" pero esperados "+expected);
                db.commit();
                System.out.println("A53_APPLIED\t"+count+"\t"+strong+"\t"+alias);
                System.out.println("A53_AUDIT\t"+audit);
                System.out.println("A53_DONE | "+count+" mappings VERIFIED nuevos. ls_match sin modificaciones.");
            }catch(Exception ex){try{db.rollback();}catch(SQLException ignore){};if(audit!=null)try{Files.deleteIfExists(audit);}catch(IOException ignore){};throw ex;}
        }
    }
}
