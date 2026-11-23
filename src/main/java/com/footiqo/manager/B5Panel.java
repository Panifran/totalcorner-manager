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
import java.util.regex.*;

/** B5: dos procesos Java 17 independientes, con simulacion, backup y comprobacion de bundles. */
public final class B5Panel extends JPanel {
    private static final long serialVersionUID=1L;
    private final JTextField intellijProject=new JTextField(48),java17=new JTextField(48);
    private final JTextField exports=new JTextField("C:/Mi PC/freefootstats/Premium/Odds/historical-odds-exports",48);
    private final JTextField commercial=new JTextField("C:/Mi PC/freefootstats/Premium/Odds/historical-odds-commercial",48);
    private final JTextField archive=new JTextField("C:/Mi PC/freefootstats/Premium/Odds/Historico",48);
    private final JTextField exportIds=new JTextField("1,2,3,4,5,18,28",20);
    private final JTextField publishedIds=new JTextField("1,2,3,4,5,18,28",20);
    private final JTextField current=new JTextField("2026/2027",16);
    private static final String MODE_CURRENT="Temporada actual";
    private static final String MODE_CUSTOM="Seleccionar temporadas";
    private static final String MODE_ALL="Todas las temporadas";
    private final JComboBox<String> exportSeasonMode=new JComboBox<String>(new String[]{MODE_CURRENT,MODE_CUSTOM,MODE_ALL});
    private final JTextField exportSeasonSelection=new JTextField("2026/2027",22);
    private final JButton check=new JButton("1. Verificar Java 17 y JAR");
    private final JButton previewArchive=new JButton("2. Simular archivo B5.1");
    private final JButton makeArchive=new JButton("3. Guardar versiones anteriores");
    private final JButton previewExport=new JButton("4. Simular Exporter");
    private final JButton makeExport=new JButton("5. Ejecutar Exporter");
    private final JButton previewPackage=new JButton("6. Simular Packager");
    private final JButton makePackage=new JButton("7. Ejecutar Packager");
    private final JButton stop=new JButton("Detener B5");
    private final JTextArea console=new JTextArea(20,105);
    private final JLabel summary=new JLabel("Primero verifica Java 17 y archiva los ZIP comerciales antes de sobrescribirlos.");
    private final JLabel blockedReasons=new JLabel("Estado de los pasos B5: pendiente de verificar.");
    private final Supplier<Boolean> managerBusy;
    private final Consumer<Boolean> setManagerBusy;
    private final Supplier<String> downloaderDir;
    private volatile Process child;
    private volatile boolean cancelled,ownBusy,otherBusy;
    private String exporterPreview="",exporterDone="",packagerPreview="",archiveConfirmed="";
    private Consumer<String> onCompletion=message -> {};
    public void setOnCompletion(Consumer<String> listener){onCompletion=listener==null?message -> {}:listener;}

    public B5Panel(Supplier<Boolean> managerBusy,Consumer<Boolean> setManagerBusy,Supplier<String> downloaderDir) {
        super(new BorderLayout(4,6));
        this.managerBusy=managerBusy;this.setManagerBusy=setManagerBusy;this.downloaderDir=downloaderDir;
        setBorder(new EmptyBorder(8,4,6,4));
        JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
        JLabel title=new JLabel("B5 - Actualizar ZIP de temporadas y paquetes comerciales (Java 17)");
        title.setFont(title.getFont().deriveFont(Font.BOLD,16f));top.add(title);
        top.add(new JLabel("IDs Footiqo (ls_league.id), NO IDs TC. Elige las ligas publicadas reales. B5 no publica en la web."));
        top.add(row("Proyecto IntelliJ",intellijProject,true));
        top.add(row("Java 17 (java.exe)",java17,false));
        top.add(row("ZIP por temporada",exports,true));
        top.add(row("ZIP comerciales",commercial,true));
        top.add(row("Historico de versiones",archive,true));
        JPanel choices=new JPanel(new FlowLayout(FlowLayout.LEFT,8,1));
        choices.add(new JLabel("Exporter LS IDs:"));choices.add(exportIds);
        choices.add(new JLabel("Publicadas LS IDs:"));choices.add(publishedIds);
        choices.add(new JLabel("Current:"));choices.add(current);
        top.add(choices);
        JPanel seasonRow=new JPanel(new FlowLayout(FlowLayout.LEFT,8,1));
        seasonRow.add(new JLabel("Temporadas Exporter:"));seasonRow.add(exportSeasonMode);
        seasonRow.add(new JLabel("Seleccion manual:"));seasonRow.add(exportSeasonSelection);
        top.add(seasonRow);
        top.add(new JLabel("Exporter: por defecto solo temporada(s) de Current, UPDATE y BOTH. Packager: historico completo + Current."));
        JPanel action1=new JPanel(new FlowLayout(FlowLayout.LEFT,5,2));
        action1.add(check);action1.add(previewArchive);action1.add(makeArchive);action1.add(previewExport);action1.add(makeExport);
        JPanel action2=new JPanel(new FlowLayout(FlowLayout.LEFT,5,2));
        action2.add(previewPackage);action2.add(makePackage);action2.add(stop);
        top.add(action1);top.add(action2);
        blockedReasons.setBorder(new EmptyBorder(3,5,4,5));top.add(blockedReasons);
        add(top,BorderLayout.NORTH);
        console.setEditable(false);console.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));
        add(new JScrollPane(console),BorderLayout.CENTER);
        summary.setBorder(new EmptyBorder(6,1,6,1));add(summary,BorderLayout.SOUTH);
        check.addActionListener(e->preflight());
        previewArchive.addActionListener(e->archive(false));
        makeArchive.addActionListener(e->archive(true));
        previewExport.addActionListener(e->runJava("exporter",false));
        makeExport.addActionListener(e->runJava("exporter",true));
        previewPackage.addActionListener(e->runJava("packager",false));
        makePackage.addActionListener(e->runJava("packager",true));
        stop.addActionListener(e->stop());
        exportSeasonMode.addActionListener(e->{
            exportSeasonSelection.setEnabled(MODE_CUSTOM.equals(exportSeasonMode.getSelectedItem()));
            clearApprovals();save();
        });
        load();
        exportSeasonSelection.setEnabled(MODE_CUSTOM.equals(exportSeasonMode.getSelectedItem()));
        javax.swing.event.DocumentListener invalidate=new javax.swing.event.DocumentListener(){
            public void insertUpdate(javax.swing.event.DocumentEvent e){updateButtons();}
            public void removeUpdate(javax.swing.event.DocumentEvent e){updateButtons();}
            public void changedUpdate(javax.swing.event.DocumentEvent e){updateButtons();}
        };
        exportSeasonSelection.getDocument().addDocumentListener(invalidate);
        current.getDocument().addDocumentListener(invalidate);
        exportIds.getDocument().addDocumentListener(invalidate);
        updateButtons();
    }

    private JPanel row(String text,JTextField field,boolean directory){
        JPanel p=new JPanel(new BorderLayout(5,2));p.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel label=new JLabel(text);label.setPreferredSize(new Dimension(160,24));p.add(label,BorderLayout.WEST);
        p.add(field,BorderLayout.CENTER);
        JButton select=new JButton("Examinar...");
        select.addActionListener(e->{JFileChooser chooser=new JFileChooser();chooser.setFileSelectionMode(directory?JFileChooser.DIRECTORIES_ONLY:JFileChooser.FILES_ONLY);
            try{Path path=Paths.get(field.getText().trim());if(Files.exists(path))chooser.setSelectedFile(path.toFile());}catch(Exception ignored){}
            if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){field.setText(chooser.getSelectedFile().getAbsolutePath());clearApprovals();save();}});
        p.add(select,BorderLayout.EAST);
        return p;
    }
    /** Opciones compartidas con B6. Siempre se leen del B5 visible, no de copias antiguas. */
    public Properties publicationSettings() {
        Properties p = new Properties();
        p.setProperty("project", intellijProject.getText().trim());
        p.setProperty("java17", java17.getText().trim());
        p.setProperty("exports", exports.getText().trim());
        p.setProperty("commercial", commercial.getText().trim());
        p.setProperty("archive", archive.getText().trim());
        p.setProperty("publishedIds", publishedIds.getText().trim());
        p.setProperty("exportIds", exportIds.getText().trim());
        return p;
    }

    public void setExternalBusy(boolean value){otherBusy=value;updateButtons();}
    private void updateButtons(){
        boolean enable=!ownBusy&&!otherBusy;
        String activity=ownBusy?"Hay un proceso B5 en ejecución.":"Hay otro proceso en Manager en ejecución.";
        String expKey=exportKey(), pkgKey=packageKey(), arcKey=archiveKey();
        boolean expReady=!exporterPreview.isEmpty()&&exporterPreview.equals(expKey);
        boolean expDone=exporterDone.equals(expKey);
        boolean pkgReady=!packagerPreview.isEmpty()&&packagerPreview.equals(pkgKey);
        boolean archived=!archiveConfirmed.isEmpty()&&archiveConfirmed.equals(arcKey);
        String expWhy=exporterPreview.isEmpty()?"Falta ejecutar 4. Simular Exporter.":
                !expReady?"Los parametros cambiaron: repite 4. Simular Exporter.":"";
        String pkgWhy= !archived?"Falta 3. Guardar versiones anteriores para esta version de los ZIP.":
                !expDone?"Falta 5. Ejecutar Exporter con la configuracion actual.":
                !pkgReady?"Falta 6. Simular Packager con los archivos y parametros actuales.":"";
        ManagerUiSupport.gate(check,enable,activity);
        ManagerUiSupport.gate(previewArchive,enable,activity);
        ManagerUiSupport.gate(makeArchive,enable,activity);
        ManagerUiSupport.gate(previewExport,enable,activity);
        ManagerUiSupport.gate(makeExport,enable&&expReady,!enable?activity:expWhy);
        ManagerUiSupport.gate(previewPackage,enable,activity);
        ManagerUiSupport.gate(makePackage,enable&&pkgWhy.isEmpty(),!enable?activity:pkgWhy);
        ManagerUiSupport.gate(stop,ownBusy,"No hay un proceso B5 que detener.");
        String expStatus=expReady?"Exporter listo para ejecutar":expWhy;
        String pkgStatus=pkgWhy.isEmpty()?"Packager listo para ejecutar":pkgWhy;
        blockedReasons.setText("<html><b>Exporter:</b> "+expStatus+"<br><b>Packager:</b> "+pkgStatus+"</html>");
    }
    private void clearApprovals(){exporterPreview="";exporterDone="";packagerPreview="";archiveConfirmed="";updateButtons();}
    private void warn(String msg){JOptionPane.showMessageDialog(this,msg,"TotalCorner Manager B5",JOptionPane.WARNING_MESSAGE);summary.setText(msg);}
    private static boolean empty(String str){return str==null||str.trim().isEmpty();}
    private Path path(JTextField field){return Paths.get(field.getText().trim()).toAbsolutePath().normalize();}
    private String exportKey(){
        return exportIds.getText().trim()+"|"+exports.getText().trim()+"|"+
                intellijProject.getText().trim()+"|"+java17.getText().trim()+"|"+
                exportSeasonMode.getSelectedItem()+"|"+
                (MODE_CUSTOM.equals(exportSeasonMode.getSelectedItem())?exportSeasonSelection.getText().trim():current.getText().trim());
    }
    /** Regla de EXPORTER independiente de la seleccion de paquetes comerciales. */
    private String exporterSeasonsArg(){
        String mode=String.valueOf(exportSeasonMode.getSelectedItem());
        if(MODE_ALL.equals(mode))return "ALL";
        String value=MODE_CUSTOM.equals(mode)?exportSeasonSelection.getText():current.getText();
        seasons(value); // valida YYYY/YYYY o YYYY, incluida una lista separada por comas
        StringJoiner result=new StringJoiner(",");
        for(String item:value.split(",",-1))result.add(item.trim());
        return result.toString();
    }
    private String packageKey(){
        try{return publishedIds.getText().trim()+"|"+current.getText().trim()+"|"+exports.getText().trim()+"|"+commercial.getText().trim()+"|"+sourcesKey();}
        catch(Exception ex){return "NOT_READY";}
    }
    private String archiveKey(){try{return B5ArchiveRunner.stateKey(path(commercial))+"|"+archive.getText().trim();}catch(Exception ex){return "NOT_READY";}}
    private String sourcesKey()throws IOException{
        Path p=path(exports);if(!Files.isDirectory(p))return "NO_SOURCE";
        // Detecta si Exporter ha cambiado los ZIP entre la simulacion y la ejecucion.
        StringBuilder b=new StringBuilder();
        try(DirectoryStream<Path> dirs=Files.newDirectoryStream(p)){
            List<Path> all=new ArrayList<Path>();
            for(Path dir:dirs)if(Files.isDirectory(dir)&&dir.getFileName().toString().matches("\\d+_.+"))
                try(DirectoryStream<Path> zip=Files.newDirectoryStream(dir,"*.zip")) {for(Path f:zip)all.add(f);}
            all.sort(Comparator.comparing(Path::toString));
            for(Path file:all)b.append(file.toString()).append(':').append(Files.size(file)).append(':').append(Files.getLastModifiedTime(file).toMillis()).append('|');
        }
        return b.toString();
    }

    private static Set<Integer> ids(String input){
        LinkedHashSet<Integer> out=new LinkedHashSet<Integer>();
        if(empty(input)||input.contains("ALL"))throw new IllegalArgumentException("Introduce IDs explicitos de ligas Footiqo, nunca ALL");
        for(String token:input.split(",",-1)){
            String s=token.trim();if(!s.matches("[0-9]+"))throw new IllegalArgumentException("ID Footiqo incorrecto: "+s);
            if(!out.add(Integer.parseInt(s)))throw new IllegalArgumentException("ID Footiqo repetido: "+s);
        }
        if(out.size()>100)throw new IllegalArgumentException("Demasiadas ligas publicadas");
        return out;
    }
    private static Set<String> seasons(String input){
        LinkedHashSet<String> out=new LinkedHashSet<String>();
        for(String token:input.split(",",-1)){
            String s=token.trim();if(!s.matches("\\d{4}(?:/\\d{4})?"))throw new IllegalArgumentException("Temporada Current incorrecta: "+s);
            if(s.contains("/")&&Integer.parseInt(s.substring(5))<Integer.parseInt(s.substring(0,4)))throw new IllegalArgumentException("Temporada invertida: "+s);
            out.add(s.replace('/','_'));
        }
        return out;
    }
    private static String join(Set<Integer> ids){StringJoiner j=new StringJoiner(",");for(Integer n:ids)j.add(n.toString());return j.toString();}
    private Path compiledJar() throws IOException{
        Path p=path(intellijProject);
        if(!Files.isRegularFile(p.resolve("pom.xml")))throw new IOException("Selecciona la carpeta raiz Corners_Cards_Simulator (con pom.xml)");
        Path jar=p.resolve("target/corners-cards-simulator-1.0.0.jar");
        if(!Files.isRegularFile(jar))throw new IOException("Falta JAR compilado "+jar+". En IntelliJ: Maven > Lifecycle > package (Java 17)");
        String[] names={"HistoricalOddsExporter.class","HistoricalOddsPackager.class"};
        try(JarFile j=new JarFile(jar.toFile())){
            for(String name:names)if(j.getEntry(name)==null)throw new IOException("JAR sin "+name+". Ejecuta Maven package en IntelliJ");
        }
        long jarTime=Files.getLastModifiedTime(jar).toMillis();
        Path src=p.resolve("src/main/java");
        for(String name:new String[]{"HistoricalOddsExporter.java","HistoricalOddsPackager.java"}) {
            Path code=src.resolve(name);
            if(!Files.isRegularFile(code))throw new IOException("Falta clase original de IntelliJ: "+name);
            if(Files.getLastModifiedTime(code).toMillis()>jarTime+2000)throw new IOException("JAR antiguo respecto a "+name+". Recompila con Maven package");
        }
        return jar;
    }
    private void initializeProject(){
        if(empty(intellijProject.getText())) {
            try {String down=downloaderDir.get();if(!empty(down)){
                Path sibling=Paths.get(down).toAbsolutePath().normalize().resolveSibling("Corners_Cards_Simulator");
                if(Files.isDirectory(sibling))intellijProject.setText(sibling.toString());
            }}catch(Exception ignored){}
        }
    }
    private void verify()throws Exception {
        initializeProject();
        Set<Integer> toExport=ids(exportIds.getText());Set<Integer> sell=ids(publishedIds.getText());
        if(!toExport.containsAll(sell))throw new IOException("Las ligas publicadas deben estar incluidas en Exporter (IDs Footiqo)");
        seasons(current.getText());
        exporterSeasonsArg();
        Path exec=path(java17);
        if(!Files.isRegularFile(exec))throw new IOException("Selecciona Java 17 (java.exe)");
        Process p=new ProcessBuilder(exec.toString(),"-version").redirectErrorStream(true).start();
        if(!p.waitFor(10,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("Java no responde");}
        StringBuilder version=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))){String s;while((s=r.readLine())!=null)version.append(s);}
        if(p.exitValue()!=0||!Pattern.compile("version \\\"17(?:\\.|\\\")").matcher(version.toString()).find())
            throw new IOException("Se requiere Java 17; detectado: "+version);
        compiledJar();
        if(!Files.isDirectory(path(exports)))throw new IOException("La carpeta ZIP por temporada no existe: "+path(exports));
        if(!Files.isDirectory(path(commercial)))throw new IOException("La carpeta comercial no existe: "+path(commercial));
    }
    private void preflight(){
        if(ownBusy||managerBusy.get())return;
        try{verify();save();console.append("VERIFICACION B5 OK: Java 17, JAR empaquetado, dos clases disponibles, IDs y rutas.\n");
            summary.setText("Configuracion B5 valida. Siguiente: simular archivo B5.1, guardar versiones, simular Exporter.");
        }catch(Exception ex){warn("Verificacion B5: "+ex.getMessage());}updateButtons();
    }
    private void startBusy(){
        ownBusy=true;cancelled=false;setManagerBusy.accept(true);updateButtons();
    }
    private void finishBusy(){
        ownBusy=false;child=null;setManagerBusy.accept(false);updateButtons();
    }
    private void archive(final boolean doCopy){
        if(ownBusy||managerBusy.get())return;
        final Path source,target;
        try{ids(publishedIds.getText());source=path(commercial);target=path(archive);if(!Files.isDirectory(source))throw new IOException("Carpeta comercial inexistente");save();}
        catch(Exception ex){warn(ex.getMessage());return;}
        if(doCopy) {
            int answer=JOptionPane.showConfirmDialog(this,"B5.1 copiara versiones comerciales modificadas a:\n"+target+"\nNo borrara ni sustituira originales. ¿Continuar?", "Guardar historico",JOptionPane.YES_NO_OPTION);
            if(answer!=JOptionPane.YES_OPTION)return;
        }
        console.setText("=== B5.1 ARCHIVO "+(doCopy?"REAL":"SIMULACION")+" ===\n");
        summary.setText("Comparando versiones comerciales con ultimo historico...");
        startBusy();
        new SwingWorker<String,String>(){
            @Override protected String doInBackground(){
                try {
                    B5ArchiveRunner.Result r=B5ArchiveRunner.run(source,target,doCopy,message->publish(message));
                    return r.summary();
                }catch(Exception ex){return "ERROR ARCHIVO B5.1: "+ex.getMessage();}
            }
            @Override protected void process(List<String> chunks){for(String s:chunks)append(s);}
            @Override protected void done(){String result;try{result=get();}catch(Exception ex){result="ERROR: "+ex.getMessage();}
                append(result);summary.setText(result);
                if(doCopy&&result.startsWith("ARCHIVO OK"))archiveConfirmed=archiveKey();
                finishBusy();
            }
        }.execute();
    }
    private void runJava(final String stage,final boolean execute){
        if(ownBusy||managerBusy.get())return;
        final List<String> cmd=new ArrayList<String>();final Path dir,log;
        final String key=stage.equals("exporter")?exportKey():packageKey();
        try {
            verify();
            if(stage.equals("exporter")&&empty(System.getenv("TC_DB_URL")))throw new IOException("Falta TC_DB_URL en el entorno de Eclipse/Manager");
            if(stage.equals("exporter")&&empty(System.getenv("TC_DB_USER")))throw new IOException("Falta TC_DB_USER en el entorno de Eclipse/Manager");
            if(stage.equals("exporter")&&empty(System.getenv("TC_DB_PASSWORD")))throw new IOException("Falta TC_DB_PASSWORD en el entorno de Eclipse/Manager");
            if(execute&&stage.equals("exporter")&&!key.equals(exporterPreview))throw new IOException("Primero simula Exporter con los mismos parametros");
            if(execute&&stage.equals("packager")){
                if(!exporterDone.equals(exportKey()))throw new IOException("Primero ejecuta Exporter en esta sesion");
                if(!key.equals(packagerPreview))throw new IOException("Primero simula Packager despues de Exporter");
                if(!archiveKey().equals(archiveConfirmed))throw new IOException("Primero archiva los ZIP comerciales actuales (B5.1)");
            }
            dir=path(intellijProject);
            cmd.add(path(java17).toString());cmd.add("-Dfile.encoding=UTF-8");cmd.add("-cp");cmd.add(compiledJar().toString());
            if(stage.equals("exporter")){
                cmd.add("HistoricalOddsExporter");cmd.add("--league-ids="+join(ids(exportIds.getText())));
                cmd.add("--seasons="+exporterSeasonsArg());cmd.add("--mode=UPDATE");cmd.add("--format=BOTH");cmd.add("--output-dir="+path(exports));
            }else{
                cmd.add("HistoricalOddsPackager");cmd.add("--league-ids="+join(ids(publishedIds.getText())));
                cmd.add("--current-seasons="+current.getText().trim());cmd.add("--mode=UPDATE");
                cmd.add("--bundle-selected");cmd.add("--source-dir="+path(exports));cmd.add("--output-dir="+path(commercial));
            }
            if(execute)cmd.add("--execute");
            Path logs=dir.resolve("target").resolve("totalcorner-manager-logs");Files.createDirectories(logs);
            log=logs.resolve("B5-"+stage+"-"+(execute?"execute":"simulate")+"-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))+".log");
            if(execute){
                int reply=JOptionPane.showConfirmDialog(this,(stage.equals("exporter")?
                    "B5 Exporter actualizara ZIP por liga/temporada en:\n"+path(exports):
                    "B5 Packager sustituira ZIP comerciales modificados en:\n"+path(commercial))+
                    "\nSeleccion Footiqo: "+(stage.equals("exporter")?exportIds.getText():publishedIds.getText())+
                    (stage.equals("exporter")?"\nTemporadas Exporter: "+exporterSeasonsArg():"\nCurrent comercial: "+current.getText().trim())+
                    "\nModo UPDATE, sin subir a la web. ¿Ejecutar?", "Confirmar B5",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE);
                if(reply!=JOptionPane.YES_OPTION)return;
            }
            save();
        }catch(Exception ex){warn(ex.getMessage());return;}
        console.setText("=== B5 "+stage.toUpperCase(Locale.ROOT)+" "+(execute?"EJECUCION":"SIMULACION")+" ===\n");
        append("Java 17: "+java17.getText()+"\nProyecto: "+dir+"\nRegistro: "+log);
        if(stage.equals("exporter"))for(String arg:cmd)if(arg.startsWith("--seasons="))append("Filtro Exporter: "+arg);
        summary.setText("Procesando B5 "+stage+"; no cerrar la aplicacion hasta finalizar.");
        startBusy();
        new SwingWorker<String,String>() {
            @Override protected String doInBackground(){
                boolean finished=false;boolean failed=false;int packages=-1,created=-1,unchanged=-1,planned=-1;
                try(BufferedWriter writer=Files.newBufferedWriter(log,StandardCharsets.UTF_8)){
                    ProcessBuilder pb=new ProcessBuilder(cmd);pb.directory(dir.toFile());pb.redirectErrorStream(true);
                    pb.environment().remove("TOTALCORNER_API_TOKEN");
                    Process p=pb.start();child=p;
                    Pattern metrics=Pattern.compile("Finished\\. created=(\\d+) unchanged=(\\d+) planned=(\\d+)");
                    Pattern count=Pattern.compile("packages=(\\d+)");
                    try(BufferedReader read=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))){
                        String line;while((line=read.readLine())!=null){
                            writer.write(line);writer.newLine();publish(line);
                            if(line.startsWith("Finished."))finished=true;
                            if(line.startsWith("EXPORT FAILED")||line.startsWith("PACKAGER FAILED")||line.startsWith("Exception in thread"))failed=true;
                            Matcher m=metrics.matcher(line);if(m.find()){
                                created=Integer.parseInt(m.group(1));unchanged=Integer.parseInt(m.group(2));planned=Integer.parseInt(m.group(3));}
                            Matcher c=count.matcher(line);if(c.find())packages=Integer.parseInt(c.group(1));
                        }
                    }
                    int exit=p.waitFor();
                    if(cancelled)return "B5 INTERRUMPIDO: no declarar etapas terminadas; revisar archivos y repetir simulacion";
                    if(exit!=0||failed||!finished)return "ERROR B5 "+stage+": no confirmado (codigo "+exit+"). Revisar "+log;
                    if(stage.equals("packager")) {
                        int expected=2*ids(publishedIds.getText()).size()+2;
                        if(packages!=expected||created+unchanged+planned!=packages||
                            (execute&&planned!=0))return "ERROR B5 Packager: paquetes inesperados (esperados="+expected+", recibidos="+packages+"). Revisar el log";
                        if(execute) {
                            String verify=B5PackageVerifier.verify(path(exports),path(commercial),ids(publishedIds.getText()),seasons(current.getText()));
                            publish(verify);
                        }
                    }
                    return (execute?"CONFIRMADO":"SIMULACION OK")+" B5 "+stage.toUpperCase(Locale.ROOT)+". "+
                        (stage.equals("exporter")?(execute?"ZIP temporadas revisados":"Sin modificar ZIP"):
                            (execute?"Bundles verificados, ZIP locales preparados":"Sin crear paquetes"));
                }catch(Exception ex){return "ERROR B5 "+stage+": "+ex.getMessage();}finally{child=null;}
            }
            @Override protected void process(List<String> lines){for(String s:lines)append(s);}
            @Override protected void done(){
                String result;try{result=get();}catch(Exception ex){result="ERROR B5: "+ex.getMessage();}
                append("== "+result+" ==");summary.setText(result);
                if(result.startsWith("SIMULACION OK B5 EXPORTER"))exporterPreview=key;
                if(result.startsWith("CONFIRMADO B5 EXPORTER")){exporterDone=key;exporterPreview="";packagerPreview="";}
                if(result.startsWith("SIMULACION OK B5 PACKAGER"))packagerPreview=key;
                if(result.startsWith("CONFIRMADO B5 PACKAGER")){packagerPreview="";archiveConfirmed="";
                    summary.setText("B5 COMPLETADO: ZIP comerciales verificados. Siguiente: pestaña 9 B6 Coverage publico.");}
                if(result.startsWith("CONFIRMADO B5 PACKAGER"))onCompletion.accept(result);
                finishBusy();
            }
        }.execute();
    }
    private void append(String line){console.append(line+"\n");if(console.getDocument().getLength()>220000)console.replaceRange("[Log abreviado; completo en archivo]\n",0,70000);console.setCaretPosition(console.getDocument().getLength());}
    private void stop(){if(!ownBusy)return;Process p=child;
        int choice=JOptionPane.showConfirmDialog(this,"Detener el proceso B5 en curso? Los archivos ya sustituidos pueden permanecer cambiados.","Interrumpir B5",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE);
        if(choice==JOptionPane.YES_OPTION){cancelled=true;if(p!=null)p.destroy();}
    }
    private Path settingsFile(){return Paths.get(System.getProperty("user.home"),".totalcorner-manager-b5.properties");}
    private void load(){
        try{
            Properties p=new Properties();Path file=settingsFile();if(Files.isRegularFile(file))try(InputStream in=Files.newInputStream(file)){p.load(in);}
            intellijProject.setText(p.getProperty("project", ""));java17.setText(p.getProperty("java17", ""));
            exports.setText(p.getProperty("exports",exports.getText()));commercial.setText(p.getProperty("commercial",commercial.getText()));
            archive.setText(p.getProperty("archive",archive.getText()));exportIds.setText(p.getProperty("exportIds",exportIds.getText()));
            publishedIds.setText(p.getProperty("publishedIds",publishedIds.getText()));current.setText(p.getProperty("current",current.getText()));
            exportSeasonSelection.setText(p.getProperty("exportSeasons",exportSeasonSelection.getText()));
            String savedMode=p.getProperty("exportSeasonMode",MODE_CURRENT);
            exportSeasonMode.setSelectedItem(Arrays.asList(MODE_CURRENT,MODE_CUSTOM,MODE_ALL).contains(savedMode)?savedMode:MODE_CURRENT);
        }catch(Exception ignored){}initializeProject();
    }
    private void save(){
        Properties p=new Properties();p.setProperty("project",intellijProject.getText().trim());p.setProperty("java17",java17.getText().trim());
        p.setProperty("exports",exports.getText().trim());p.setProperty("commercial",commercial.getText().trim());
        p.setProperty("archive",archive.getText().trim());p.setProperty("exportIds",exportIds.getText().trim());
        p.setProperty("publishedIds",publishedIds.getText().trim());p.setProperty("current",current.getText().trim());
        p.setProperty("exportSeasonMode",String.valueOf(exportSeasonMode.getSelectedItem()));
        p.setProperty("exportSeasons",exportSeasonSelection.getText().trim());
        try(OutputStream out=Files.newOutputStream(settingsFile())){p.store(out,"TotalCorner Manager B5 rutas e IDs de Footiqo - no contiene contrasenas");}
        catch(Exception e){summary.setText("Aviso: no se han guardado preferencias B5: "+e.getMessage());}
    }
}
