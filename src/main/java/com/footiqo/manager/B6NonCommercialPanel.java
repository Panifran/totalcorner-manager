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

/** B6 no comercial: ZIP internos por temporada, luego Coverage. Nunca invoca Packager ni B5 comercial. */
public final class B6NonCommercialPanel extends JPanel {
    private static final long serialVersionUID=1L;
    private final Supplier<Properties> settings;
    private final Supplier<Boolean> busy;
    private final Consumer<Boolean> setBusy;
    private final JTextField exportFolder=new JTextField(75);
    private final JTextField coverageFolder=new JTextField(75);
    private final JButton refresh=new JButton("1. Revisar ligas y rutas");
    private final JButton simExport=new JButton("2. Simular ZIP internos");
    private final JButton runExport=new JButton("3. Generar ZIP internos");
    private final JButton simCoverage=new JButton("4. Simular Coverage");
    private final JButton runCoverage=new JButton("5. Generar Coverage no comercial");
    private final JButton stop=new JButton("Detener");
    private final JTextArea log=new JTextArea(18,95);
    private final JLabel selection=new JLabel("Sin comprobar");
    private final JLabel status=new JLabel("Solo ligas LS no comerciales, con actualizaciones activadas.");
    private volatile Process child;
    private volatile boolean running,cancelled;
    private String exportSimKey="",exportDoneKey="",coverageSimKey="";

    public B6NonCommercialPanel(Supplier<Properties> settings,Supplier<Boolean> busy,Consumer<Boolean> setBusy) {
        super(new BorderLayout(5,5));this.settings=settings;this.busy=busy;this.setBusy=setBusy;
        setBorder(new EmptyBorder(8,5,8,5));
        JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
        JLabel title=new JLabel("B6 - Coverage de ligas no comerciales");
        title.setFont(title.getFont().deriveFont(Font.BOLD,16f));top.add(title);
        top.add(new JLabel("Mismos generadores Java 17 que B5/B6 comercial; salida independiente. No toca MySQL ni publica ZIP."));
        top.add(selection);
        top.add(row("ZIP internos",exportFolder));
        top.add(row("Coverage privado",coverageFolder));
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,5,2));
        for(JButton b:new JButton[]{refresh,simExport,runExport,simCoverage,runCoverage,stop})actions.add(b);
        top.add(actions);top.add(status);add(top,BorderLayout.NORTH);
        log.setEditable(false);log.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));add(new JScrollPane(log),BorderLayout.CENTER);
        refresh.addActionListener(e->preflight());
        simExport.addActionListener(e->execute("export",false));
        runExport.addActionListener(e->execute("export",true));
        simCoverage.addActionListener(e->execute("coverage",false));
        runCoverage.addActionListener(e->execute("coverage",true));
        stop.addActionListener(e->{cancelled=true;Process p=child;if(p!=null)p.destroy();});
        exportFolder.setText("");coverageFolder.setText("");
        updateButtons();
    }
    private JPanel row(String label,JTextField input){
        JPanel p=new JPanel(new BorderLayout(5,1));p.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel l=new JLabel(label);l.setPreferredSize(new Dimension(130,25));p.add(l,BorderLayout.WEST);p.add(input,BorderLayout.CENTER);
        return p;
    }
    private static String mandatory(Properties p,String key)throws IOException {
        String val=p.getProperty(key,"").trim();if(val.isEmpty())throw new IOException("Configura "+key+" en B5");return val;
    }
    private static Set<Integer> ids(String txt)throws IOException {
        Set<Integer> result=new LinkedHashSet<Integer>();
        for(String x:txt.split(",")){
            x=x.trim();if(!x.matches("[1-9][0-9]{0,8}"))throw new IOException("ID comercial invalido: "+x);
            result.add(Integer.parseInt(x));
        }
        return result;
    }
    private Set<Integer> nonCommercial(Properties p)throws IOException {
        Set<Integer> published=ids(mandatory(p,"publishedIds"));
        Set<Integer> found=new TreeSet<Integer>();
        for(LeagueMappingRegistry.Association a:LeagueMappingRegistry.load().values()) {
            if(a.updates){int ls=Integer.parseInt(a.lsId);if(!published.contains(ls))found.add(ls);}
        }
        if(found.isEmpty())throw new IOException("No hay ligas LS no comerciales con actualizacion activa.");
        return found;
    }
    private static String join(Collection<Integer> vals){StringJoiner j=new StringJoiner(",");for(Integer v:vals)j.add(v.toString());return j.toString();}
    private static Path jar(Path project,String cls)throws IOException {
        Path j=project.resolve("target/corners-cards-simulator-1.0.0.jar");
        if(!Files.isRegularFile(j))throw new IOException("Falta JAR; compila Corners_Cards_Simulator en IntelliJ");
        try(JarFile f=new JarFile(j.toFile())){if(f.getEntry(cls+".class")==null)throw new IOException("Falta "+cls+" en el JAR");}
        return j;
    }
    private static final class Config {
        Path project,java17,export,coverage,jar;Set<Integer> leagues;String csv;
    }
    private Config config()throws Exception {
        Properties p=settings.get();Config c=new Config();
        c.project=Paths.get(mandatory(p,"project")).toAbsolutePath().normalize();
        c.java17=Paths.get(mandatory(p,"java17")).toAbsolutePath().normalize();
        if(!Files.isRegularFile(c.java17))throw new IOException("Java 17 no encontrado");
        if(!Files.isRegularFile(c.project.resolve("pom.xml")))throw new IOException("Proyecto IntelliJ incorrecto");
        c.leagues=nonCommercial(p);c.csv=join(c.leagues);
        Path commercial=Paths.get(mandatory(p,"commercial")).toAbsolutePath().normalize();
        Path originalExport=Paths.get(mandatory(p,"exports")).toAbsolutePath().normalize();
        Path export=exportFolder.getText().trim().isEmpty()?originalExport.resolveSibling("historical-odds-exports-no-comerciales"):
            Paths.get(exportFolder.getText().trim()).toAbsolutePath().normalize();
        Path coverage=coverageFolder.getText().trim().isEmpty()?commercial.resolveSibling("coverage-no-comerciales"):
            Paths.get(coverageFolder.getText().trim()).toAbsolutePath().normalize();
        if(export.equals(originalExport)||export.startsWith(originalExport)||originalExport.startsWith(export))
            throw new IOException("La carpeta ZIP no comercial debe ser independiente de Exporter B5");
        if(coverage.equals(commercial)||coverage.startsWith(commercial)||commercial.startsWith(coverage))
            throw new IOException("Coverage no comercial debe estar fuera de la carpeta comercial");
        if(export.equals(coverage)||coverage.startsWith(export)||export.startsWith(coverage))
            throw new IOException("Separa carpetas de ZIP y Coverage");
        c.export=export;c.coverage=coverage;
        jar(c.project,"HistoricalOddsExporter");c.jar=jar(c.project,"HistoricalOddsCoverageGenerator");
        return c;
    }
    private String key(Config c,String stage)throws IOException {
        StringBuilder key=new StringBuilder(stage).append('|').append(c.csv).append('|').append(c.project)
                .append('|').append(c.export).append('|').append(c.coverage).append('|')
                .append(Files.getLastModifiedTime(c.jar).toMillis());
        if("coverage".equals(stage)&&Files.isDirectory(c.export)) {
            try(DirectoryStream<Path> dirs=Files.newDirectoryStream(c.export)){
                List<Path> files=new ArrayList<Path>();
                for(Path dir:dirs)if(Files.isDirectory(dir))try(DirectoryStream<Path> zips=Files.newDirectoryStream(dir,"*.zip")){
                    for(Path file:zips)files.add(file);
                }
                files.sort(Comparator.comparing(Path::toString));
                for(Path f:files)key.append('|').append(f).append(':').append(Files.size(f)).append(':').append(Files.getLastModifiedTime(f).toMillis());
            }
        }
        return key.toString();
    }
    private void preflight(){
        try {Config c=config();selection.setText("LS no comerciales: "+c.csv+" ("+c.leagues.size()+" ligas)");
            exportFolder.setText(c.export.toString());coverageFolder.setText(c.coverage.toString());
            status.setText("Rutas verificadas; primero simula el Exporter.");
        }catch(Exception e){JOptionPane.showMessageDialog(this,e.getMessage(),"B6 no comercial",JOptionPane.WARNING_MESSAGE);}
    }
    private void updateButtons(){boolean can=!running&&!busy.get();
        for(JButton b:new JButton[]{refresh,simExport,runExport,simCoverage,runCoverage})b.setEnabled(can);
        stop.setEnabled(running);
    }
    private void execute(String phase,boolean real){
        if(running||busy.get())return;
        final Config c;final String fingerprint;final List<String> cmd=new ArrayList<String>();
        try {
            c=config();fingerprint=key(c,phase);
            if(real&&"export".equals(phase)&&!fingerprint.equals(exportSimKey))throw new IOException("Simula primero los ZIP internos");
            if("coverage".equals(phase)&&!Files.isDirectory(c.export))throw new IOException("Primero genera los ZIP internos");
            if(real&&"coverage".equals(phase)&&!fingerprint.equals(coverageSimKey))throw new IOException("Simula primero Coverage");
            if(real&&"coverage".equals(phase)){
                if(JOptionPane.showConfirmDialog(this,"Generar Coverage privado para LS "+c.csv+" en:\n"+c.coverage+"\nNo modifica Coverage comercial.",
                    "Confirmar",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
            }
            cmd.add(c.java17.toString());cmd.add("-Dfile.encoding=UTF-8");cmd.add("-cp");cmd.add(c.jar.toString());
            if("export".equals(phase)){
                cmd.add("HistoricalOddsExporter");cmd.add("--league-ids="+c.csv);cmd.add("--seasons=ALL");
                cmd.add("--mode=UPDATE");cmd.add("--format=BOTH");cmd.add("--output-dir="+c.export);
            }else{
                cmd.add("HistoricalOddsCoverageGenerator");cmd.add("--league-ids="+c.csv);
                cmd.add("--seasons=ALL");cmd.add("--source="+c.export);cmd.add("--output="+c.coverage);
            }
            if(real)cmd.add("--execute");
        }catch(Exception e){JOptionPane.showMessageDialog(this,e.getMessage(),"B6 no comercial",JOptionPane.WARNING_MESSAGE);return;}
        running=true;cancelled=false;setBusy.accept(true);updateButtons();
        status.setText((real?"Ejecutando ":"Simulando ")+phase+"...");
        log.append("\n=== "+phase+(real?" REAL":" SIMULACION")+" LS="+c.csv+" ===\n");
        new SwingWorker<String,String>(){
            @Override protected String doInBackground(){
                boolean fail=false,finished=false;
                try{
                    if(real)Files.createDirectories("export".equals(phase)?c.export:c.coverage);
                    Process p=new ProcessBuilder(cmd).directory(c.project.toFile()).redirectErrorStream(true).start();child=p;
                    try(BufferedReader reader=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))){
                        String line;while((line=reader.readLine())!=null){publish(line);
                            if(line.startsWith("EXPORT FAILED")||line.startsWith("COVERAGE FAILED")||line.startsWith("Exception in thread"))fail=true;
                            if(line.startsWith("Finished.")||line.startsWith("Coverage report generated.")||line.startsWith("DRY-RUN"))finished=true;
                        }
                    }
                    int exit=p.waitFor();if(cancelled)return "Interrumpido: revisar archivos antes de reintentar";
                    if(exit!=0||fail||!finished)return "ERROR "+phase+" (codigo="+exit+"): revisar registro";
                    if("export".equals(phase)){
                        if(real){
                            for(Integer id:c.leagues){
                                boolean found=false;
                                try(DirectoryStream<Path> dirs=Files.newDirectoryStream(c.export,id+"_*")){
                                    for(Path dir:dirs)if(Files.isDirectory(dir))try(DirectoryStream<Path> zips=Files.newDirectoryStream(dir,"*.zip")){
                                        for(Path file:zips)if(file.getFileName().toString().matches("\\d{4}(?:_\\d{4})?\\.zip"))found=true;
                                    }
                                }
                                if(!found)return "ERROR: falta ZIP interno para LS="+id;
                            }
                            exportDoneKey=fingerprint;coverageSimKey="";
                        }else exportSimKey=fingerprint;
                    }else if(real){
                        B6ReportVerifier.Result checked=B6ReportVerifier.verify(c.coverage,c.export,c.leagues);
                        publish(checked.summary());
                    }else coverageSimKey=fingerprint;
                    return "OK "+phase+(real?" generado":" simulado")+" para LS "+c.csv;
                }catch(Exception e){return "ERROR "+phase+": "+e.getMessage();}
                finally{child=null;}
            }
            @Override protected void process(List<String> lines){for(String s:lines){log.append(s+"\n");}log.setCaretPosition(log.getDocument().getLength());}
            @Override protected void done(){String result;try{result=get();}catch(Exception ex){result="ERROR: "+ex.getMessage();}
                status.setText(result);log.append(result+"\n");running=false;setBusy.accept(false);updateButtons();}
        }.execute();
    }
}
