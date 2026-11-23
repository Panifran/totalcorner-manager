package com.footiqo.manager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** B7: supervision local WinSCP; PowerShell y Pageant siguen siendo los originales del usuario. */
public final class B7PublishPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String PS1 = "Publicar_Odds.ps1";
    private static final String[] CMDS = {"01_Comprobar_Cambios.cmd", "02_Publicar_Cambios.cmd"};
    private static final String MAP = "mapa_woocommerce.csv";
    private static final String COVERAGE = "Footiqo_Historical_Odds_Coverage.xlsx";
    private static final Pattern ZIP = Pattern.compile("Footiqo_Historical_Odds_(.+)_(Current|Historical)\\.zip");
    private static final Pattern COUNTS = Pattern.compile("unchanged\\s*=\\s*(\\d+)\\s+changed\\s*=\\s*(\\d+)\\s+published\\s*=\\s*(\\d+)",Pattern.CASE_INSENSITIVE);
    private static final Map<Integer,String> LS_SLUGS = new LinkedHashMap<Integer,String>();
    static { LS_SLUGS.put(1,"laliga");LS_SLUGS.put(2,"premier_league");LS_SLUGS.put(3,"serie_a");
        LS_SLUGS.put(4,"bundesliga");LS_SLUGS.put(5,"ligue_1");LS_SLUGS.put(6,"championship");
        LS_SLUGS.put(18,"champions_league");LS_SLUGS.put(28,"europa_league"); }
    private final Supplier<Properties> b5Settings;
    private final Supplier<Boolean> managerBusy;
    private final Consumer<Boolean> setManagerBusy;
    private final JTextField folder = new JTextField(65);
    private final JLabel intro = new JLabel("Publica solo los 16 ZIP de ocho ligas + Coverage XLSX. Sin Bundle ni CSV internos.");
    private final JLabel summary = new JLabel("Paso 1: localizar los cuatro archivos de publicacion y verificar el mapa.");
    private final JTextArea console = new JTextArea(16,100);
    private final JCheckBox approved = new JCheckBox("He completado B5.1, B5 y B6 y revisado los archivos comerciales locales.");
    private final JButton verify = new JButton("1. Verificar mapa y archivos");
    private final JButton dry = new JButton("2. Simular publicacion");
    private final JButton publish = new JButton("3. Publicar cambios (WinSCP)");
    private final JButton postcheck = new JButton("4. Comprobar resultado final");
    private final JButton webcheck = new JButton("5. Abrir Coverage web");
    private final JButton confirmWeb = new JButton("6. Confirmar verificacion web");
    private Consumer<String> onCompletion=message -> {};
    public void setOnCompletion(Consumer<String> listener){onCompletion=listener==null?message -> {}:listener;}
    private final JButton stop = new JButton("Detener simulacion");
    private final DefaultTableModel model = new DefaultTableModel(new String[]{"Publicar", "Archivo local", "Destino remoto"},0) {
        private static final long serialVersionUID=1L;
        @Override public boolean isCellEditable(int r,int c){return false;}
    };
    private final JTable table = new JTable(model);
    private volatile boolean ownBusy,otherBusy,cancelled;
    private volatile Process child;
    private String validatedKey="", simulatedKey="", publishedKey="", finalKey="";
    private boolean publishing;
    private int activeCount=0;
    private final Path preferences = Paths.get(System.getProperty("user.home"),".totalcorner-manager-b7.properties");

    public B7PublishPanel(Supplier<Properties> b5Settings, Supplier<Boolean> managerBusy, Consumer<Boolean> setManagerBusy) {
        super(new BorderLayout(5,5));this.b5Settings=b5Settings;this.managerBusy=managerBusy;this.setManagerBusy=setManagerBusy;
        setBorder(new EmptyBorder(8,5,5,5));
        JPanel head=new JPanel();head.setLayout(new BoxLayout(head,BoxLayout.Y_AXIS));
        JLabel title=new JLabel("B7 - Publicacion incremental en Footiqo (WinSCP)");title.setFont(title.getFont().deriveFont(Font.BOLD,16f));head.add(title);
        head.add(intro);
        JPanel pick=new JPanel(new BorderLayout(6,3));pick.add(new JLabel("Carpeta con scripts"),BorderLayout.WEST);pick.add(folder,BorderLayout.CENTER);
        JButton browse=new JButton("Examinar...");browse.addActionListener(e->browse());pick.add(browse,BorderLayout.EAST);head.add(pick);
        head.add(approved);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,5,2));
        for(JButton b:new JButton[]{verify,dry,publish,postcheck,webcheck,stop})actions.add(b);
        head.add(actions);
        JPanel webRow=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));
        webRow.add(confirmWeb);
        webRow.add(new JLabel("Tras el control final, descarga Coverage y comprueba un ZIP desde WooCommerce."));
        head.add(webRow);
        add(head,BorderLayout.NORTH);
        JPanel center=new JPanel(new BorderLayout(3,3));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setPreferredWidth(75);
        table.getColumnModel().getColumn(1).setPreferredWidth(390);
        table.getColumnModel().getColumn(2).setPreferredWidth(580);
        center.add(ManagerUiSupport.withTotals(table),BorderLayout.NORTH);
        console.setEditable(false);console.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));
        center.add(new JScrollPane(console),BorderLayout.CENTER);add(center,BorderLayout.CENTER);
        summary.setBorder(new EmptyBorder(6,0,6,0));add(summary,BorderLayout.SOUTH);
        verify.addActionListener(e->preflight());dry.addActionListener(e->run("dry"));
        publish.addActionListener(e->run("publish"));postcheck.addActionListener(e->run("final"));
        webcheck.addActionListener(e->openCoverage());stop.addActionListener(e->cancel());
        confirmWeb.addActionListener(e->confirmWebCheck());
        approved.addActionListener(e->updateButtons());
        folder.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){
            private void changed(){validatedKey="";simulatedKey="";publishedKey="";finalKey="";updateButtons();}
            public void insertUpdate(javax.swing.event.DocumentEvent e){changed();}
            public void removeUpdate(javax.swing.event.DocumentEvent e){changed();}
            public void changedUpdate(javax.swing.event.DocumentEvent e){changed();}
        });
        load();updateButtons();
    }
    public void setExternalBusy(boolean v){otherBusy=v;updateButtons();}
    private void updateButtons(){
        boolean ready=!ownBusy&&!otherBusy;
        String activity=ownBusy?"Publicacion o verificacion en curso.":"Hay otro proceso de Manager en curso.";
        String k=safeKey();
        ManagerUiSupport.gate(verify,ready,activity);
        ManagerUiSupport.gate(dry,ready,activity);
        ManagerUiSupport.gate(publish,ready&&approved.isSelected()&&!simulatedKey.isEmpty()&&simulatedKey.equals(k),
            !ready?activity:!approved.isSelected()?"Marca la casilla de autorizacion tras revisar la simulacion.":
            "Falta 2. Simular publicacion con los archivos y rutas actuales.");
        ManagerUiSupport.gate(postcheck,ready&&!publishedKey.isEmpty()&&publishedKey.equals(k),
            !ready?activity:"Falta 3. Publicar cambios y confirmar su resultado.");
        ManagerUiSupport.gate(webcheck,ready,activity);
        ManagerUiSupport.gate(confirmWeb,ready&&!finalKey.isEmpty()&&finalKey.equals(k),
            !ready?activity:"Falta 4. Comprobar el resultado final (SHA-256 remoto).");
        ManagerUiSupport.gate(stop,ownBusy&&!publishing,
            publishing?"No se permite detener una sustitucion remota en curso.":"No hay una simulacion en curso.");
    }
    private void browse(){JFileChooser chooser=new JFileChooser();chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        try {Path path=Paths.get(folder.getText().trim());if(Files.isDirectory(path))chooser.setCurrentDirectory(path.toFile());}catch(Exception ignored){}
        if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){folder.setText(chooser.getSelectedFile().toString());save();}}
    private void load(){try{Properties p=new Properties();if(Files.isRegularFile(preferences))try(InputStream in=Files.newInputStream(preferences)){p.load(in);}folder.setText(p.getProperty("scriptDir",""));}catch(Exception ignored){}}
    private void save(){try{Properties p=new Properties();p.setProperty("scriptDir",folder.getText().trim());try(OutputStream out=Files.newOutputStream(preferences)){p.store(out,"Rutas B7; sin credenciales.");}}catch(Exception ignored){}}
    private void note(String s){console.append(s+"\n");console.setCaretPosition(console.getDocument().getLength());summary.setText(s);}
    private void warning(String s){note("ERROR B7: "+s);JOptionPane.showMessageDialog(this,s,"B7 WinSCP",JOptionPane.WARNING_MESSAGE);}
    private Path scripts()throws IOException{String p=folder.getText().trim();if(p.isEmpty())throw new IOException("Selecciona la carpeta con Publicar_Odds.ps1, .cmd y mapa_woocommerce.csv");
        Path dir=Paths.get(p).toAbsolutePath().normalize();if(!Files.isDirectory(dir))throw new IOException("No existe carpeta: "+dir);return dir;}
    private Path commercial()throws IOException{String value=b5Settings.get().getProperty("commercial","").trim();
        if(value.isEmpty())throw new IOException("Configura carpeta ZIP comerciales en B5");Path p=Paths.get(value).toAbsolutePath().normalize();
        if(!Files.isDirectory(p))throw new IOException("No existe carpeta comercial "+p);return p;}
    private static Map<String,String> expectedSlugs(String ids)throws IOException{
        Set<String> names=new TreeSet<String>();for(String s:ids.split(",")){String part=s.trim();if(!part.matches("\\d+"))throw new IOException("IDs publicadas B5 incorrectas: "+ids);
            String slug=LS_SLUGS.get(Integer.parseInt(part));if(slug==null)throw new IOException("ID LS fuera de las ligas del mapa: "+part);
            if(!names.add(slug))throw new IOException("ID LS repetido: "+part);}
        if(names.size()!=8)throw new IOException("B7 exige las 8 ligas publicadas. B5 tiene "+names.size()+" (revisa Publicadas LS IDs, debe incluir Championship=6).");
        Map<String,String> result=new TreeMap<String,String>();for(String n:names){result.put(n+"_Current",n);result.put(n+"_Historical",n);}return result;
    }
    private static final class Entry {final String local,remote,remoteDir;final boolean enabled;
        Entry(String l,String dir,String r,boolean e){local=l;remoteDir=dir;remote=r;enabled=e;}}
    private static List<Entry> readMap(Path path)throws IOException{
        List<Entry> result=new ArrayList<Entry>();List<String> lines=Files.readAllLines(path,StandardCharsets.UTF_8);
        if(lines.isEmpty())throw new IOException("Mapa vacio");
        String header=lines.get(0).replace("\ufeff","").trim();if(!"Enabled,LocalFile,RemoteDir,RemoteFile".equals(header))
            throw new IOException("Cabecera inesperada del mapa WooCommerce: "+header);
        for(int i=1;i<lines.size();i++){String s=lines.get(i).trim();if(s.isEmpty())continue;
            String[] parts=s.split(",",-1);if(parts.length!=4)throw new IOException("Linea CSV "+(i+1)+": columnas incorrectas (no se admiten comas en rutas)");
            String enabled=parts[0].trim().toLowerCase(Locale.ROOT);
            if(!enabled.equals("true")&&!enabled.equals("false"))throw new IOException("Enabled incorrecto en linea "+(i+1));
            result.add(new Entry(parts[1].trim(),parts[2].trim(),parts[3].trim(),enabled.equals("true")));
        }return result;
    }
    private static void validateCmd(Path dir)throws IOException {
        for(int i=0;i<CMDS.length;i++){
            Path p=dir.resolve(CMDS[i]);if(!Files.isRegularFile(p))throw new IOException("Falta "+CMDS[i]);
            String content=new String(Files.readAllBytes(p),StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if(!content.contains("publicar_odds.ps1")||!content.contains("powershell.exe"))throw new IOException("Script .cmd no coincide con la guia: "+CMDS[i]);
            if(i==0&&content.contains("\" -publish"))throw new IOException("El .cmd de simulacion tiene -Publish: BLOQUEADO");
            if(i==1&&!content.contains("-publish"))throw new IOException("El .cmd real no contiene -Publish");
        }
        if(!Files.isRegularFile(dir.resolve(PS1)))throw new IOException("Falta Publicar_Odds.ps1 en esa carpeta. Los .cmd no funcionan sin el PS1; adjuntalo para revisar la publicacion.");
    }
    private static void verifyZip(Path file)throws IOException {
        try(ZipFile z=new ZipFile(file.toFile())){if(z.size()<1)throw new IOException("ZIP vacio: "+file.getFileName());}
    }
    private List<Entry> inspect(boolean display)throws Exception {
        Path dir=scripts();validateCmd(dir);Path csv=dir.resolve(MAP);if(!Files.isRegularFile(csv))throw new IOException("Falta "+MAP);
        Path commercial=commercial();Properties b5=b5Settings.get();Map<String,String> expected=expectedSlugs(b5.getProperty("publishedIds",""));
        List<Entry> entries=readMap(csv);if(entries.size()!=17)throw new IOException("Mapa debe contener exactamente 17 filas (16 ZIP y Coverage); contiene "+entries.size());
        Set<String> names=new HashSet<String>(),targets=new HashSet<String>();int enabled=0;
        for(Entry entry:entries){String n=entry.local;
            if(!n.matches("[A-Za-z0-9_\\-]+\\.(zip|xlsx)"))throw new IOException("Nombre local no permitido: "+n);
            if(!names.add(n))throw new IOException("Archivo local duplicado: "+n);
            if(!entry.remote.matches("[A-Za-z0-9_\\-]+\\.(zip|xlsx)"))throw new IOException("Nombre remoto no permitido: "+entry.remote);
            if(entry.remoteDir.contains("..")||!entry.remoteDir.startsWith("/var/www/html/wp-content/uploads/"))
                throw new IOException("Ruta remota fuera de uploads: "+entry.remoteDir);
            if(!targets.add(entry.remoteDir+"/"+entry.remote))throw new IOException("Destino remoto duplicado: "+entry.remote);
            Matcher m=ZIP.matcher(n);
            if(!n.equals(COVERAGE)){
                if(!m.matches()||!expected.containsKey(m.group(1)+"_"+m.group(2)))throw new IOException("ZIP no corresponde a ligas publicadas: "+n);
                if(!entry.remote.startsWith(n.substring(0,n.length()-4)+"-"))throw new IOException("Nombre remoto de WooCommerce inesperado para "+n);
                if(!entry.remoteDir.contains("/woocommerce_uploads/"))throw new IOException("ZIP fuera de WooCommerce: "+n);
            }else{
                if(!entry.remote.equals(COVERAGE)||entry.remoteDir.contains("/woocommerce_uploads/"))throw new IOException("Coverage en ruta incorrecta");
            }
            Path local=commercial.resolve(n);if(entry.enabled){
                if(!Files.isRegularFile(local)||Files.size(local)==0)throw new IOException("Archivo comercial ausente o vacio: "+local);
                if(n.endsWith(".zip"))verifyZip(local);
                enabled++;
            }
        }
        for(String k:expected.keySet())if(!names.contains("Footiqo_Historical_Odds_"+k+".zip"))throw new IOException("Falta liga comercial: "+k);
        if(!names.contains(COVERAGE))throw new IOException("Falta Coverage en el mapa");
        if(display){model.setRowCount(0);for(Entry e:entries)model.addRow(new Object[]{e.enabled?"SI":"NO",e.local,e.remoteDir+"/"+e.remote});
            note("MAPA VALIDADO: 17 destinos, "+enabled+" habilitados, 8 ligas y Coverage. No se publica Bundle.");}
        activeCount=enabled;save();return entries;
    }
    private String signature()throws Exception {
        Path dir=scripts();List<Entry> entries=inspect(false);Path commercial=commercial();StringBuilder sb=new StringBuilder(dir.toString()).append('|').append(commercial.toString()).append('|')
            .append(b5Settings.get().getProperty("publishedIds", ""));
        for(String file:new String[]{MAP,PS1,CMDS[0],CMDS[1]}){Path p=dir.resolve(file);sb.append('|').append(file).append('=').append(sha256(p));}
        for(Entry e:entries)if(e.enabled){Path f=commercial.resolve(e.local);sb.append('|').append(e.local).append(':').append(Files.size(f)).append(':').append(Files.getLastModifiedTime(f).toMillis());}
        return sb.toString();
    }
    private String safeKey(){try{return signature();}catch(Exception ignored){return "NOT_READY";}}
    private static String sha256(Path path)throws Exception {MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)){
        byte[] buf=new byte[16384];int n;while((n=in.read(buf))!=-1)d.update(buf,0,n);}
        StringBuilder hex=new StringBuilder();for(byte b:d.digest())hex.append(String.format("%02x",b&255));return hex.toString();}
    private void preflight(){if(ownBusy||managerBusy.get())return;try{inspect(true);validatedKey=signature();
        note("B7 VERIFICADO. Siguiente: Simular publicacion. No se ha conectado a la web.");}
        catch(Exception ex){validatedKey="";simulatedKey="";warning(ex.getMessage());}updateButtons();}
    private void begin(boolean isPublish){publishing=isPublish;ownBusy=true;cancelled=false;setManagerBusy.accept(true);updateButtons();}
    private void end(){ownBusy=false;publishing=false;child=null;setManagerBusy.accept(false);updateButtons();}
    private static final class Outcome {int exit,unchanged=-1,changed=-1,published=-1,oks=0;boolean summary;
        boolean validCounts(int active){return summary&&unchanged>=0&&changed>=0&&published>=0&&unchanged+changed==active;}}
    private void run(String step){
        if(ownBusy||managerBusy.get())return;
        final Path dir,log;final String key;
        try {
            inspect(true);key=signature();dir=scripts();
            if("publish".equals(step)) {
                if(!approved.isSelected())throw new IOException("Confirma que B5.1/B5/B6 estan completados y revisados.");
                if(!key.equals(simulatedKey))throw new IOException("Antes simula la publicacion con los archivos actuales.");
                String value=JOptionPane.showInputDialog(this,
                    "ATENCION: WinSCP sustituira los ficheros remotos modificados de WooCommerce/WordPress.\n"
                    +"Comprueba Pageant, las copias B5.1 y la simulacion.\nEscribe PUBLICAR para confirmar:","Confirmar publicacion B7",JOptionPane.WARNING_MESSAGE);
                if(!"PUBLICAR".equals(value))return;
            }
            if("final".equals(step)&&!key.equals(publishedKey))throw new IOException("Primero ejecuta y confirma la publicacion real del mismo lote.");
            Path logs=dir.resolve("totalcorner-manager-logs");Files.createDirectories(logs);
            log=logs.resolve("B7-"+step+"-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS"))+".log");
        }catch(Exception ex){warning(ex.getMessage());updateButtons();return;}
        final boolean real="publish".equals(step);
        console.setText("=== B7 "+("dry".equals(step)?"SIMULACION":real?"PUBLICACION":"CONTROL FINAL")+" ===\n");
        note("Script: "+dir.resolve(PS1)+"\nLog: "+log+"\nArchivos habilitados: "+activeCount);
        begin(real);
        new SwingWorker<Outcome,String>(){
            @Override protected Outcome doInBackground()throws Exception {
                List<String> cmd=new ArrayList<String>();cmd.add("powershell.exe");cmd.add("-NoProfile");
                cmd.add("-ExecutionPolicy");cmd.add("Bypass");cmd.add("-File");cmd.add(dir.resolve(PS1).toString());
                if(real)cmd.add("-Publish");
                ProcessBuilder pb=new ProcessBuilder(cmd);pb.directory(dir.toFile());pb.redirectErrorStream(true);
                Outcome result=new Outcome();StringBuilder out=new StringBuilder(32768);
                try(BufferedWriter writer=Files.newBufferedWriter(log,StandardCharsets.UTF_8)){
                    child=pb.start();try(BufferedReader reader=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8))){
                        String line;while((line=reader.readLine())!=null){writer.write(line);writer.newLine();out.append(line).append('\n');
                            if(line.contains("PUBLICACION_OK"))result.oks++;if(!isCancelled())publish(line);
                        }
                    }
                    if(!child.waitFor(5,TimeUnit.SECONDS)){child.destroyForcibly();throw new IOException("Publicador no finalizo correctamente");}
                    result.exit=child.exitValue();
                }
                Matcher m=COUNTS.matcher(out);while(m.find()){
                    result.summary=true;result.unchanged=Integer.parseInt(m.group(1));result.changed=Integer.parseInt(m.group(2));result.published=Integer.parseInt(m.group(3));
                }
                return result;
            }
            @Override protected void process(List<String> lines){for(String s:lines){console.append(s+"\n");if(console.getDocument().getLength()>250000)console.replaceRange("[Salida abreviada; log completo en disco]\n",0,75000);}console.setCaretPosition(console.getDocument().getLength());}
            @Override protected void done(){String message;
                try {Outcome r=get();if(cancelled)throw new IOException("Ejecucion interrumpida");
                    if(r.exit!=0)throw new IOException("WinSCP/PowerShell devolvio codigo "+r.exit+". Revisa log.");
                    if(!r.validCounts(activeCount))throw new IOException("No se encontro resumen comprobable (unchanged/changed/published), o el total no coincide con los "+activeCount+" archivos habilitados. Revisa log y Publicar_Odds.ps1.");
                    if(real){
                        if(r.published!=r.changed || r.oks<r.changed)throw new IOException("No se confirmaron todos los ficheros cambiados: changed="+r.changed+", published="+r.published+", PUBLICACION_OK="+r.oks);
                        publishedKey=key;simulatedKey="";finalKey="";
                        message="PUBLICACION B7 CONFIRMADA: "+r.published+" actualizados. Siguiente: comprobar resultado final (paso 4).";
                    }else if("final".equals(step)){
                        if(r.changed!=0||r.published!=0||r.unchanged!=activeCount)throw new IOException("Control final detecta diferencias (changed="+r.changed+"). No marcar B7 completo.");
                        finalKey=key;message="B7 VERIFICADO: "+activeCount+" archivos sin cambios remotos. Falta comprobar descargas web (paso 5).";
                    }else{
                        if(r.published!=0)throw new IOException("El modo simulacion ha declarado published="+r.published+". Bloqueada la publicacion.");
                        simulatedKey=key;publishedKey="";finalKey="";
                        message="SIMULACION B7 OK: "+r.changed+" con cambios, "+r.unchanged+" sin cambios. Siguiente: confirmar y publicar (paso 3).";
                    }
                }catch(Exception ex){message="ERROR B7: "+ex.getMessage();if(real)publishedKey="";if("final".equals(step))finalKey="";}
                end();note(message);
                if(message.startsWith("B7 VERIFICADO"))onCompletion.accept(message);
            }
        }.execute();
    }
    private void cancel(){if(!ownBusy||publishing)return;cancelled=true;Process p=child;if(p!=null)p.destroy();note("Cancelando simulacion... No se autoriza publicacion.");}
    private void confirmWebCheck(){
        if(finalKey.isEmpty() || !finalKey.equals(safeKey())){
            warning("Primero completa la comprobacion remota final (paso 4).");return;
        }
        int ok=JOptionPane.showConfirmDialog(this,
            "Confirma que has descargado el Coverage publico actualizado y al menos un ZIP desde WooCommerce, " +
            "y que ambos contienen los datos esperados.\n¿Has realizado las dos comprobaciones?",
            "Verificacion web B7",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE);
        if(ok==JOptionPane.YES_OPTION){
            String evidence="B7 WEB CONFIRMADA: control remoto SHA-256 y comprobacion manual de descargas.";
            note(evidence);onCompletion.accept(evidence);
        }
    }
    private void openCoverage(){try{if(!Desktop.isDesktopSupported())throw new IOException("Navegador no disponible");
        Desktop.getDesktop().browse(new URI("https://footiqo.com/wp-content/uploads/2026/11/Footiqo_Historical_Odds_Coverage.xlsx"));
        note("Comprueba el Coverage descargado y al menos un ZIP modificado desde WooCommerce.");
    }catch(Exception ex){warning(ex.getMessage());}}
    /** Prueba local sin WinSCP ni conexion: valida el mapa proporcionado, sin publicar. */
    public static void selfTest()throws Exception {
        if(expectedSlugs("1,2,3,4,5,6,18,28").size()!=16)throw new AssertionError("8x2");
        if(expectedSlugs("1,2,3,4,5,6,18,28").containsKey("bundle"))throw new AssertionError("Bundle");
        boolean rejected=false;try{expectedSlugs("1,2,3,4,5,18,28");}catch(IOException ex){rejected=true;}
        if(!rejected)throw new AssertionError("No bloqueo 7 ligas");
        System.out.println("B7 self-test OK: ocho ligas, 16 ZIP, sin Bundle, no publicacion.");
    }
}
