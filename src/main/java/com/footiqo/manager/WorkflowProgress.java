package com.footiqo.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/** Evidencias locales por ciclo y seleccion de ligas; nunca deduce finalizacion a partir de una simulacion. */
public final class WorkflowProgress {
    public static final String DONE="DONE", SKIP="SKIP", REVIEW="REVIEW";
    private final Properties data=new Properties();
    private final Path store;
    private final java.util.function.Supplier<String> cycle, leagues;

    public WorkflowProgress(java.util.function.Supplier<String> cycle,java.util.function.Supplier<String> leagues){
        this(cycle,leagues,Paths.get(System.getProperty("user.home"),".totalcorner-manager-workflow.properties"));
    }
    WorkflowProgress(java.util.function.Supplier<String> cycle,java.util.function.Supplier<String> leagues,Path store){
        this.cycle=cycle;this.leagues=leagues;this.store=store;
        if(Files.isRegularFile(store))try(InputStream in=Files.newInputStream(store)){data.load(in);}catch(IOException ignored){}
    }
    private static String normalized(String ids){
        SortedSet<String> parts=new TreeSet<String>();
        for(String v:ids.split(",")){String s=v.trim();if(!s.isEmpty())parts.add(s);}
        return String.join(",",parts);
    }
    private String context(){
        String value=cycle.get().trim()+"|"+normalized(leagues.get());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private String prefix(String stage){return "context."+context()+"."+stage+".";}
    public synchronized void mark(String stage,String state,String detail){
        if(!Arrays.asList(DONE,SKIP,REVIEW).contains(state))throw new IllegalArgumentException("Invalid state");
        String key=prefix(stage);
        data.setProperty(key+"state",state);
        data.setProperty(key+"detail",detail==null?"":detail);
        data.setProperty(key+"when",LocalDateTime.now().toString());
        try{Path parent=store.toAbsolutePath().getParent();if(parent!=null)Files.createDirectories(parent);
            try(OutputStream out=Files.newOutputStream(store)){data.store(out,"TotalCorner Manager workflow confirmations by cycle/league selection; no passwords");}
        }catch(IOException ignored){} // visualiza la evidencia en esta sesion aunque falle persistencia
    }
    public synchronized String state(String stage){return data.getProperty(prefix(stage)+"state","");}
    public synchronized String detail(String stage){return data.getProperty(prefix(stage)+"detail","");}
    public synchronized String when(String stage){return data.getProperty(prefix(stage)+"when","");}
    public String contextLabel(){return cycle.get().trim()+" | TC="+normalized(leagues.get());}
    public static void selfTest()throws Exception{
        Path file=Files.createTempFile("workflow-test-",".properties");
        try {
            final String[] cycle={"2026-11-20"};final String[] leagues={"1,2"};
            WorkflowProgress w=new WorkflowProgress(()->cycle[0],()->leagues[0],file);
            w.mark("B1.1",DONE,"Consultado");
            if(!DONE.equals(w.state("B1.1")))throw new AssertionError("mark");
            leagues[0]="2,1";
            if(!DONE.equals(w.state("B1.1")))throw new AssertionError("sorting");
            cycle[0]="2026-11-21";
            if(!w.state("B1.1").isEmpty())throw new AssertionError("cycle isolation");
            cycle[0]="2026-11-20";
            if(!DONE.equals(new WorkflowProgress(()->cycle[0],()->leagues[0],file).state("B1.1")))throw new AssertionError("persist");
            System.out.println("OK: workflow confirmations per cycle, isolated by league selection");
        } finally{Files.deleteIfExists(file);}
    }
}
