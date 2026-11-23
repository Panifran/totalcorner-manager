package com.footiqo.manager;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Verifica que los Bundles contengan EXCLUSIVAMENTE las ligas Footiqo aprobadas. */
public final class B5PackageVerifier {
    private B5PackageVerifier() {}
    public static String verify(Path exports, Path commercial, Set<Integer> publishedIds,
                                Set<String> currentSeasons) throws IOException {
        if(publishedIds.isEmpty())throw new IOException("No hay ligas publicadas");
        Map<String,Integer> allowed=new HashMap<String,Integer>();
        for(Integer id:publishedIds) {
            String prefix=id+"_";
            int found=0;
            try(DirectoryStream<Path> all=Files.newDirectoryStream(exports)) {
                for(Path p:all)if(Files.isDirectory(p)&&p.getFileName().toString().startsWith(prefix)) {
                    String slug=p.getFileName().toString().substring(prefix.length());
                    if(allowed.put(slug,id)!=null)throw new IOException("Nombre repetido en ligas: "+slug);
                    found++;
                }
            }
            if(found!=1)throw new IOException("La liga Footiqo "+id+" debe tener exactamente una carpeta de exportacion (encontradas="+found+")");
        }
        String[] types={"Historical","Current"};
        for(String kind:types) {
            Path path=commercial.resolve("Footiqo_Historical_Odds_Bundle_"+kind+".zip");
            if(!Files.isRegularFile(path))throw new IOException("Falta bundle comercial: "+path);
            Set<String> seen=new HashSet<String>();
            try(ZipFile zip=new ZipFile(path.toFile())) {
                Enumeration<? extends ZipEntry> entries=zip.entries();
                while(entries.hasMoreElements()) {
                    ZipEntry entry=entries.nextElement();
                    String name=entry.getName();
                    if(entry.isDirectory())continue;
                    String[] segments=name.split("/",-1);
                    if(segments.length!=2 || !allowed.containsKey(segments[0]) ||
                            !segments[1].matches("\\d{4}(?:_\\d{4})?\\.zip"))
                        throw new IOException("Contenido no autorizado en Bundle_"+kind+": "+name);
                    boolean isCurrent=currentSeasons.contains(segments[1].substring(0,segments[1].length()-4));
                    if(isCurrent!=kind.equals("Current"))throw new IOException("Temporada mal clasificada: "+name);
                    Path original=exports.resolve(allowed.get(segments[0])+"_"+segments[0]).resolve(segments[1]);
                    if(!Files.isRegularFile(original))throw new IOException("ZIP fuente no encontrado: "+original);
                    seen.add(segments[0]);
                }
            }
            if(!seen.equals(allowed.keySet()))
                throw new IOException("Bundle_"+kind+" incompleto: esperado "+allowed.keySet()+", contiene "+seen);
        }
        return "BUNDLES VERIFICADOS: Historical y Current solo contienen "+publishedIds.size()+" ligas publicadas";
    }
}
