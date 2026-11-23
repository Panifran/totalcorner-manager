package com.footiqo.manager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** B5.1. Archiva solo versiones modificadas de ZIP comerciales y Coverage sin borrar archivos. */
public final class B5ArchiveRunner {
    private B5ArchiveRunner() {}

    public interface Progress { void line(String msg); }

    public static final class Result {
        public int changed, unchanged, copied;
        public long bytes;
        public Path session;
        public String summary() {
            return "ARCHIVO OK | modificados="+changed+" | sin_cambios="+unchanged
                    +" | archivos_copiados="+copied+" | bytes="+bytes
                    +(session==null?" | no se necesito crear sesion":" | sesion="+session);
        }
    }

    public static Result run(Path commercial, Path archive, boolean execute, Progress progress) throws Exception {
        commercial = commercial.toAbsolutePath().normalize();
        archive = archive.toAbsolutePath().normalize();
        if (!Files.isDirectory(commercial)) throw new IOException("No existe carpeta comercial: " + commercial);
        if (archive.startsWith(commercial)) throw new IOException("La carpeta Historico no puede estar dentro de la comercial");
        if (!Files.exists(archive) && !execute) progress.line("Historico aun no creado: "+archive);
        final List<Path> files = eligible(commercial);
        if (files.isEmpty()) {
            progress.line("No hay ZIP comerciales o ficheros Coverage para archivar.");
            return new Result();
        }
        Result result = new Result();
        Map<String,Path> latest = newestCopies(archive);
        List<Path> changed = new ArrayList<Path>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            Path previous = latest.get(name);
            if (previous == null || !sha256(file).equals(sha256(previous))) {
                changed.add(file);
                result.changed++;
                result.bytes += Files.size(file);
                progress.line((execute?"ARCHIVAR ":"SIMULAR ARCHIVO ")+name);
            } else {
                result.unchanged++;
                progress.line("SIN CAMBIOS "+name);
            }
        }
        if (!execute || changed.isEmpty()) return result;
        long needed = result.bytes;
        for (Path file : changed) {
            if (file.getFileName().toString().endsWith(".zip")) {
                Path state = file.resolveSibling(file.getFileName()+".properties");
                if (Files.isRegularFile(state)) needed += Files.size(state);
            }
        }
        Files.createDirectories(archive);
        if (Files.getFileStore(archive).getUsableSpace() < needed + 1024L*1024L) {
            throw new IOException("Espacio insuficiente para archivar "+needed+" bytes (se reserva 1 MiB)");
        }
        Path session = archive.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")))
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmssSSS")));
        // Si dos procesos generan la misma marca, no sobrescribir copias anteriores.
        int attempts=0;
        while (Files.exists(session)) session=archive.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")))
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmssSSS"))+"_"+(++attempts));
        Files.createDirectories(session);
        result.session=session;
        for (Path file : changed) {
            copyVerified(file,session.resolve(file.getFileName()));result.copied++;
            if (file.getFileName().toString().endsWith(".zip")) {
                Path props = file.resolveSibling(file.getFileName()+".properties");
                if (Files.isRegularFile(props)) {
                    copyVerified(props,session.resolve(props.getFileName()));result.copied++;
                }
            }
        }
        return result;
    }

    public static String stateKey(Path commercial) throws IOException {
        if (!Files.isDirectory(commercial)) return "MISSING";
        StringBuilder b=new StringBuilder(commercial.toAbsolutePath().normalize().toString());
        for(Path file:eligible(commercial)){
            b.append('|').append(file.getFileName()).append(':').append(Files.size(file))
                    .append(':').append(Files.getLastModifiedTime(file).toMillis());
        }
        return b.toString();
    }

    private static List<Path> eligible(Path dir) throws IOException {
        List<Path> out=new ArrayList<Path>();
        try(DirectoryStream<Path> stream=Files.newDirectoryStream(dir)){
            for(Path file:stream){
                if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) continue;
                String n=file.getFileName().toString();
                if ((n.startsWith("Footiqo_Historical_Odds_") && n.endsWith(".zip")) ||
                        n.equals("Footiqo_Historical_Odds_Coverage.xlsx") ||
                        n.equals("Footiqo_Historical_Odds_Coverage_Detail.csv") ||
                        n.equals("Footiqo_Historical_Odds_Coverage_File_Check.csv")) out.add(file);
            }
        }
        out.sort(Comparator.comparing(a -> a.getFileName().toString()));
        return out;
    }

    private static Map<String,Path> newestCopies(Path archive) throws IOException {
        Map<String,Path> result=new HashMap<String,Path>();
        if (!Files.isDirectory(archive))return result;
        // Carpetas ordenadas cronologicamente, solo dos niveles dia/sesion.
        List<Path> sessions=new ArrayList<Path>();
        try(DirectoryStream<Path> days=Files.newDirectoryStream(archive)) {
            for(Path day:days) {
                if (!Files.isDirectory(day) || !day.getFileName().toString().matches("\\d{8}"))continue;
                try(DirectoryStream<Path> folders=Files.newDirectoryStream(day)){
                    for(Path folder:folders) if(Files.isDirectory(folder))sessions.add(folder);
                }
            }
        }
        sessions.sort(Comparator.comparing(Path::toString));
        for(Path folder:sessions)try(DirectoryStream<Path> files=Files.newDirectoryStream(folder)){
            for(Path file:files)if(Files.isRegularFile(file) && !Files.isSymbolicLink(file)) {
                String n=file.getFileName().toString();
                if(n.endsWith(".zip") || n.startsWith("Footiqo_Historical_Odds_Coverage"))result.put(n,file);
            }
        }
        return result;
    }

    private static void copyVerified(Path source,Path dest)throws Exception {
        Path temp=dest.resolveSibling(dest.getFileName()+".partial");
        try {
            Files.copy(source,temp,StandardCopyOption.COPY_ATTRIBUTES);
            if(!sha256(source).equals(sha256(temp)))throw new IOException("Copia no coincide con origen: "+source);
            Files.move(temp,dest,StandardCopyOption.ATOMIC_MOVE);
        } catch(AtomicMoveNotSupportedException ex){Files.move(temp,dest);} finally {Files.deleteIfExists(temp);}
    }

    private static String sha256(Path file)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=Files.newInputStream(file)) {
            byte[] buf=new byte[65536];int n;
            while((n=input.read(buf))!=-1)digest.update(buf,0,n);
        }
        StringBuilder hex=new StringBuilder();
        for(byte b:digest.digest())hex.append(String.format("%02x",b&255));
        return hex.toString();
    }

    public static void main(String[] args) throws Exception {
        if(args.length<2)throw new IllegalArgumentException("Uso: <carpeta-comercial> <carpeta-historico> [--execute]");
        Result r=run(Paths.get(args[0]),Paths.get(args[1]),Arrays.asList(args).contains("--execute"),System.out::println);
        System.out.println(r.summary());
    }
}
