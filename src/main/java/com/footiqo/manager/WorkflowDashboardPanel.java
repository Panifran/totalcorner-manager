package com.footiqo.manager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Panel de evidencias: ✓ real, — sin trabajo, ! revisar, ○ aun sin confirmar. */
public final class WorkflowDashboardPanel extends JPanel {
    private static final long serialVersionUID=1L;
    private final NewLeaguePanel newLeague;
    private final WorkflowProgress progress;
    private final Supplier<Boolean> currentB12;
    private final Supplier<String> lastB12;
    private final Consumer<Integer> navigate;
    private final JLabel leagueLabel=new JLabel();
    private final JLabel cycleLabel=new JLabel();
    private final JLabel summary=new JLabel();
    private final DefaultTableModel aModel=model();
    private final DefaultTableModel bModel=model();
    private final JTable aTable=new JTable(aModel),bTable=new JTable(bModel);
    private final JButton open=new JButton("Abrir etapa seleccionada");
    private final JButton next=new JButton("Ir al siguiente paso");
    private int nextRoute=10,nextAStage=0;
    private final String[] bSteps={"B1.1","B1.2","B2.1","B2.2","B2.3","B3","B4","B5","B6","B7"};
    public WorkflowDashboardPanel(NewLeaguePanel newLeague,WorkflowProgress progress,
                                  Supplier<Boolean> currentB12,Supplier<String> lastB12,
                                  Consumer<Integer> navigate) {
        super(new BorderLayout(8,8));
        this.newLeague=newLeague;this.progress=progress;this.currentB12=currentB12;this.lastB12=lastB12;this.navigate=navigate;
        setBorder(new EmptyBorder(8,6,8,6));
        JPanel head=new JPanel();head.setLayout(new BoxLayout(head,BoxLayout.Y_AXIS));
        JLabel title=new JLabel("Panel general · Estado de los procesos");
        title.setFont(title.getFont().deriveFont(Font.BOLD,18f));head.add(title);
        head.add(new JLabel("✓ Completado o confirmado   |   — Sin trabajo pendiente   |   ! Necesita revision   |   ○ No confirmado   |   · Por integrar"));
        head.add(Box.createVerticalStrut(5));head.add(leagueLabel);head.add(cycleLabel);
        head.add(Box.createVerticalStrut(5));summary.setFont(summary.getFont().deriveFont(Font.BOLD,12f));head.add(summary);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,6,5));
        JButton refresh=new JButton("Actualizar estados");refresh.addActionListener(e->refreshState());
        actions.add(refresh);actions.add(next);actions.add(open);head.add(actions);
        add(head,BorderLayout.NORTH);
        aTable.setRowHeight(22);bTable.setRowHeight(22);
        setupTable(aTable);setupTable(bTable);
        aTable.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&aTable.getSelectedRow()>=0)bTable.clearSelection();});
        bTable.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&bTable.getSelectedRow()>=0)aTable.clearSelection();});
        JPanel aPane=panel("BLOQUE 01 · Incorporar una liga (por liga)",aTable);
        JPanel bPane=panel("BLOQUE 02 · Actualizar ligas existentes (por ciclo)",bTable);
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,aPane,bPane);split.setResizeWeight(.46);split.setDividerLocation(265);
        add(split,BorderLayout.CENTER);
        JLabel foot=new JLabel("Solo se marcan automáticamente evidencias verificadas. Las simulaciones no cuentan como procesos ejecutados.");
        foot.setBorder(new EmptyBorder(3,0,0,0));add(foot,BorderLayout.SOUTH);
        next.addActionListener(e->{navigate.accept(nextRoute);if(nextRoute==10)newLeague.selectStage(nextAStage);});
        open.addActionListener(e->openSelection());
        refreshState();
    }
    private static DefaultTableModel model() {
        return new DefaultTableModel(new Object[]{"Estado", "Etapa", "Evidencia / siguiente accion"},0) {
            private static final long serialVersionUID=1L;
            @Override public boolean isCellEditable(int r,int c){return false;}
        };
    }
    private static JPanel panel(String title,JTable table) {
        JPanel p=new JPanel(new BorderLayout(4,3));
        JLabel head=new JLabel(title);head.setFont(head.getFont().deriveFont(Font.BOLD,14f));
        p.add(head,BorderLayout.NORTH);p.add(ManagerUiSupport.withTotals(table),BorderLayout.CENTER);return p;
    }
    private static void setupTable(JTable t){
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        t.getTableHeader().setReorderingAllowed(false);
        t.getColumnModel().getColumn(0).setPreferredWidth(93);
        t.getColumnModel().getColumn(0).setMaxWidth(105);
        t.getColumnModel().getColumn(1).setPreferredWidth(170);
        t.getColumnModel().getColumn(1).setMaxWidth(205);
        t.getColumnModel().getColumn(2).setPreferredWidth(800);
        t.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer(){
            private static final long serialVersionUID=1L;
            @Override public Component getTableCellRendererComponent(JTable tb,Object v,boolean selected,boolean focused,int row,int col){
                JLabel result=(JLabel)super.getTableCellRendererComponent(tb,v,selected,focused,row,col);
                result.setFont(result.getFont().deriveFont(Font.BOLD));
                if(!selected){String s=String.valueOf(v);
                    result.setForeground(s.startsWith("✓")||s.startsWith("—")?new Color(18,115,62):
                        s.startsWith("!")?new Color(157,96,0):new Color(95,95,95));}
                return result;
            }
        });
    }
    private static void row(DefaultTableModel model,String status,String title,String note){model.addRow(new Object[]{status,title,note});}
    private void progressRow(String step,String title,String fallback){
        String st=progress.state(step),detail=progress.detail(step),when=progress.when(step);
        if(st.isEmpty())row(bModel,"○ Pendiente",title,fallback);
        else if(WorkflowProgress.DONE.equals(st))row(bModel,"✓ Hecho",title,detail+(when.isEmpty()?"":" | "+when));
        else if(WorkflowProgress.SKIP.equals(st))row(bModel,"— Omitir",title,detail);
        else row(bModel,"! Revisar",title,detail);
    }
    public void refreshState(){
        int selectedA=aTable.getSelectedRow(),selectedB=bTable.getSelectedRow();
        aModel.setRowCount(0);bModel.setRowCount(0);
        leagueLabel.setText("Liga seleccionada (Bloque 01): "+newLeague.leagueLabel());
        cycleLabel.setText("Ciclo del Bloque 02: "+progress.contextLabel());
        boolean a1=newLeague.a1Verified(),a2=newLeague.a2Confirmed();
        row(aModel,a1?"✓ Hecho":"○ Pendiente","A1 · Verificar liga",a1?"ID confirmado con API: "+newLeague.a1When():"Verificar ID TC y nombre contra la API; no basta la ficha web");
        row(aModel,a2?"✓ Hecho":"○ Pendiente","A2 · Pagina inicial",a2?"Pagina 1 DONE: "+newLeague.a2When():"Tras A1, simular y descargar la primera pagina");
        boolean a3=newLeague.a3Confirmed();
        row(aModel,a3?"✓ Hecho":"○ Pendiente","A3 · Calendario completo",
            a3?"Fin de paginacion confirmado: "+newLeague.a3When():
               a2?"Simular pagina pendiente; descargar por lotes hasta fin de paginacion":"Requiere A2 confirmado");
        boolean a4=newLeague.a4Confirmed();
        row(aModel,a4?"✓ Hecho":"○ Pendiente","A4 · Revisar partidos",
             a4?"Auditoria y revision confirmadas: "+newLeague.a4When():
                a3?"Consultar partidos TC y temporadas Footiqo; confirmar tras revisar resultados":"Requiere A3 confirmado");
        boolean a51=newLeague.a51Checked();
        row(aModel,a51?"✓ Hecho":"○ Pendiente","A5.1 · Equipos existentes",
            a51?"Mappings VERIFIED consultados: "+newLeague.a51When():
                a4?"Consultar mappings actuales, sin inserciones":"Requiere A4 confirmado");
        boolean a52=newLeague.a52Checked();
        row(aModel,a52?"✓ CSV":"○ Pendiente","A5.2 · Proponer equipos",
             a52?"CSV generado para revision: "+newLeague.a52When():
                 a51?"Generar propuestas con evidencia de partidos y CSV; NO inserta mappings":"Requiere A5.1 comprobado");
        boolean a53=newLeague.a53Applied();
        row(aModel,a53?"✓ Lote":"○ Pendiente","A5.3 · Enlazar equipos",
            a53?"Ultimo lote confirmado: "+newLeague.a53Count()+" enlaces VERIFIED | "+newLeague.a53When()+". Quedan pendientes por revisar los no aplicados.":
                a52?"Cargar CSV A5.2, seleccionar estados y filas, simular y confirmar insercion":"Requiere CSV A5.2");
        boolean a61=newLeague.a61Checked();
        row(aModel,a61?"✓ CSV":"○ Pendiente","A6.1 · Previsualizar partidos",
            a61?"CSV generado: "+newLeague.a61When()+" | seguros propuestos: "+newLeague.a61Count()+"; no enlazados aun":
                a53?"Consultar enlaces y generar CSV usando ls_match como referencia":"Requiere equipos VERIFIED de A5.3");
        boolean a62=newLeague.a62Applied();
        row(aModel,a62?"✓ Lote":"○ Pendiente","A6.2 · Aplicar partidos seguros",
            a62?"Ultimo lote confirmado: "+newLeague.a62Count()+" enlaces VERIFIED | "+newLeague.a62When():
                a61?"Simular y aplicar los seguros de A6.1":"Requiere CSV A6.1 revisado");
        boolean a63=newLeague.a63Checked();
        row(aModel,a63?"✓ CSV":"○ Pendiente","A6.3 · Revisar pendientes",
            a63?"CSV por liga y margen: "+newLeague.a63When()+" | informe no implica enlaces":
            newLeague.a62Applied()?"Buscar candidatos LS→TC y TC→LS con minutos configurables":"Requiere A6.2 aplicado");
        boolean a64=!newLeague.a64When().isEmpty();
        row(aModel,a64?"✓ Lote":"○ Pendiente","A6.4 · Enlazar manual",
            a64?"Ultimo lote revisado: "+newLeague.a64Count()+" VERIFIED | "+newLeague.a64When()+". Reconsultar A6.3":
            "Marcar parejas individualmente en A6.3, simular y aplicar A6.4");
        row(aModel,"· Futuro","A7 · Descargar cuotas","Datos multi-bookmaker de liga nueva");
        row(aModel,"· Futuro","A8 · Auditar cobertura","Comprobar datos y recalcular tc_cov_*");
        row(aModel,"· Futuro","A9 · Exportar datasets","Generacion comercial y revision antes de publicar");
        progressRow("B1.1","B1.1 · Consultar estado","Consultar al inicio y de nuevo tras B1.2");
        if(currentB12.get()){
            row(bModel,"✓ Hecho","B1.2 · Refrescar calendario","Confirmado para este ciclo, ligas y paginas: "+lastB12.get());
        } else if(WorkflowProgress.DONE.equals(progress.state("B1.2"))){
            row(bModel,"! Revisar","B1.2 · Refrescar calendario","La seleccion de paginas/parametros ha cambiado; repetir B1.2");
        } else {
            row(bModel,"○ Pendiente","B1.2 · Refrescar calendario","Obligatorio por ciclo, aun con 0 pendientes");
        }
        progressRow("B2.1","B2.1 · Revisar enlaces","Consultar candidatos seguros y ambiguos");
        progressRow("B2.2","B2.2 · Aplicar enlaces","Si B2.1 no encuentra pendientes, omitir; si los hay, simular y revisar");
        progressRow("B2.3","B2.3 · Informe de enlaces","Generar informe y examinar incidencias");
        progressRow("B3","B3 · Cuotas","Consultar pendientes; descargar solo si los hay");
        progressRow("B4","B4 · Procesar cuotas (MySQL)","Consultar pendientes y procesar solo si procede");
        progressRow("B5","B5 · ZIP comerciales","Archivado, Exporter y Packager confirmados");
        progressRow("B6","B6 · Informe Coverage","Revision del Excel y archivado final");
        progressRow("B7","B7 · Publicacion","Simulacion, publicacion y comprobacion remota y web");
        if(selectedA>=0&&selectedA<aModel.getRowCount())aTable.setRowSelectionInterval(selectedA,selectedA);
        if(selectedB>=0&&selectedB<bModel.getRowCount())bTable.setRowSelectionInterval(selectedB,selectedB);
        if(!a1){next.setEnabled(true);nextRoute=10;nextAStage=0;summary.setText("Bloque 01: siguiente A1 · Verificar liga con API.");}
        else if(!a2){next.setEnabled(true);nextRoute=10;nextAStage=1;summary.setText("Bloque 01: siguiente A2 · Descargar primera pagina.");}
        else if(!a3){
            nextRoute=10;nextAStage=2;
            summary.setText("Bloque 01: siguiente A3 · Descargar calendario completo por lotes.");
            next.setEnabled(true);
        } else if(!a4) {
            nextRoute=10;nextAStage=3;
            summary.setText("Bloque 01: siguiente A4 · Comprobar partidos y temporadas (solo lectura).");
            next.setEnabled(true);
        } else if(!a51) {
            nextRoute=10;nextAStage=4;
            summary.setText("Bloque 01: siguiente A5.1 · Comprobar mappings existentes de equipos (solo lectura).");
            next.setEnabled(true);
        } else if(!a52){
            nextRoute=10;nextAStage=5;
            summary.setText("Bloque 01: siguiente A5.2 · Proponer equipos por partidos y generar CSV de revision.");
            next.setEnabled(true);
        } else if(!newLeague.a53Applied()) {
            nextRoute=10;nextAStage=6;
            summary.setText("Bloque 01: siguiente A5.3 · Seleccionar candidatos y alias revisados, simular y aplicar.");
            next.setEnabled(true);
        } else {
            nextRoute=10;nextAStage=7;
            if(newLeague.a61Checked())nextAStage=8;
            if(newLeague.a62Applied())nextAStage=9;
            summary.setText(newLeague.a62Applied()?"Bloque 01: siguiente A6.3/A6.4 · Buscar pendientes, marcar y aplicar manualmente.":
                newLeague.a61Checked()?"Bloque 01: siguiente A6.2 · Simular y aplicar candidatos seguros.":
                "Bloque 01: siguiente A6.1 · Previsualizar partidos y revisar CSV.");
            next.setEnabled(true);
        }
    }
    private void openSelection(){
        int a=aTable.getSelectedRow(),b=bTable.getSelectedRow();
        if(a>=0){
            if(a<=10){navigate.accept(10);newLeague.selectStage(Math.min(a,9));}
            else JOptionPane.showMessageDialog(this,"Esta etapa del Bloque 01 todavia no esta integrada en el gestor.","Siguiente modulo",JOptionPane.INFORMATION_MESSAGE);
        } else if(b>=0){navigate.accept(b);}else JOptionPane.showMessageDialog(this,"Selecciona un proceso en una de las tablas.");
    }
}
