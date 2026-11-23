package com.footiqo.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** B2.3: informe de solo lectura de tablas permanentes, con temporales en sesion MySQL. */
public final class B23ReportRunner {
    private static final Map<String,Integer> MAP = new LinkedHashMap<String,Integer>();
    static { MAP.put("1",2); MAP.put("14",1); MAP.put("12",3); MAP.put("16",4);
        MAP.put("25",5); MAP.put("137",18); MAP.put("138",28); MAP.put("2",6); }
    private B23ReportRunner() {}
    private static String sanitize(String s) {return s==null?"":s.replace('\t',' ').replace('\r',' ').replace('\n',' ');}
    private static String env(String n,String d) { String x=System.getenv(n);return x==null||x.trim().isEmpty()?d:x; }

    public static void main(String[] args) {
        try {
            if (args.length==1 && "--self-test".equals(args[0])) {
                if (!MAP.containsKey("137") || !isAllowed("DELETE p FROM tmp_b23_pares p WHERE 1=0")
                    || isAllowed("DELETE FROM ls_match") || isAllowed("UPDATE tc_match SET id=1"))
                    throw new IllegalStateException("Restricciones SQL incorrectas");
                if (!loadScript().contains("SELECT z.tipo_fila")) throw new IllegalStateException("SQL B2.3 ausente");
                System.out.println("B23_SELF_TEST_OK - script SQL y lista blanca de escrituras temporales"); return;
            }
            Map<String,String> opt = new HashMap<String,String>();
            for (String s:args) { int pos=s.indexOf('='); if (!s.startsWith("--") || pos<3 || opt.put(s.substring(2,pos),s.substring(pos+1))!=null)
                throw new IllegalArgumentException("Argumento invalido/repetido"); }
            String[] keys={"league-ids","period","margin-hours","include-solo-tc"};
            for(String k:keys) if (!opt.containsKey(k)) throw new IllegalArgumentException("Falta --"+k);
            List<String> ids=B11ControlRunner.parseIds(new String[]{"--league-ids="+opt.get("league-ids")});
            String period=opt.get("period"); if (!period.equals("60d") && !period.equals("historico")) throw new IllegalArgumentException("period=60d|historico");
            int margin=Integer.parseInt(opt.get("margin-hours")); if(margin<1||margin>240) throw new IllegalArgumentException("Margen: 1..240 h");
            String showTc=opt.get("include-solo-tc"); if (!showTc.equals("true") && !showTc.equals("false")) throw new IllegalArgumentException("include-solo-tc debe ser true/false");
            LocalDateTime now=LocalDateTime.now(ZoneId.of("Europe/Madrid"));
            String from=period.equals("60d")?now.minusDays(60).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")):"2015-07-01 00:00:00";
            String until=LocalDate.now(ZoneId.of("Europe/Madrid")).plusDays(1).toString()+" 00:00:00";
            String pwd=System.getenv("TOTALCORNER_DB_PASSWORD"); if(pwd==null||pwd.isEmpty()) throw new IllegalArgumentException("Falta TOTALCORNER_DB_PASSWORD");
            Class.forName("com.mysql.cj.jdbc.Driver"); DriverManager.setLoginTimeout(12);
            try(Connection db=DriverManager.getConnection(env("TOTALCORNER_DB_URL","jdbc:mysql://localhost:3307/freefootstats?serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8&connectTimeout=10000&socketTimeout=30000"),
                    env("TOTALCORNER_DB_USER","root"),pwd)) {
                if(!"freefootstats".equalsIgnoreCase(db.getCatalog())) throw new IllegalStateException("Base de datos no es freefootstats");
                try(Statement st=db.createStatement()) { st.execute("SET SESSION time_zone='+00:00'"); }
                set(db,"SET @desde_madrid=?",from);
                set(db,"SET @hasta_madrid_exclusiva=?",until);
                set(db,"SET @margen_horas=?",margin);
                set(db,"SET @max_propuestas_por_ls=?",10);
                set(db,"SET @incluir_solo_tc=?",Boolean.parseBoolean(showTc)?1:0);
                System.out.println("B23_START\t"+ids.size()+"\t"+period+"\t"+margin+"\t"+from+"\t"+until);
                int summaries=0,details=0;
                for (String instruction:statements(loadScript())) {
                    String q=instruction.trim(); if(q.isEmpty())continue;
                    // Ajustes de variables anteriores y control de esquema ya ejecutados desde Java.
                    if(q.matches("(?is)^USE\\s+freefootstats\\s*$") ||
                            q.matches("(?is)^SET\\s+@(desde_madrid|hasta_madrid_exclusiva|margen_horas|max_propuestas_por_ls|incluir_solo_tc)\\s*=.*")) continue;
                    if(!isAllowed(q)) throw new IllegalStateException("Instruccion SQL no permitida en B2.3");
                    if(q.startsWith("INSERT INTO tmp_b23_ligas")) {
                        try(PreparedStatement ps=db.prepareStatement("INSERT INTO tmp_b23_ligas (ls_league_id,tc_id) VALUES (?,?)")) {
                            for (String id:ids) {ps.setInt(1,MAP.get(id));ps.setString(2,id);ps.addBatch();}
                            ps.executeBatch();
                        }
                        continue;
                    }
                    try(Statement st=db.createStatement()) {
                        st.setQueryTimeout(180);
                        boolean result=st.execute(q);
                        if (!result) continue;
                        try(ResultSet rs=st.getResultSet()) {
                            if(q.startsWith("SELECT @desde_madrid")) {
                                if (!rs.next() || !"OK".equals(rs.getString("preflight")))
                                    throw new IllegalStateException("Preflight invalido: fechas o zona horaria Madrid. Cancelado.");
                                System.out.println("B23_PREFLIGHT\tOK");
                            } else if(q.startsWith("SELECT g.ls_league_id")) {
                                while(rs.next()) { print("B23_ROW",rs); summaries++; }
                            } else if(q.startsWith("SELECT z.tipo_fila")) {
                                while(rs.next()) { if(details>=100000) throw new IllegalStateException("Informe >100000 filas; acota el periodo");
                                    print("B23_DETAIL",rs); details++; }
                            }
                        }
                    }
                }
                if(summaries!=ids.size()) throw new IllegalStateException("Resultado incompleto: "+summaries+" ligas de "+ids.size());
                System.out.println("B23_DONE\t"+summaries+"\t"+details+"\t"+period+"\t"+margin);
            }
        } catch(Throwable e) {
            String msg=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            if(e instanceof SQLException){ SQLException s=(SQLException)e;msg="MySQL SQLState="+s.getSQLState()+" codigo="+s.getErrorCode()+" ("+msg+")"; }
            System.err.println("B23_ERROR\t"+sanitize(msg)); System.exit(1);
        }
    }
    private static void print(String prefix,ResultSet rs) throws SQLException {
        StringBuilder row=new StringBuilder(prefix);
        int count=rs.getMetaData().getColumnCount();
        for(int i=1;i<=count;i++) row.append('\t').append(sanitize(rs.getString(i)));
        System.out.println(row);
    }
    private static void set(Connection db,String sql,Object v) throws SQLException {
        try(PreparedStatement ps=db.prepareStatement(sql)){ps.setObject(1,v);ps.executeUpdate();}
    }
    private static boolean isAllowed(String q) {
        String s=q.trim().toUpperCase(Locale.ROOT);
        return s.startsWith("SELECT ") || s.startsWith("SET @")
            || s.startsWith("DROP TEMPORARY TABLE IF EXISTS TMP_B23_")
            || s.startsWith("CREATE TEMPORARY TABLE TMP_B23_")
            || s.startsWith("ALTER TABLE TMP_B23_")
            || s.startsWith("INSERT INTO TMP_B23_LIGAS")
            || s.startsWith("DELETE P FROM TMP_B23_PARES ");
    }
    private static String loadScript() throws IOException {
        try(InputStream in=B23ReportRunner.class.getResourceAsStream("/com/footiqo/manager/b23-report.sql")) {
            if(in==null) throw new IOException("Recurso b23-report.sql no empaquetado; compila el proyecto con Maven/Refresh");
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[4096];int n;
            while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static List<String> statements(String script) {
        StringBuilder content=new StringBuilder();
        for(String line:script.split("\\R")) {
            int comment=line.indexOf("--");
            if(comment>=0)line=line.substring(0,comment);
            String t=line.trim(); if(t.isEmpty())continue;
            // En el guion no hay cadenas con punto y coma.
            content.append(line).append('\n');
        }
        List<String> parts=new ArrayList<String>();
        for(String part:content.toString().split(";")) if(!part.trim().isEmpty()) parts.add(part.trim());
        return parts;
    }
}
