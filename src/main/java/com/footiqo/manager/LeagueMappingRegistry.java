package com.footiqo.manager;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Correspondencias LS (referencia) -> N competiciones TC.
 * Se guarda fuera del proyecto y NO modifica totalcorner-history.properties.
 * La activacion para el Bloque 2 es explicita e independiente de publicar datasets.
 */
public final class LeagueMappingRegistry {
    public static final class Association {
        public final String tcId, lsId, name;
        public final boolean updates;
        Association(String tc, String ls, String name, boolean updates) {
            this.tcId=tc;this.lsId=ls;this.name=name;this.updates=updates;
        }
    }
    public static final Path FILE=Paths.get(System.getProperty("user.home"),".totalcorner-manager-tc-ls-mappings.properties");
    private static final String[][] DEFAULTS={
        {"1","2","England Premier League"},{"14","1","Spain La Liga"},
        {"12","3","Italy Serie A"},{"16","4","Germany Bundesliga I"},
        {"25","5","France Ligue 1"},{"137","18","UEFA Champions League"},
        {"138","28","UEFA Europa League"},{"2","6","England Championship"}
    };
    private LeagueMappingRegistry(){}
    public static boolean isBaseline(String tc){for(String[] row:DEFAULTS)if(row[0].equals(tc))return true;return false;}
    private static String valid(String id){
        if(id==null||!id.matches("[1-9][0-9]{0,10}"))throw new IllegalArgumentException("ID numerico invalido: "+id);
        return id;
    }
    public static synchronized Map<String,Association> load() {
        LinkedHashMap<String,Association> result=new LinkedHashMap<String,Association>();
        for(String[] row:DEFAULTS)result.put(row[0],new Association(row[0],row[1],row[2],true));
        if(Files.isRegularFile(FILE)) {
            Properties p=new Properties();
            try(InputStream in=Files.newInputStream(FILE)){p.load(in);}catch(IOException ex){throw new IllegalStateException("No se pudo leer "+FILE+": "+ex.getMessage(),ex);}
            for(String raw:p.getProperty("tc.ids","").split(",")){
                String tc=raw.trim();if(tc.isEmpty())continue;valid(tc);
                String ls=valid(p.getProperty("tc."+tc+".ls",""));
                String nm=p.getProperty("tc."+tc+".name", "Liga TC "+tc).trim();
                if(nm.isEmpty()||nm.length()>120)throw new IllegalStateException("Nombre invalido para TC="+tc);
                boolean enabled=Boolean.parseBoolean(p.getProperty("tc."+tc+".updates","false"));
                Association previous=result.get(tc);
                if(previous!=null&&!previous.lsId.equals(ls))throw new IllegalStateException("TC="+tc+" no puede pertenecer a LS="+ls+" y LS="+previous.lsId);
                result.put(tc,new Association(tc,ls,nm,enabled));
            }
        }
        return Collections.unmodifiableMap(result);
    }
    public static synchronized void save(String tc,String ls,String name,boolean updates) throws IOException {
        valid(tc);valid(ls);String normalized=name==null?"":name.trim();
        if(normalized.isEmpty()||normalized.length()>120||normalized.indexOf('\n')>=0||normalized.indexOf('\r')>=0)
            throw new IOException("Nombre de competicion no valido");
        Map<String,Association> current=load();Association previous=current.get(tc);
        if(previous!=null&&!previous.lsId.equals(ls))throw new IOException("TC="+tc+" ya esta asociado a LS="+previous.lsId+". No se puede cambiar sin migracion auditada.");
        Properties p=new Properties();
        if(Files.isRegularFile(FILE))try(InputStream in=Files.newInputStream(FILE)){p.load(in);}
        LinkedHashSet<String> ids=new LinkedHashSet<String>();
        for(String id:p.getProperty("tc.ids","").split(","))if(!id.trim().isEmpty())ids.add(id.trim());
        ids.add(tc);p.setProperty("tc.ids",String.join(",",ids));
        p.setProperty("tc."+tc+".ls",ls);p.setProperty("tc."+tc+".name",normalized);
        p.setProperty("tc."+tc+".updates",Boolean.toString(updates));
        Path tmp=Files.createTempFile(FILE.getParent(),"tc-ls-mapping-",".tmp");
        try {
            try(OutputStream out=Files.newOutputStream(tmp)){p.store(out,"TotalCorner Manager: LS 1:N TC | updates no implica publicacion");}
            try{Files.move(tmp,FILE,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException ex){Files.move(tmp,FILE,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(tmp);}
    }
    public static String tcIdsForLs(String ls){
        StringJoiner j=new StringJoiner(",");for(Association a:load().values())if(a.lsId.equals(ls))j.add(a.tcId);return j.toString();
    }
    public static String sqlCteRows(){
        StringBuilder sql=new StringBuilder();boolean first=true;
        for(Association a:load().values()){
            if(!first)sql.append(" UNION ALL ");first=false;
            sql.append("SELECT '").append(a.tcId).append("' AS tc_id,").append(a.lsId)
                .append(" AS ls_league_id,'").append(a.name.replace("'","''")).append("' AS liga");
        }
        return sql.toString();
    }
    public static String sqlCase(String column){
        StringBuilder sql=new StringBuilder("(CASE ").append(column);
        for(Association a:load().values())sql.append(" WHEN '").append(a.tcId).append("' THEN ").append(a.lsId);
        return sql.append(" ELSE NULL END)").toString();
    }
    public static String sqlMembership(String lsColumn,String tcColumn){
        StringBuilder sql=new StringBuilder("(");boolean first=true;
        for(Association a:load().values()){
            if(!first)sql.append(" OR ");first=false;
            sql.append('(').append(lsColumn).append('=').append(a.lsId)
                .append(" AND ").append(tcColumn).append(" COLLATE utf8mb4_unicode_ci='")
                .append(a.tcId).append("' COLLATE utf8mb4_unicode_ci)");
        }
        return sql.append(')').toString();
    }
    public static void augmentSelected(Properties p,String csv) throws IOException {
        Map<String,Association> known=load();
        for(String id:csv.split(",")){
            Association a=known.get(id);
            if(a==null)throw new IOException("TC="+id+" no figura en el registro de correspondencias");
            if(!a.updates)throw new IOException("TC="+id+" aun no esta activada en Bloque 2");
            p.setProperty("league."+id+".name",a.name);
            if(p.getProperty("league."+id+".priority")==null)p.setProperty("league."+id+".priority","100");
        }
        p.setProperty("league.ids",csv);
    }
    public static void selfTest() throws Exception {
        Map<String,Association> a=load();
        if(!"2".equals(a.get("1").lsId)||!"1".equals(a.get("14").lsId)||!sqlMembership("s.league_id","l.tc_league_id").contains("s.league_id=2"))
            throw new AssertionError("Mapping baseline invalido");
        System.out.println("LEAGUE_MAPPING_SELF_TEST_OK "+a.size());
    }
    public static void main(String[] args)throws Exception{if(args.length==1&&"--self-test".equals(args[0]))selfTest();else throw new IllegalArgumentException("--self-test");}
}
