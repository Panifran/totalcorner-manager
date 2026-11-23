package com.footiqo.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Validacion independiente de los tres artefactos Coverage, sin Excel/POI ni MySQL. */
public final class B6ReportVerifier {
    public static final String PREFIX = "Footiqo_Historical_Odds_Coverage";
    private static final Pattern SEASON_ZIP=Pattern.compile("\\d{4}(?:_\\d{4})?\\.zip");
    private B6ReportVerifier() { }

    public static final class Result {
        public final int leagues, leagueSeasons;
        public final long matches, quoteRows;
        public final String fingerprint;
        Result(int a,int b,long c,long d,String fp) {
            leagues=a;leagueSeasons=b;matches=c;quoteRows=d;fingerprint=fp;
        }
        public String summary() {
            return "Coverage VERIFICADO | ligas="+leagues+" | liga-temporadas="+leagueSeasons+
                " | partidos="+matches+" | cuotas="+quoteRows;
        }
    }

    public static Result verify(Path dir, Path source, Set<Integer> publishedIds) throws Exception {
        if(publishedIds==null||publishedIds.isEmpty())throw new IOException("No hay ligas publicadas");
        Path excel=dir.resolve(PREFIX+".xlsx");
        Path detail=dir.resolve(PREFIX+"_Detail.csv");
        Path audit=dir.resolve(PREFIX+"_File_Check.csv");
        for(Path p:new Path[]{excel,detail,audit}) {
            if(!Files.isRegularFile(p)||Files.size(p)==0)throw new IOException("Falta un informe Coverage no vacio: "+p);
        }
        Set<Path> expected=new HashSet<Path>();
        for(Integer id:publishedIds) {
            boolean found=false;
            try(DirectoryStream<Path> dirs=Files.newDirectoryStream(source,id+"_*")) {
                for(Path folder:dirs)if(Files.isDirectory(folder)) {
                    try(DirectoryStream<Path> zips=Files.newDirectoryStream(folder,"*.zip")) {
                        for(Path zip:zips)if(SEASON_ZIP.matcher(zip.getFileName().toString()).matches()) {
                            expected.add(zip.toAbsolutePath().normalize());found=true;
                        }
                    }
                }
            }
            if(!found)throw new IOException("No hay ZIP de temporada para liga publicada LS "+id);
        }
        Set<Integer> seenLeagues=new HashSet<Integer>();
        Set<Path> seenZips=new HashSet<Path>();
        long matches=0, odds=0;
        boolean total=false;
        int seasons=0;
        try(BufferedReader in=Files.newBufferedReader(audit,StandardCharsets.UTF_8)) {
            String line=in.readLine();
            if(line==null||!line.startsWith("league_id,league,season,source_zip,"))
                throw new IOException("Cabecera incorrecta en Coverage_File_Check.csv");
            while((line=in.readLine())!=null) {
                if(line.trim().isEmpty())continue;
                List<String> cols=csv(line);
                if(cols.size()!=9)throw new IOException("Fila de auditoria incorrecta: "+line);
                if(!"OK".equals(cols.get(8)))throw new IOException("Auditoria Coverage con status distinto de OK");
                if(cols.get(0).trim().isEmpty()) {
                    if(total||!"TOTAL_UNIQUE_MATCH_IDS".equals(cols.get(1)))throw new IOException("TOTAL de auditoria incorrecto");
                    if(Long.parseLong(cols.get(4))!=matches||Long.parseLong(cols.get(7))!=odds)
                        throw new IOException("TOTAL no coincide con los datos de Coverage");
                    total=true;continue;
                }
                if(total)throw new IOException("Filas posteriores al TOTAL de Coverage");
                int id=Integer.parseInt(cols.get(0));
                if(!publishedIds.contains(id))throw new IOException("Coverage incluye liga NO publicada: "+id);
                Path zip=Paths.get(cols.get(3)).toAbsolutePath().normalize();
                if(!expected.contains(zip))throw new IOException("ZIP ajeno a la seleccion comercial: "+zip);
                if(!seenZips.add(zip))throw new IOException("ZIP duplicado en auditoria: "+zip);
                long n=Long.parseLong(cols.get(4));
                long with=Long.parseLong(cols.get(5)),without=Long.parseLong(cols.get(6));
                long quotes=Long.parseLong(cols.get(7));
                if(n<=0||with<0||without<0||quotes<0||with+without!=n)
                    throw new IOException("Recuentos inconsistentes en "+zip.getFileName());
                seenLeagues.add(id);seasons++;matches+=n;odds+=quotes;
            }
        }
        if(!total)throw new IOException("Falta fila TOTAL en auditoria Coverage");
        if(!seenLeagues.equals(publishedIds))throw new IOException("Faltan ligas publicadas en informe: "+missing(publishedIds,seenLeagues));
        if(!seenZips.equals(expected))throw new IOException("Faltan ZIP de temporada en Coverage: "+missing(expected,seenZips));
        if(matches<=0||odds<=0)throw new IOException("Coverage no contiene partidos o cuotas");
        try(BufferedReader in=Files.newBufferedReader(detail,StandardCharsets.UTF_8)) {
            String header=in.readLine();
            if(header==null||!header.startsWith("league_id,league,season,bookmaker,market,period,"))
                throw new IOException("Cabecera incorrecta en Coverage_Detail.csv");
            if(in.readLine()==null)throw new IOException("Coverage_Detail.csv carece de datos");
        }
        try(ZipFile file=new ZipFile(excel.toFile())) {
            if(file.getEntry("xl/styles.xml")==null)throw new IOException("XLSX sin estilos");
            for(int n=1;n<=4;n++)if(file.getEntry("xl/worksheets/sheet"+n+".xml")==null)
                throw new IOException("XLSX sin hoja "+n);
            ZipEntry workbook=file.getEntry("xl/workbook.xml");
            if(workbook==null)throw new IOException("XLSX sin workbook");
            String xml;
            try(InputStream in=file.getInputStream(workbook)) {xml=readText(in,128*1024);}
            for(String name:new String[]{"Overview","Coverage_Matrix","Open_Close_Detail","Legend"})
                if(!xml.contains("name=\""+name+"\""))throw new IOException("XLSX sin hoja "+name);
        }
        String fingerprint=sha(excel)+"|"+sha(detail)+"|"+sha(audit);
        return new Result(seenLeagues.size(),seasons,matches,odds,fingerprint);
    }
    private static <T> Set<T> missing(Set<T> expected,Set<T> seen) {
        Set<T> result=new HashSet<T>(expected);result.removeAll(seen);return result;
    }
    private static String readText(InputStream in,int max)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
        while((n=in.read(buf))!=-1) { if(out.size()+n>max)throw new IOException("workbook.xml inesperadamente grande");out.write(buf,0,n); }
        return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
    private static List<String> csv(String line)throws IOException {
        List<String> out=new ArrayList<String>();StringBuilder v=new StringBuilder();boolean quote=false;
        for(int i=0;i<line.length();i++) {
            char c=line.charAt(i);
            if(c=='"') {
                if(quote&&i+1<line.length()&&line.charAt(i+1)=='"'){v.append('"');i++;}
                else quote=!quote;
            } else if(c==','&&!quote){out.add(v.toString());v.setLength(0);}
            else v.append(c);
        }
        if(quote)throw new IOException("CSV con comillas sin cerrar");
        out.add(v.toString());return out;
    }
    private static String sha(Path file)throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;
        try(InputStream in=Files.newInputStream(file)) {while((n=in.read(b))!=-1)d.update(b,0,n);}
        StringBuilder sb=new StringBuilder();for(byte x:d.digest())sb.append(String.format("%02x",x&255));return sb.toString();
    }
}
