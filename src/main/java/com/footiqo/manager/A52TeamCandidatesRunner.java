package com.footiqo.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/** A5.2: proponer enlaces TC/LS con evidencia de partidos; SELECT y CSV, sin escrituras SQL. */
public final class A52TeamCandidatesRunner {
    private static final String RESOURCE="/com/footiqo/manager/a52-candidates.sql";
    /** Separador CSV configurable para la instalacion del cliente: coma. */
    private static final char CSV_SEPARATOR = ',';
    private static final String[] HEADERS={"tc_team_pk","tc_team_api_id","equipo_totalcorner",
        "ls_team_id","equipo_footiqo","partidos_evidencia","candidatos_ls_para_tc",
        "candidatos_tc_para_ls","evaluacion","ejemplos_tc_match_pk:ls_match_id"};
    private A52TeamCandidatesRunner(){}

    private static String arg(String text,String prefix) {
        if(!text.startsWith(prefix)||text.length()<=prefix.length())throw new IllegalArgumentException("Parametro: "+prefix);
        return text.substring(prefix.length());
    }
    private static long id(String text) {
        if(!text.matches("[0-9]{1,15}")||Long.parseLong(text)<=0)throw new IllegalArgumentException("ID no valido: "+text);
        return Long.parseLong(text);
    }
    private static String env(String name,String fallback){String s=System.getenv(name);return s==null||s.trim().isEmpty()?fallback:s;}
    private static String sql() throws IOException {
        try(InputStream in=A52TeamCandidatesRunner.class.getResourceAsStream(RESOURCE)) {
            if(in==null)throw new IOException("No se encuentra recurso SQL A5.2 en el JAR.");
            ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
            while((n=in.read(buf))>=0)b.write(buf,0,n);
            return new String(b.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static String safe(String s){return s==null?"":s.replace('\t',' ').replace('\r',' ').replace('\n',' ');}
    private static String csv(String s){return "\""+(s==null?"":s.replace("\"","\"\""))+"\"";}
    private static void row(Writer w,String[] items)throws IOException {
        for(int i=0;i<items.length;i++){
            if(i>0)w.write(CSV_SEPARATOR);w.write(csv(items[i]));
        }w.write("\r\n");
    }
    private static void bind(PreparedStatement p,String tc,String ls,String from,String to,int tolerance)throws SQLException {
        p.setString(1,tc);p.setString(2,from);p.setString(3,to);
        p.setLong(4,Long.parseLong(ls));p.setString(5,from);p.setString(6,to);
        p.setInt(7,tolerance);p.setInt(8,tolerance);
    }
    public static void main(String[] args){
        try { run(args); } catch(Throwable ex){
            String msg=ex instanceof SQLException ? "SQLSTATE="+((SQLException)ex).getSQLState()+" CODE="+((SQLException)ex).getErrorCode()+": "+ex.getMessage():ex.getMessage();
            System.err.println("A52_ERROR: "+safe(msg));System.exit(2);
        }
    }
    private static void run(String[] args)throws Exception {
        if(args.length==1&&"--self-test".equals(args[0])) {
            String q=sql().toLowerCase(Locale.ROOT);
            if(!q.contains("with")||!q.contains("candidate_fixture")||!q.contains("profile_verified")||
               q.matches("(?s).*\\b(insert|update|delete|replace|create|drop|alter|truncate)\\b.*"))
                throw new AssertionError("A52 SQL debe ser SELECT con evidencias, sin DML/DDL");
            if(id("85")!=85||!"\"a\"\"b\"".equals(csv("a\"b")))throw new AssertionError("CSV/ID");
            StringWriter sample=new StringWriter();
            row(sample,new String[]{"FC, Dallas","Club \"United\"","linea; con punto y coma"});
            if(!"\"FC, Dallas\",\"Club \"\"United\"\"\",\"linea; con punto y coma\"\r\n".equals(sample.toString()))
                throw new AssertionError("CSV: separador coma / comillas / texto");
            System.out.println("A52_SELF_TEST_OK | Java8 | CSV UTF8 BOM | CSV_SEPARATOR=COMMA | SQL SOLO SELECT");return;
        }
        if(args.length!=6)throw new IllegalArgumentException("Uso: --ls-league-id=N --tc-league-id=N --from=YYYY-MM-DD --to-exclusive=YYYY-MM-DD --tolerance-minutes=N --output=CSV");
        String ls=Long.toString(id(arg(args[0],"--ls-league-id=")));
        String tc=Long.toString(id(arg(args[1],"--tc-league-id=")));
        String from=LocalDate.parse(arg(args[2],"--from=")).toString();
        String to=LocalDate.parse(arg(args[3],"--to-exclusive=")).toString();
        if(!LocalDate.parse(to).isAfter(LocalDate.parse(from)))throw new IllegalArgumentException("Fecha final debe ser posterior a inicial");
        String tol=arg(args[4],"--tolerance-minutes=");
        if(!tol.matches("[0-9]{1,4}"))throw new IllegalArgumentException("Tolerancia invalida");
        int tolerance=Integer.parseInt(tol);
        if(tolerance<0||tolerance>1440)throw new IllegalArgumentException("Tolerancia max 1440 min");
        Path output=Paths.get(arg(args[5],"--output=")).toAbsolutePath().normalize();
        if(!output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))throw new IllegalArgumentException("Salida debe ser CSV");
        if(Files.exists(output))throw new IOException("El CSV ya existe, no se sobrescribira: "+output);
        String pass=env("TOTALCORNER_DB_PASSWORD","");
        if(pass.isEmpty())throw new IllegalArgumentException("Falta TOTALCORNER_DB_PASSWORD en el entorno");
        String url=env("TOTALCORNER_DB_URL","jdbc:mysql://localhost:3307/freefootstats?serverTimezone=UTC&useUnicode=true");
        String user=env("TOTALCORNER_DB_USER","root");
        DriverManager.setLoginTimeout(15);Class.forName("com.mysql.cj.jdbc.Driver");
        System.out.println("=== A5.2 PROPONER EQUIPOS POR PARTIDOS | API=0 | MYSQL SOLO LECTURA ===");
        System.out.println("A52_SCOPE\t"+ls+"\t"+tc+"\t"+from+"\t"+to+"\t"+tolerance);
        try(Connection db=DriverManager.getConnection(url,user,pass)) {
            db.setReadOnly(true);db.setAutoCommit(true);
            // Fail closed when time zone tables are absent: no broken or misleading CSV.
            try(Statement p=db.createStatement();ResultSet r=p.executeQuery(
                "SELECT CONVERT_TZ('2026-11-01 12:00:00','+00:00','Europe/Madrid') AS zone_check")){
                if(!r.next()||r.getString(1)==null)throw new SQLException("Zonas horarias MySQL incompletas (CONVERT_TZ devuelve NULL)");
                System.out.println("A52_ZONE_OK\t"+r.getString(1));
            }
            Path parent=output.getParent();if(parent!=null)Files.createDirectories(parent);
            int count=0, strong=0, alias=0,ambig=0,limited=0,none=0;
            Set<String> teams=new HashSet<String>();
            try(PreparedStatement p=db.prepareStatement(sql())){
                bind(p,tc,ls,from,to,tolerance);
                p.setQueryTimeout(240);
                try(ResultSet r=p.executeQuery();Writer w=new BufferedWriter(new OutputStreamWriter(
                    Files.newOutputStream(output,StandardOpenOption.CREATE_NEW),StandardCharsets.UTF_8))){
                    w.write('\uFEFF');row(w,HEADERS);
                    while(r.next()){
                        String[] values={str(r,"tc_team_pk"),str(r,"id_equipo_api"),str(r,"equipo_tc"),
                            str(r,"ls_team_id"),str(r,"equipo_footiqo"),str(r,"partidos_evidencia"),
                            str(r,"candidatos_ls_para_tc"),str(r,"candidatos_tc_para_ls"),
                            str(r,"evaluacion"),str(r,"ejemplos_tc_ls")};
                        row(w,values);count++;teams.add(values[0]);
                        if("CANDIDATO_CON_EVIDENCIA".equals(values[8]))strong++;
                        else if("POSIBLES_ALIAS_TC_REVISAR".equals(values[8]))alias++;
                        else if("AMBIGUO_REVISAR".equals(values[8]))ambig++;
                        else if("EVIDENCIA_LIMITADA_REVISAR".equals(values[8]))limited++;
                        else if("SIN_CANDIDATO_POR_PARTIDOS".equals(values[8]))none++;
                        System.out.println("A52_TEAM\t"+safe(values[0])+"\t"+safe(values[2])+"\t"+
                            safe(values[3])+"\t"+safe(values[4])+"\t"+values[5]+"\t"+values[8]);
                    }
                }
            } catch(Exception ex) {Files.deleteIfExists(output);throw ex;}
            System.out.println("A52_RESULT\t"+count+"\t"+teams.size()+"\t"+strong+"\t"+alias+"\t"+ambig+"\t"+limited+"\t"+none);
            System.out.println("A52_CSV\t"+output);
            System.out.println("A52 FIN | CSV generado; ninguna insercion ni actualizacion SQL. Todos los candidatos requieren revision.");
        }
    }
    private static String str(ResultSet r,String key)throws SQLException{String s=r.getString(key);return s==null?"":s;}
}
