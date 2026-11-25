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
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Formulario identico en A6.4 y B2.2: simular -> confirmar -> enlazar por PK interno TC.
 * Sin escrituras hasta el boton de confirmacion. Java8 Swing. */
public final class ManualMatchByIdsPanel extends JPanel {
    private static final long serialVersionUID=1L;
    private static final String MAIN="com.footiqo.manager.ManualMatchByIdsRunner";
    private static final DateTimeFormatter NOW=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");
    private final JTextField lsMatch=new JTextField(10), tcPk=new JTextField(10);
    private final JButton preview=new JButton("1. Simular enlace por IDs"), apply=new JButton("2. Confirmar y enlazar");
    private final JTextArea details=new JTextArea(12,84);
    private final JLabel notice=new JLabel("Introduce ls_match.id y tc_match.id (PK interno, no ID API). Sin limite de dias para revision manual.");
    private final Supplier<String> projectPath,javaPath,allowedTc;
    private final Supplier<Boolean> busy;
    private final Consumer<Boolean> setBusy;
    private final Runnable onSuccess;
    private String snapshot="",signedInputs="",lastPreview="";
    private boolean running;

    public ManualMatchByIdsPanel(Supplier<String> projectPath,Supplier<String> javaPath,Supplier<String> allowedTc,
                                 Supplier<Boolean> busy,Consumer<Boolean> setBusy,Runnable onSuccess){
        super(new BorderLayout(5,6));
        this.projectPath=projectPath;this.javaPath=javaPath;this.allowedTc=allowedTc;
        this.busy=busy;this.setBusy=setBusy;this.onSuccess=onSuccess;
        setBorder(new EmptyBorder(9,8,9,8));
        JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
        JLabel heading=new JLabel("Enlace excepcional entre dos partidos: comprobacion manual");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD,15f));top.add(heading);
        top.add(new JLabel("No rellena marcadores ni fechas. Permite diferencias de dias y marcador ausente en TC, con confirmacion explicita."));
        JPanel inputs=new JPanel(new FlowLayout(FlowLayout.LEFT,8,8));
        inputs.add(new JLabel("ID ls_match:"));inputs.add(lsMatch);
        inputs.add(new JLabel("TC match PK:"));inputs.add(tcPk);
        inputs.add(preview);inputs.add(apply);top.add(inputs);top.add(notice);
        add(top,BorderLayout.NORTH);
        details.setEditable(false);details.setLineWrap(false);
        details.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));
        add(new JScrollPane(details),BorderLayout.CENTER);
        add(new JLabel("Seguridad: liga LS/TC valida, equipos visibles, simulacion vigente, sin enlaces previos y UNIQUE LS/TC."),BorderLayout.SOUTH);
        apply.setEnabled(false);
        javax.swing.event.DocumentListener dl=new javax.swing.event.DocumentListener(){
            public void insertUpdate(javax.swing.event.DocumentEvent e){invalidatePreview();}
            public void removeUpdate(javax.swing.event.DocumentEvent e){invalidatePreview();}
            public void changedUpdate(javax.swing.event.DocumentEvent e){invalidatePreview();}
        };
        lsMatch.getDocument().addDocumentListener(dl);tcPk.getDocument().addDocumentListener(dl);
        preview.addActionListener(e->execute(false));apply.addActionListener(e->execute(true));
    }
    private void invalidatePreview(){snapshot="";signedInputs="";lastPreview="";apply.setEnabled(false);}
    private String inputScope(){return lsMatch.getText().trim()+":"+tcPk.getText().trim()+":"+allowedTc.get();}
    private static boolean validId(String s){return s.matches("[1-9][0-9]{0,14}");}
    private void execute(final boolean write){
        if(running||busy.get())return;
        final String ls=lsMatch.getText().trim(),tc=tcPk.getText().trim(),scope=inputScope();
        final String expected=snapshot;
        final List<String> args=new ArrayList<String>();
        final Path dir;
        try {
            if(!validId(ls)||!validId(tc))throw new IOException("Introduce IDs enteros positivos. TC debe ser PK interno de tc_match.");
            String allowed=allowedTc.get();
            if(allowed==null||allowed.trim().isEmpty())throw new IOException("Selecciona primero una competicion TC de la liga actual.");
            if(write&&(!scope.equals(signedInputs)||!expected.matches("[a-f0-9]{64}")))
                throw new IOException("Necesitas simular primero la misma pareja de IDs y la misma seleccion de ligas.");
            dir=Paths.get(projectPath.get().trim()).toAbsolutePath().normalize();
            Path java=Paths.get(javaPath.get().trim()).toAbsolutePath().normalize();TotalCornerManager.verifyJava8(java);
            Path audit=dir.resolve("reports/totalcorner/manual_match_link_ls"+ls+"_tc"+tc+"_"+NOW.format(LocalDateTime.now())+".csv");
            Collections.addAll(args,java.toString(),"-Dfile.encoding=UTF-8","-cp",
                TotalCornerManager.managerCodeLocation()+File.pathSeparator+TotalCornerManager.buildClasspath(dir), MAIN,
                "--mode="+(write?"APPLY":"PREVIEW"),"--ls-match-id="+ls,"--tc-match-pk="+tc,
                "--allowed-tc-ids="+allowed,"--fingerprint="+(write?expected:"-"),"--audit="+(write?audit.toString():"-"));
        }catch(Exception ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"Enlace manual por IDs",JOptionPane.ERROR_MESSAGE);return;}
        if(write){
            String text="Vas a enlazar manualmente LS="+ls+" con TC PK="+tc+".\n\n"+
                lastPreview+"\n\nLa fecha o el marcador pueden NO coincidir.\n\n"+
                "Has verificado personalmente que se trata del mismo encuentro?";
            if(JOptionPane.showConfirmDialog(this,text,"Confirmar identidad de ambos partidos",JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE)!=JOptionPane.YES_OPTION)return;
            if(JOptionPane.showConfirmDialog(this,"Confirmacion final: INSERT VERIFIED en tc_match_link.\n"+
                "NO se modifican fechas ni marcadores. ¿Aplicar?","Confirmar COMMIT",JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE)!=JOptionPane.YES_OPTION)return;
        }
        running=true;setBusy.accept(true);preview.setEnabled(false);apply.setEnabled(false);
        notice.setText(write?"Aplicando enlace transaccional...":"Consultando ambos partidos (solo lectura)...");
        details.setText("");
        new SwingWorker<Outcome, String>(){
            @Override protected Outcome doInBackground(){
                Outcome out=new Outcome();
                try{
                    ProcessBuilder pb=new ProcessBuilder(args);pb.directory(dir.toFile());pb.redirectErrorStream(true);
                    Process p=pb.start();StringBuilder s=new StringBuilder();
                    try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))){
                        String line;while((line=r.readLine())!=null){s.append(line).append('\n');publish(line);
                            if(line.startsWith("MIDS_PREVIEW_OK\t")){
                                String[] split=line.split("\t");if(split.length>=2)out.fingerprint=split[1];}
                            if(line.startsWith("MIDS_APPLIED\t"))out.applied=true;
                            if(line.startsWith("MIDS_WARNING\t")||line.startsWith("MIDS_LS\t")||line.startsWith("MIDS_TC\t")||line.startsWith("MIDS_TEAMS\t"))out.summary+=line+"\n";
                        }
                    }
                    out.code=p.waitFor();
                    Path log=dir.resolve("target/totalcorner-manager-logs/M-manual-"+(write?"apply":"preview")+"-"+NOW.format(LocalDateTime.now())+".log");
                    Files.createDirectories(log.getParent());Files.write(log,s.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
                    out.log=log.toString();
                }catch(Exception e){out.error=e.getMessage();}
                return out;
            }
            @Override protected void process(List<String> chunks){for(String x:chunks){details.append(x+"\n");details.setCaretPosition(details.getDocument().getLength());}}
            @Override protected void done(){
                Outcome result;
                try{result=get();}catch(Exception ex){result=new Outcome();result.error=ex.getMessage();}
                running=false;setBusy.accept(false);
                preview.setEnabled(true);
                boolean ok=result.code==0&&result.error==null&&scope.equals(inputScope());
                if(!write&&ok&&result.fingerprint.matches("[a-f0-9]{64}")){
                    snapshot=result.fingerprint;signedInputs=scope;lastPreview=result.summary;
                    apply.setEnabled(true);notice.setText("SIMULACION OK. Revisa los datos y confirma el enlace si es correcto.");
                }else if(write&&ok&&result.applied){
                    invalidatePreview();notice.setText("ENLACE VERIFIED CONFIRMADO. Reconsultar pendientes y cobertura.");
                    onSuccess.run();
                    JOptionPane.showMessageDialog(ManualMatchByIdsPanel.this,"Enlace manual aplicado correctamente.\n"+result.log,
                        "Confirmado",JOptionPane.INFORMATION_MESSAGE);
                }else{
                    invalidatePreview();notice.setText("No se ha confirmado el enlace. Lee el log del proceso.");
                    if(result.error!=null)details.append("ERROR: "+result.error+"\n");
                }
            }
        }.execute();
    }
    private static final class Outcome {int code=-1;String fingerprint="",error,log,summary="";boolean applied;}
}
