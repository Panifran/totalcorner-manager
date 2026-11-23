package com.footiqo.manager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/** B6: proceso Java 17 ajeno a Manager. Nunca publica en la web. */
public final class B6Panel extends JPanel {
    private static final long serialVersionUID=1L;
    private final Supplier<Properties> b5Settings;
    private final Supplier<Boolean> managerBusy;
    private final Consumer<Boolean> setManagerBusy;
    private final JLabel config=new JLabel("Usa las rutas e IDs PUBLICADOS de B5. Temporadas: TODAS las exportadas.");
    private final JLabel reviewPath=new JLabel("Revision: [se obtiene de la carpeta comercial de B5]");
    private final JTextArea console=new JTextArea(20,100);
    private final JLabel summary=new JLabel("Paso 1: verificar configuracion B6 y los ZIP disponibles.");
    private final JLabel blockedReasons=new JLabel("Estado B6: verificar la configuracion.");
    private final JCheckBox reviewed=new JCheckBox("He revisado Overview, Open_Close_Detail y File_Check en coverage-review");
    private final JButton check=new JButton("1. Verificar Java 17 y JAR");
    private final JButton simulate=new JButton("2. Simular Coverage");
    private final JButton buildReview=new JButton("3. Generar revision");
    private final JButton validateReview=new JButton("4. Validar revision");
    private final JButton archiveOld=new JButton("5. Archivar version anterior");
    private final JButton publishLocal=new JButton("6. Generar Coverage comercial");
    private final JButton archiveNew=new JButton("7. Archivar nueva version");
    private final JButton stop=new JButton("Detener B6");
    private volatile Process child;
    private volatile boolean ownBusy,otherBusy,cancelled;
    private String simulatedKey="",generatedKey="",validatedKey="",reviewFingerprint="",archivedState="",commercialDoneKey="";
    private Consumer<String> onCompletion=message -> {};
    public void setOnCompletion(Consumer<String> listener){onCompletion=listener==null?message -> {}:listener;}

    public B6Panel(Supplier<Properties> b5Settings,Supplier<Boolean> managerBusy,Consumer<Boolean> setManagerBusy) {
        super(new BorderLayout(5,6));
        this.b5Settings=b5Settings;this.managerBusy=managerBusy;this.setManagerBusy=setManagerBusy;
        setBorder(new EmptyBorder(8,4,6,4));
        JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
        JLabel title=new JLabel("B6 - Actualizar Coverage publico multiliga (Java 17)");
        title.setFont(title.getFont().deriveFont(Font.BOLD,16f));top.add(title);
        top.add(new JLabel("Lee ZIP por liga/temporada (NO bundles), no consulta MySQL ni TotalCorner. Sin subir ficheros a Footiqo."));
        top.add(config);top.add(reviewPath);
        JPanel row1=new JPanel(new FlowLayout(FlowLayout.LEFT,5,3));
        for(JButton b:new JButton[]{check,simulate,buildReview,validateReview})row1.add(b);
        JPanel row2=new JPanel(new FlowLayout(FlowLayout.LEFT,5,3));
        for(JButton b:new JButton[]{archiveOld,publishLocal,archiveNew,stop})row2.add(b);
        top.add(row1);top.add(reviewed);top.add(row2);top.add(blockedReasons);
        add(top,BorderLayout.NORTH);
        console.setEditable(false);console.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));
        add(new JScrollPane(console),BorderLayout.CENTER);
        summary.setBorder(new EmptyBorder(6,1,6,1));add(summary,BorderLayout.SOUTH);
        check.addActionListener(e->preflight());
        simulate.addActionListener(e->runGenerator(false,false));
        buildReview.addActionListener(e->runGenerator(true,false));
        validateReview.addActionListener(e->validateReviewFiles());
        archiveOld.addActionListener(e->archive(false));
        publishLocal.addActionListener(e->runGenerator(true,true));
        archiveNew.addActionListener(e->archive(true));
        stop.addActionListener(e->cancel());
        reviewed.addActionListener(e->updateButtons());
        updateButtons();
    }

    public void setExternalBusy(boolean v){otherBusy=v;updateButtons();}
    private void updateButtons() {
        boolean ready=!ownBusy&&!otherBusy;
        String busyReason=ownBusy?"Hay un proceso B6 en ejecucion.":"Hay otro proceso en Manager en ejecucion.";
        String key=safeKey();
        boolean simDone=!key.isEmpty()&&key.equals(simulatedKey);
        boolean generated=!key.isEmpty()&&key.equals(generatedKey);
        boolean validated=!key.isEmpty()&&key.equals(validatedKey);
        boolean archiveOldDone=!archivedState.isEmpty()&&archivedState.equals(safeArchiveKey());
        boolean commercialDone=!key.isEmpty()&&key.equals(commercialDoneKey);
        ManagerUiSupport.gate(check,ready,busyReason);
        ManagerUiSupport.gate(simulate,ready,busyReason);
        ManagerUiSupport.gate(buildReview,ready&&simDone,!ready?busyReason:"Falta 2. Simular Coverage con esta configuracion.");
        ManagerUiSupport.gate(validateReview,ready&&generated,!ready?busyReason:"Falta 3. Generar revision para la configuracion actual.");
        ManagerUiSupport.gate(archiveOld,ready&&validated&&reviewed.isSelected(),!ready?busyReason:
            !validated?"Falta 4. Validar revision.":"Marca 'He revisado' despues de comprobar los informes.");
        ManagerUiSupport.gate(publishLocal,ready&&validated&&reviewed.isSelected()&&archiveOldDone,!ready?busyReason:
            !validated?"Falta 4. Validar revision.":!reviewed.isSelected()?"Marca la casilla de revision manual.":
            "Falta 5. Archivar version anterior de Coverage.");
        ManagerUiSupport.gate(archiveNew,ready&&commercialDone,!ready?busyReason:"Falta 6. Generar Coverage comercial.");
        ManagerUiSupport.gate(stop,ownBusy,"No hay un proceso B6 que detener.");
        String next=!simDone?"Simular Coverage":!generated?"Generar revision":!validated?"Validar revision":
            !reviewed.isSelected()?"Marcar revision manual":!archiveOldDone?"Archivar version anterior":
            !commercialDone?"Generar Coverage comercial":"Archivar nueva version";
        blockedReasons.setText("Siguiente paso habilitable B6: "+next+". Los botones bloqueados muestran el motivo al pasar el raton.");
        try {
            Config c=snapshot();
            config.setText("Ligas comerciales (IDs Footiqo): "+c.idsText+"  |  Temporadas: ALL  |  Java 17 configurado en B5");
            reviewPath.setText("Carpeta revision: "+c.review);
        }catch(Exception ignored){}
    }
    private void warn(String msg) {
        JOptionPane.showMessageDialog(this,msg,"TotalCorner Manager B6",JOptionPane.WARNING_MESSAGE);
        summary.setText(msg);
    }
    private static final class Config {
        final Path project,java17,source,commercial,archive,review;
        final Set<Integer> ids;
        final String idsText;
        Config(Properties p)throws IOException {
            project=required(p,"project");java17=required(p,"java17");source=required(p,"exports");
            commercial=required(p,"commercial");archive=required(p,"archive");
            idsText=p.getProperty("publishedIds","").trim();
            ids=parseIds(idsText);review=commercial.resolve("coverage-review");
            if(!project.resolve("pom.xml").toFile().isFile())throw new IOException("Indica el proyecto IntelliJ en B5");
        }
        static Path required(Properties p,String k)throws IOException {
            String value=p.getProperty(k,"").trim();
            if(value.isEmpty())throw new IOException("Falta configurar "+k+" en B5");
            return Paths.get(value).toAbsolutePath().normalize();
        }
        static Set<Integer> parseIds(String s)throws IOException {
            Set<Integer> out=new LinkedHashSet<Integer>();
            if(s.isEmpty()||s.toUpperCase(Locale.ROOT).contains("ALL"))throw new IOException("Usa IDs de ligas publicadas explicitos en B5, nunca ALL");
            for(String part:s.split(",",-1)) {
                if(!part.trim().matches("\\d+"))throw new IOException("ID Footiqo incorrecto: "+part);
                int id=Integer.parseInt(part.trim());
                if(id<1||!out.add(id))throw new IOException("ID repetido o incorrecto: "+id);
            }
            if(out.isEmpty())throw new IOException("No hay ligas publicadas");
            return out;
        }
    }
    private Config snapshot()throws IOException {return new Config(b5Settings.get());}
    private String key(Config c)throws IOException {
        Path jar=c.project.resolve("target/corners-cards-simulator-1.0.0.jar");
        StringBuilder s=new StringBuilder();
        s.append(c.project).append('|').append(c.java17).append('|').append(c.source).append('|')
            .append(c.commercial).append('|').append(c.idsText).append('|');
        if(Files.isRegularFile(jar))s.append(Files.size(jar)).append(':').append(Files.getLastModifiedTime(jar).toMillis());
        List<Path> files=new ArrayList<Path>();
        if(Files.isDirectory(c.source))for(Integer id:c.ids) {
            try(DirectoryStream<Path> dirs=Files.newDirectoryStream(c.source,id+"_*")) {
                for(Path d:dirs)if(Files.isDirectory(d))try(DirectoryStream<Path> zips=Files.newDirectoryStream(d,"*.zip")){
                    for(Path z:zips)if(z.getFileName().toString().matches("\\d{4}(?:_\\d{4})?\\.zip"))files.add(z);
                }
            }
        }
        files.sort(Comparator.comparing(Path::toString));
        for(Path f:files)s.append('|').append(f).append(':').append(Files.size(f))
            .append(':').append(Files.getLastModifiedTime(f).toMillis());
        return s.toString();
    }
    private String safeKey(){try{Config c=snapshot();return key(c);}catch(Exception ex){return "NOT_READY";}}
    private String safeArchiveKey(){try{return B5ArchiveRunner.stateKey(snapshot().commercial);}catch(Exception ex){return "NOT_READY";}}
    private Path jar(Config c)throws IOException {
        Path target=c.project.resolve("target/corners-cards-simulator-1.0.0.jar");
        if(!Files.isRegularFile(target))throw new IOException("Falta JAR de IntelliJ. Ejecuta Maven > Lifecycle > package");
        try(JarFile f=new JarFile(target.toFile())) {
            if(f.getEntry("HistoricalOddsCoverageGenerator.class")==null)
                throw new IOException("JAR sin HistoricalOddsCoverageGenerator.class. Ejecuta Maven > package en IntelliJ");
        }
        Path source=c.project.resolve("src/main/java/HistoricalOddsCoverageGenerator.java");
        if(!Files.isRegularFile(source))throw new IOException("Falta HistoricalOddsCoverageGenerator.java en IntelliJ");
        if(Files.getLastModifiedTime(source).toMillis()>Files.getLastModifiedTime(target).toMillis()+2000)
            throw new IOException("JAR anterior al CoverageGenerator. Recompila con Maven > package");
        return target;
    }
    private void verify(Config c)throws Exception {
        if(!Files.isRegularFile(c.java17))throw new IOException("Selecciona java.exe de Java 17 en B5");
        Process p=new ProcessBuilder(c.java17.toString(),"-version").redirectErrorStream(true).start();
        if(!p.waitFor(10,TimeUnit.SECONDS)) {p.destroyForcibly();throw new IOException("Java 17 no responde");}
        StringBuilder version=new StringBuilder();
        try(BufferedReader br=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))){
            String line;while((line=br.readLine())!=null)version.append(line);
        }
        if(p.exitValue()!=0||!Pattern.compile("version \\\"17(?:\\.|\\\")").matcher(version).find())
            throw new IOException("B6 requiere Java 17; detectado: "+version);
        jar(c);
        if(!Files.isDirectory(c.source))throw new IOException("No existe carpeta ZIP de temporadas: "+c.source);
        if(!Files.isDirectory(c.commercial))throw new IOException("No existe carpeta comercial: "+c.commercial);
        if(c.archive.startsWith(c.commercial))throw new IOException("Historico no debe estar dentro de la carpeta comercial");
        for(Integer id:c.ids) {
            boolean found=false;
            try(DirectoryStream<Path> dirs=Files.newDirectoryStream(c.source,id+"_*")) {
                for(Path dir:dirs)if(Files.isDirectory(dir))try(DirectoryStream<Path> zips=Files.newDirectoryStream(dir,"*.zip")) {
                    for(Path file:zips)if(file.getFileName().toString().matches("\\d{4}(?:_\\d{4})?\\.zip"))found=true;
                }
            }
            if(!found)throw new IOException("La liga publicada "+id+" no tiene ZIP por temporada");
        }
    }
    private void preflight() {
        if(ownBusy||managerBusy.get())return;
        try {Config c=snapshot();verify(c);append("B6 VERIFICACION OK: Java 17, JAR Coverage, "+c.ids.size()+" ligas publicadas y rutas.");
            summary.setText("Configuracion correcta. Siguiente: simular Coverage (solo lectura).");
        }catch(Exception ex){warn(ex.getMessage());}updateButtons();
    }
    private void begin(){ownBusy=true;cancelled=false;setManagerBusy.accept(true);updateButtons();}
    private void end(){ownBusy=false;child=null;setManagerBusy.accept(false);updateButtons();}
    private void append(String s){console.append(s+"\n");if(console.getDocument().getLength()>250000)console.replaceRange("[Registro abreviado. Log completo en disco.]\n",0,75000);console.setCaretPosition(console.getDocument().getLength());}
    private Path log(Config c,String name)throws IOException {
        Path folder=c.project.resolve("target/totalcorner-manager-logs");Files.createDirectories(folder);
        return folder.resolve("B6-"+name+"-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS"))+".log");
    }
    private void runGenerator(final boolean execute,final boolean commercialOutput) {
        if(ownBusy||managerBusy.get())return;
        final Config c;final String runKey;final Path log;final List<String> cmd=new ArrayList<String>();
        try {
            c=snapshot();verify(c);runKey=key(c);
            if(execute&&!commercialOutput&&!runKey.equals(simulatedKey))throw new IOException("Primero simula B6 con las mismas ligas y ZIP");
            if(commercialOutput) {
                if(!runKey.equals(validatedKey)||!reviewed.isSelected())throw new IOException("Primero valida y revisa los informes de coverage-review");
                if(!archivedState.equals(B5ArchiveRunner.stateKey(c.commercial)))throw new IOException("Primero archiva la version comercial anterior (B5.1)");
                B6ReportVerifier.Result review=B6ReportVerifier.verify(c.review,c.source,c.ids);
                if(!reviewFingerprint.equals(review.fingerprint))throw new IOException("La revision ha cambiado: repite validacion");
                if(JOptionPane.showConfirmDialog(this,"Se sustituiran los 3 informes Coverage en:\n"+c.commercial+
                    "\nLigas publicadas (Footiqo): "+c.idsText+"\nSin cambios en ZIP comerciales ni subida a la web. ¿Continuar?",
                    "Confirmar B6",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.YES_OPTION)return;
            }
            log=log(c,commercialOutput?"comercial":(execute?"revision":"simulacion"));
            cmd.add(c.java17.toString());cmd.add("-Dfile.encoding=UTF-8");cmd.add("-cp");cmd.add(jar(c).toString());
            cmd.add("HistoricalOddsCoverageGenerator");cmd.add("--league-ids="+c.idsText);
            cmd.add("--seasons=ALL");cmd.add("--source="+c.source);
            cmd.add("--output="+(commercialOutput?c.commercial:c.review));
            if(execute)cmd.add("--execute");
            if(execute&&!commercialOutput) {
                // A review from a previous cycle must never authorize an overwrite.
                validatedKey="";reviewFingerprint="";reviewed.setSelected(false);archivedState="";commercialDoneKey="";
            }
            if(commercialOutput)commercialDoneKey=""; // Una ejecucion fallida no conserva una aprobacion previa.
        }catch(Exception ex){warn("B6: "+ex.getMessage());return;}
        console.setText("=== B6 "+(commercialOutput?"COVERAGE COMERCIAL":(execute?"REVISION":"SIMULACION"))+" ===\n");
        append("Ligas LS publicadas="+c.idsText+" | temporadas=ALL\nOrigen="+c.source+"\nDestino="+(commercialOutput?c.commercial:c.review)+"\nLog="+log);
        summary.setText("Procesando B6; no cerrar la aplicacion.");begin();
        new SwingWorker<String,String>() {
            @Override protected String doInBackground() {
                boolean failure=false,sawCoverage=false,sawDry=false,sawReport=false;
                try(BufferedWriter writer=Files.newBufferedWriter(log,StandardCharsets.UTF_8)) {
                    ProcessBuilder pb=new ProcessBuilder(cmd).directory(c.project.toFile()).redirectErrorStream(true);
                    pb.environment().remove("TOTALCORNER_API_TOKEN");
                    pb.environment().remove("TC_DB_PASSWORD");
                    Process p=pb.start();child=p;
                    try(BufferedReader in=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))) {
                        String line;while((line=in.readLine())!=null) {
                            writer.write(line);writer.newLine();publish(line);
                            if(line.startsWith("COVERAGE source="))sawCoverage=true;
                            if(line.startsWith("DRY-RUN"))sawDry=true;
                            if(line.startsWith("Coverage report generated."))sawReport=true;
                            if(line.startsWith("COVERAGE FAILED")||line.startsWith("Exception in thread"))failure=true;
                        }
                    }
                    int exit=p.waitFor();
                    if(cancelled)return "B6 INTERRUMPIDO. Revisa el estado de los archivos; no se autoriza avanzar";
                    if(exit!=0||failure||!sawCoverage||(execute?!sawReport:!sawDry))
                        return "ERROR B6: fallo en generator (codigo "+exit+"). Revisar "+log;
                    if(execute) {
                        B6ReportVerifier.Result verified=B6ReportVerifier.verify(commercialOutput?c.commercial:c.review,c.source,c.ids);
                        publish(verified.summary());
                        if(commercialOutput) {
                            commercialDoneKey=runKey;
                            return "B6 COMERCIAL CONFIRMADO: "+verified.summary()+". Siguiente: archivar la version nueva.";
                        }
                        generatedKey=runKey;reviewFingerprint=verified.fingerprint;
                        return "B6 REVISION GENERADA: "+verified.summary()+". Abre el Excel y revisa antes de validar.";
                    }
                    simulatedKey=runKey;
                    return "B6 SIMULACION OK: "+c.ids.size()+" ligas seleccionadas. Aun NO se han validado contenidos ZIP.";
                }catch(Exception ex){return "ERROR B6: "+ex.getMessage();}
                finally{child=null;}
            }
            @Override protected void process(List<String> lines){for(String line:lines)append(line);}
            @Override protected void done() {
                String result;try{result=get();}catch(Exception ex){result="ERROR B6: "+ex.getMessage();}
                append("== "+result+" ==");summary.setText(result);end();
            }
        }.execute();
    }
    private void validateReviewFiles() {
        if(ownBusy||managerBusy.get())return;
        final Config c;final String runKey;
        try {c=snapshot();runKey=key(c);
            if(!runKey.equals(generatedKey))throw new IOException("Genera primero la carpeta coverage-review para esta seleccion");
        }catch(Exception ex){warn(ex.getMessage());return;}
        begin();
        new SwingWorker<String,Void>() {
            @Override protected String doInBackground() {
                try {
                    B6ReportVerifier.Result r=B6ReportVerifier.verify(c.review,c.source,c.ids);
                    if(!r.fingerprint.equals(reviewFingerprint))return "ERROR B6: los archivos review han cambiado. Regeneralos.";
                    validatedKey=runKey;
                    return "B6 REVISION VALIDADA: "+r.summary()+". Revisa personalmente Overview y File_Check y marca la casilla.";
                }catch(Exception ex){return "ERROR B6 VALIDACION: "+ex.getMessage();}
            }
            @Override protected void done(){String s;try{s=get();}catch(Exception ex){s="ERROR B6: "+ex.getMessage();}
                append(s);summary.setText(s);
                if(s.startsWith("B6 COMPLETADO"))onCompletion.accept(s);
                end();}
        }.execute();
    }
    private void archive(final boolean newVersion) {
        if(ownBusy||managerBusy.get())return;
        final Config c;final String runKey;
        try {
            c=snapshot();runKey=key(c);
            if(newVersion&&!runKey.equals(commercialDoneKey))throw new IOException("Primero genera el Coverage comercial");
            if(!newVersion&&(!runKey.equals(validatedKey)||!reviewed.isSelected()))
                throw new IOException("Primero valida los informes y confirma la revision manual");
            if(JOptionPane.showConfirmDialog(this,"B5.1 archivara los ficheros comerciales modificados en:\n"+c.archive+
                "\nNo elimina los originales. ¿Continuar?","Archivar B6",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
        }catch(Exception ex){warn(ex.getMessage());return;}
        begin();
        new SwingWorker<String,String>() {
            @Override protected String doInBackground(){
                try {
                    B5ArchiveRunner.Result r=B5ArchiveRunner.run(c.commercial,c.archive,true,this::publish);
                    String answer=r.summary();
                    if(newVersion)return "B6 COMPLETADO: "+answer+". Preparado para revision de publicacion (WinSCP, separado).";
                    archivedState=B5ArchiveRunner.stateKey(c.commercial);
                    return answer+". Version anterior archivada: puedes generar Coverage comercial.";
                }catch(Exception ex){return "ERROR ARCHIVO B6: "+ex.getMessage();}
            }
            @Override protected void process(List<String> lines){for(String line:lines)append(line);}
            @Override protected void done(){String s;try{s=get();}catch(Exception ex){s="ERROR ARCHIVO B6: "+ex.getMessage();}
                append(s);summary.setText(s);end();}
        }.execute();
    }
    private void cancel() {
        if(!ownBusy)return;
        if(JOptionPane.showConfirmDialog(this,"Detener B6? Puede haber archivos de revision parciales.","Detener",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.YES_OPTION)return;
        cancelled=true;Process p=child;if(p!=null)p.destroy();
    }
}
