package com.footiqo.manager;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.function.*;

/** Pestaña transversal: auditoria por liga LS y todas sus asociaciones TC. */
public final class CoverageAuditPanel extends JPanel {
    private static final long serialVersionUID=1L;
    private final Supplier<String> project,java8;
    private final Supplier<Boolean> busy;
    private final Consumer<Boolean> setBusy;
    private final JComboBox<LsChoice> choice=new JComboBox<LsChoice>();
    private final JLabel associations=new JLabel("Selecciona una liga LS");
    private final JLabel totals=new JLabel("Pendiente de auditoría");
    private final JButton refresh=new JButton("Actualizar ligas asociadas");
    private final JButton audit=new JButton("Consultar cobertura (0 API)");
    private final JButton cancel=new JButton("Cancelar consulta");
    private final JTabbedPane views=new JTabbedPane();
    private final DefaultTableModel seasons=model("Temporada","LS total","Enlazados","Sin enlace","Multiples","Con cuotas","Sin cuotas","2 DONE","Parcial");
    private final DefaultTableModel bet365=model("Temporada","Partidos LS","1X2 FT","Goles O/U FT","AH FT","Corners O/U FT","BTTS FT","1X2 1H","Goles O/U 1H","AH 1H","Corners O/U 1H");
    private final JTextArea log=new JTextArea(5,80);
    private volatile Process process;
    private static final class LsChoice {
        final String id;final List<String> tc;final String label;
        LsChoice(String id,List<String> tc,String label){this.id=id;this.tc=tc;this.label=label;}
        public String toString(){return "LS "+id+" · "+label;}
    }
    private static DefaultTableModel model(String...cols){return new DefaultTableModel(cols,0){private static final long serialVersionUID=1L;public boolean isCellEditable(int r,int c){return false;}};}
    private static void append(DefaultTableModel m,String[] parts,int from){String[] row=Arrays.copyOfRange(parts,from,parts.length);m.addRow(row);}
    private static JScrollPane table(DefaultTableModel m){JTable t=new JTable(m);t.setAutoCreateRowSorter(true);t.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);return new JScrollPane(t);}
    CoverageAuditPanel(Supplier<String> project,Supplier<String> java8,Supplier<Boolean> busy,Consumer<Boolean> setBusy){
        super(new BorderLayout(7,7));this.project=project;this.java8=java8;this.busy=busy;this.setBusy=setBusy;
        JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
        top.add(new JLabel("Auditoría global · Denominador: ls_match de la liga LS seleccionada"));
        JPanel controls=new JPanel(new FlowLayout(FlowLayout.LEFT));controls.add(new JLabel("Liga Footiqo:"));controls.add(choice);controls.add(refresh);controls.add(audit);controls.add(cancel);
        top.add(controls);top.add(associations);top.add(totals);add(top,BorderLayout.NORTH);
        views.addTab("Temporadas",table(seasons));views.addTab("Bet365",table(bet365));
        log.setEditable(false);log.setFont(new Font(Font.MONOSPACED,Font.PLAIN,11));
        JSplitPane center=new JSplitPane(JSplitPane.VERTICAL_SPLIT,views,new JScrollPane(log));center.setResizeWeight(.82);add(center,BorderLayout.CENTER);
        choice.addActionListener(e->updateAssociations());refresh.addActionListener(e->loadLeagues());audit.addActionListener(e->runAudit());
        cancel.setEnabled(false);cancel.addActionListener(e->{Process p=process;if(p!=null){p.destroyForcibly();log.append("Solicitud de cancelacion enviada\n");}});loadLeagues();
    }
    private void loadLeagues(){
        Object old=choice.getSelectedItem();String prev=old instanceof LsChoice?((LsChoice)old).id:"";
        Map<String,List<String>> byLs=new TreeMap<String,List<String>>(Comparator.comparingLong(Long::parseLong));
        Map<String,String> labels=new HashMap<String,String>();
        try{
            for(LeagueMappingRegistry.Association a:LeagueMappingRegistry.load().values()){
                byLs.computeIfAbsent(a.lsId,k->new ArrayList<String>()).add(a.tcId);
                labels.putIfAbsent(a.lsId,a.name);
            }
            choice.removeAllItems();for(Map.Entry<String,List<String>> e:byLs.entrySet())choice.addItem(new LsChoice(e.getKey(),e.getValue(),labels.get(e.getKey())));
            for(int i=0;i<choice.getItemCount();i++)if(choice.getItemAt(i).id.equals(prev)){choice.setSelectedIndex(i);break;}
            updateAssociations();
        }catch(Exception ex){associations.setText("Error leyendo registro: "+ex.getMessage());}
    }
    private void updateAssociations(){LsChoice c=(LsChoice)choice.getSelectedItem();associations.setText(c==null?"Sin ligas":"LS "+c.id+" → TC "+String.join(", ",c.tc)+" (todas, estén o no activadas para actualizaciones)");}
    private static final class Report {
        final List<String[]> seasons=new ArrayList<String[]>(),bet365=new ArrayList<String[]>();
        String[] totals;boolean done;String error="";
        void accept(String line){String[] p=line.split("\\|",-1);
            if(p[0].equals("A8_SEASON")&&p.length==10)seasons.add(Arrays.copyOfRange(p,1,10));
            else if(p[0].equals("A8_B365")&&p.length==12)bet365.add(Arrays.copyOfRange(p,1,12));
            else if(p[0].equals("A8_TOTAL")&&p.length==12)totals=p;
            else if(p[0].equals("A8_REPORT_OK"))done=true;
            else if(line.startsWith("A8_ERROR:"))error=line;
        }
    }
    private void runAudit(){
        LsChoice selected=(LsChoice)choice.getSelectedItem();if(selected==null||busy.get())return;
        final List<String> cmd=new ArrayList<String>();final Path dir;
        try{
            dir=Paths.get(project.get().trim()).toAbsolutePath().normalize();Path java=Paths.get(java8.get().trim()).toAbsolutePath().normalize();
            TotalCornerManager.verifyJava8(java);
            if(!Files.isRegularFile(dir.resolve("pom.xml")))throw new IOException("Selecciona el directorio totalcorner-downloader.");
            if(System.getenv("TOTALCORNER_DB_PASSWORD")==null)throw new IOException("Falta TOTALCORNER_DB_PASSWORD.");
            Collections.addAll(cmd,java.toString(),"-Dfile.encoding=UTF-8","-cp",TotalCornerManager.managerCodeLocation()+File.pathSeparator+TotalCornerManager.buildClasspath(dir),
                "com.footiqo.manager.A8CoverageAuditRunner","--ls-league-id="+selected.id,"--tc-leagues="+String.join(",",selected.tc));

        }catch(Exception ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"Auditoría",JOptionPane.ERROR_MESSAGE);return;}
        totals.setText("Consultando LS "+selected.id+" ... (0 API)");audit.setEnabled(false);refresh.setEnabled(false);cancel.setEnabled(true);setBusy.accept(true);
        new SwingWorker<Report,String>(){
            @Override protected Report doInBackground(){
                Report r=new Report();try{
                    process=new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
                    try(BufferedReader br=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){
                        String line;while((line=br.readLine())!=null){r.accept(line);if(!line.startsWith("A8_MATCH|")&&!line.startsWith("A8_B365|"))publish(line);}}
                    int code=process.waitFor();if(code!=0||!r.done||r.totals==null)r.error="Proceso incompleto (exit="+code+") "+r.error;
                }catch(Exception ex){r.error="Auditoria: "+ex.getMessage();}finally{process=null;}return r;
            }
            @Override protected void process(List<String> chunks){for(String line:chunks)log.append(line+"\n");log.setCaretPosition(log.getDocument().getLength());}
            @Override protected void done(){try{
                Report r=get();if(!r.error.isEmpty()){totals.setText("ERROR · "+r.error);return;}
                seasons.setRowCount(0);bet365.setRowCount(0);
                for(String[] row:r.seasons)seasons.addRow(row);
                for(String[] row:r.bet365)bet365.addRow(row);
                String[] t=r.totals;
                double pct=Long.parseLong(t[1])==0?0:100.0*Long.parseLong(t[5])/Long.parseLong(t[1]);
                totals.setText(String.format(Locale.ROOT,"LS: %s | Enlazados: %s | Sin enlace: %s | Multiples: %s | Con cuotas: %s (%.1f%%) | 2 DONE: %s",t[1],t[2],t[3],t[4],t[5],pct,t[7]));
            }catch(Exception ex){totals.setText("ERROR: "+ex.getMessage());}finally{audit.setEnabled(true);refresh.setEnabled(true);cancel.setEnabled(false);setBusy.accept(false);}}
        }.execute();
    }
}
