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

/** Bloque 01, A1-A6.4: liga nueva, enlaces seguros y revision manual de partidos. */
public final class NewLeaguePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String VERIFY = "com.footiqo.totalcorner.TotalCornerLeagueIdVerifier";
    private static final String BACKFILL = "com.footiqo.totalcorner.LeagueScheduleBackfill";
    private static final String DISCOVERY = "com.footiqo.totalcorner.HistoricalDownloadRunner";
    private static final String AUDIT = "com.footiqo.manager.A4AuditRunner";
    private static final String TEAMS_A51 = "com.footiqo.manager.A51ExistingTeamsRunner";
    private static final String TEAMS_A52 = "com.footiqo.manager.A52TeamCandidatesRunner";
    private static final String TEAMS_A53 = "com.footiqo.manager.A53TeamLinksRunner";
    private static final String MATCHES_A61 = "com.footiqo.manager.A61MatchPreviewRunner";
    private static final String MATCHES_A62 = "com.footiqo.manager.A62MatchLinksRunner";
    private static final String MATCHES_A63 = "com.footiqo.manager.A63ManualMatchRunner";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");
    private final Supplier<String> projectPath, java8Path;
    private final Supplier<Boolean> managerBusy;
    private final Consumer<Boolean> setManagerBusy;
    private final Runnable onStateChanged;
    private final JTextField lsId = new JTextField("7", 6);
    private final JTextField tcId = new JTextField("85", 7);
    private final JTextField name = new JTextField("USA MLS", 23);
    private final JComboBox<String> savedLeagues = new JComboBox<String>();
    private final JButton saveLeague = new JButton("Guardar liga");
    private final Map<String, String> savedLeagueKeys = new LinkedHashMap<String, String>();
    private final Properties leagueHistory = new Properties();
    private boolean rebuildingLeagues;
    private final JButton simulateA1 = new JButton("1. Simular A1 (0 API / MySQL)");
    private final JButton verifyA1 = new JButton("2. Verificar A1 con API (1 peticion)");
    private final JButton simulateA2 = new JButton("3. Simular A2 (lectura MySQL)");
    private final JButton executeA2 = new JButton("4. Descargar pagina 1 A2");
    private final JButton checkA3 = new JButton("1. Verificar configuracion A3");
    private final JButton simulateA3 = new JButton("2. Consultar pagina pendiente A3");
    private final JButton executeA3 = new JButton("3. Descargar lote A3");
    private final JSpinner requestsA3 = new JSpinner(new SpinnerNumberModel(10, 1, 1000, 1));
    private final JSpinner hoursA3 = new JSpinner(new SpinnerNumberModel(1, 1, 72, 1));
    private final JLabel a3State = new JLabel("A3: despues de completar A2, comprobar pagina pendiente.");
    private final JLabel a3Next = new JLabel("Pagina pendiente: sin consultar");
    private final JButton consultA4 = new JButton("1. Consultar A4 (solo lectura)");
    private final JButton confirmA4 = new JButton("2. Confirmar revision A4");
    private final JLabel a4State = new JLabel("A4: completar A3 para revisar partidos y temporadas.");
    private final JLabel a4Summary = new JLabel("Sin consulta A4 para esta liga.");
    private final JLabel a4Dates = new JLabel("Fechas UTC: pendientes de consulta.");
    private final javax.swing.table.DefaultTableModel a4TableModel = new javax.swing.table.DefaultTableModel(
            new Object[]{"ID temporada LS", "Temporada", "Partidos Footiqo", "Finalizados"}, 0) {
        private static final long serialVersionUID=1L;
        @Override public boolean isCellEditable(int row,int col){return false;}
    };
    private final JTable a4Table = new JTable(a4TableModel);
    private final JButton consultA51 = new JButton("1. Consultar mappings A5.1 (lectura)");
    private final JLabel a51State = new JLabel("A5.1: completar A4 antes de consultar equipos.");
    private final JLabel a51Summary = new JLabel("Pendiente: no se ha consultado el mapping de equipos.");
    private final javax.swing.table.DefaultTableModel a51TableModel = new javax.swing.table.DefaultTableModel(
        new Object[]{"TC PK", "ID API TC", "Equipo TotalCorner", "Estado", "Equipo Footiqo"}, 0) {
        private static final long serialVersionUID=1L;
        @Override public boolean isCellEditable(int r,int c){return false;}
    };
    private final JTable a51Table = new JTable(a51TableModel);
    private final JButton consultA52 = new JButton("1. Generar propuestas A5.2 y CSV");
    private final JTextField a52From = new JTextField("2014-01-01", 10);
    private final JTextField a52To = new JTextField("2027-01-01", 10);
    private final JSpinner a52Tolerance = new JSpinner(new SpinnerNumberModel(5,0,1440,1));
    private final JLabel a52State = new JLabel("A5.2: pendiente de generar propuestas.");
    private final JLabel a52Summary = new JLabel("Los resultados se guardaran en reports/totalcorner; sin escrituras SQL.");
    private final JLabel a52Path = new JLabel("CSV: pendiente.");
    private final javax.swing.table.DefaultTableModel a52TableModel = new javax.swing.table.DefaultTableModel(
        new Object[]{"TC PK", "Equipo TC", "LS ID", "Equipo LS", "Partidos", "Evaluacion"},0) {
        private static final long serialVersionUID=1L;
        @Override public boolean isCellEditable(int r,int c){return false;}
    };
    private final JTable a52Table = new JTable(a52TableModel);
    private final JButton loadA53 = new JButton("1. Cargar CSV A5.2");
    private final JButton selectStrongA53 = new JButton("Solo candidatos con evidencia");
    private final JButton selectAliasA53 = new JButton("Anadir alias revisados");
    private final JButton clearA53 = new JButton("Desmarcar todos");
    private final JButton previewA53 = new JButton("2. Simular enlaces A5.3 (SQL lectura)");
    private final JButton executeA53 = new JButton("3. Aplicar enlaces seleccionados (VERIFIED)");
    private final JLabel a53State = new JLabel("A5.3: pendiente de cargar y revisar CSV A5.2.");
    private final JLabel a53Summary = new JLabel("La seleccion no escribe SQL hasta simular y confirmar la aplicacion.");
    private final javax.swing.table.DefaultTableModel a53TableModel=new javax.swing.table.DefaultTableModel(
        new Object[]{"Enlazar", "TC PK", "Equipo TC", "LS ID", "Equipo Footiqo", "Partidos", "Evaluacion"},0) {
        private static final long serialVersionUID=1L;
        @Override public Class<?> getColumnClass(int c){return c==0?Boolean.class:String.class;}
        @Override public boolean isCellEditable(int r,int c){return c==0 &&
            ("CANDIDATO_CON_EVIDENCIA".equals(getValueAt(r,6))||"POSIBLES_ALIAS_TC_REVISAR".equals(getValueAt(r,6)));}
    };
    private final JTable a53Table = new JTable(a53TableModel);
    private final JButton previewA61 = new JButton("1. Previsualizar partidos A6.1 y generar CSV");
    private final JTextField a61From = new JTextField("2014-01-01", 10);
    private final JTextField a61To = new JTextField("2027-01-01", 10);
    private final JSpinner a61Tolerance = new JSpinner(new SpinnerNumberModel(5,0,1440,1));
    private final JLabel a61State = new JLabel("A6.1: pendiente. No inserta enlaces de partidos.");
    private final JLabel a61Summary = new JLabel("La referencia comercial es ls_match; solo partidos finalizados.");
    private final JLabel a61Path = new JLabel("CSV: pendiente.");
    private final javax.swing.table.DefaultTableModel a61TableModel = new javax.swing.table.DefaultTableModel(
        new Object[]{"Evaluacion", "Numero de parejas / partidos"},0) {
        private static final long serialVersionUID=1L;
        @Override public boolean isCellEditable(int r,int c){return false;}
    };
    private final JTable a61Table = new JTable(a61TableModel);
    private final JButton previewA62=new JButton("1. Simular enlaces seguros A6.2");
    private final JButton executeA62=new JButton("2. Aplicar enlaces A6.2 (VERIFIED)");
    private final JLabel a62State=new JLabel("A6.2: requiere CSV A6.1 revisado.");
    private final JLabel a62Summary=new JLabel("A6.2 solo inserta LISTO_PARA_INSERTAR.");
    private final JButton reportA63=new JButton("1. Buscar pendientes A6.3 + CSV");
    private final JButton clearA63=new JButton("Desmarcar todos");
    private final JButton markSafeA63=new JButton("Marcar unicos seguros");
    private final JButton markVisibleA63=new JButton("Marcar visibles (revision)");
    private final JSpinner a63SelectMinutes=new JSpinner(new SpinnerNumberModel(100,1,14400,1));
    private final JButton previewA64=new JButton("2. Simular seleccion manual A6.4");
    private final JButton executeA64=new JButton("3. Aplicar seleccion manual A6.4");
    private final JTextField a63From=new JTextField("2015-01-01",10), a63To=new JTextField("2027-01-01",10);
    private final JSpinner a63Tolerance=new JSpinner(new SpinnerNumberModel(120,1,14400,5));
    private final JSpinner a63Max=new JSpinner(new SpinnerNumberModel(10,1,30,1));
    private final JComboBox<String> a63Filter=new JComboBox<String>(new String[]{"TODOS","CANDIDATO","REVISAR_MAPPING","REVISAR_MARCADOR","AMBIGUO","SOLO_LS","SOLO_TC"});
    private final JLabel a63State=new JLabel("A6.3: pendiente de consultar los partidos no enlazados.");
    private final JLabel a63Summary=new JLabel("Footiqo es la referencia; el informe TC no implica que falten datos LS.");
    private final JLabel a63Path=new JLabel("CSV: pendiente.");
    private final JLabel a64State=new JLabel("A6.4: marcar parejas, simular y confirmar antes de aplicar.");
    private final javax.swing.table.DefaultTableModel a63TableModel=new javax.swing.table.DefaultTableModel(
        new Object[]{"Enlazar","Tipo","LS ID","TC PK","ID TC API","Fecha LS","Fecha TC","Delta min","Local LS","Visitante LS","FT LS","Local TC","Visitante TC","FT TC","Evidencia","Local VERIFIED","Visitante VERIFIED","Zona TC","Avisos"},0){
        private static final long serialVersionUID=1L;
        @Override public Class<?> getColumnClass(int c){return c==0?Boolean.class:String.class;}
        @Override public boolean isCellEditable(int r,int c){return c==0&&!"SOLO_LS".equals(getValueAt(r,1))&&!"SOLO_TC".equals(getValueAt(r,1));}
    };
    private final JTable a63Table=new JTable(a63TableModel);
    private final Map<String,String> a63Fingerprints=new LinkedHashMap<String,String>();
    private String a63ConsultKey="",a63ConsultScope="",a64PreviewSignature="";
    private boolean loadingA63;
    private String a62PreviewSignature="";
    private String a61ConsultKey="",a61ConsultScope="";
    private String a53LoadedCsv="",a53LoadedScope="",a53LoadedKey="",a53PreviewSignature="";
    private String a52ConsultKey="";
    private String a51ConsultKey="";
    private String a4ConsultKey="",a4Evidence="";
    private boolean a4ConsultOk=false;
    private String a3PreviewSignature="", a3PreviewPage="";
    private final JButton stop = new JButton("Detener proceso");
    private final JLabel a1State = new JLabel("A1: candidato web, pendiente de verificar API.");
    private final JLabel a2State = new JLabel("A2: bloqueado hasta verificar A1 por API.");
    private final JTextArea log = new JTextArea(15, 98);
    private final JTabbedPane stages = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
    private final Path saved = Paths.get(System.getProperty("user.home"), ".totalcorner-manager-new-league.properties");
    private String verifiedKey = "", pageDoneKey = "", verifiedAt = "", pageDoneAt = "";
    private volatile boolean ownBusy, otherBusy, cancelled;
    private volatile Process process;

    public NewLeaguePanel(Supplier<String> projectPath, Supplier<String> java8Path,
                         Supplier<Boolean> managerBusy, Consumer<Boolean> setManagerBusy,
                         Runnable onStateChanged) {
        super(new BorderLayout(5, 6));
        this.projectPath = projectPath; this.java8Path = java8Path;
        this.managerBusy = managerBusy; this.setManagerBusy = setManagerBusy;
        this.onStateChanged = onStateChanged;
        setBorder(new EmptyBorder(8, 4, 6, 4));
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("BLOQUE 01 - Incorporar liga nueva");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        top.add(title);
        top.add(new JLabel("Selecciona una liga o introduce sus IDs. Ejemplo inicial: USA MLS (LS 7 / TC 85 sin verificar)."));
        JPanel fields = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        fields.add(new JLabel("LS ID:")); fields.add(lsId);
        fields.add(new JLabel("TC ID:")); fields.add(tcId);
        fields.add(new JLabel("Nombre esperado API:")); fields.add(name);
        top.add(fields);
        JPanel presets = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        presets.add(new JLabel("Ligas guardadas:"));
        savedLeagues.setPreferredSize(new Dimension(450, 26));
        presets.add(savedLeagues);
        presets.add(saveLeague);
        top.add(presets);
        add(top, BorderLayout.NORTH);

        JPanel a1 = new JPanel(new BorderLayout(3, 3));
        JLabel a1Info = new JLabel("A1 no escribe MySQL; guarda CSV y JSON .gz. Debe devolver HTTP 200, ID y nombre correctos, estado OK.");
        JPanel a1Head = new JPanel(); a1Head.setLayout(new BoxLayout(a1Head, BoxLayout.Y_AXIS));
        a1Head.add(a1Info);
        JPanel actions1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        actions1.add(simulateA1); actions1.add(verifyA1); a1Head.add(actions1); a1Head.add(a1State);
        a1.add(a1Head, BorderLayout.NORTH);
        a1.add(new JLabel("Archivos de auditoria: totalcorner-downloader/reports y samples/totalcorner/league_id_probe"), BorderLayout.CENTER);
        stages.addTab("A1. Identificar y verificar liga", a1);

        JPanel a2 = new JPanel(new BorderLayout(3, 3));
        JPanel a2Head = new JPanel(); a2Head.setLayout(new BoxLayout(a2Head, BoxLayout.Y_AXIS));
        a2Head.add(new JLabel("A2 usa LeagueScheduleBackfill: pagina 1 reanudable. Solo --execute realiza API y escrituras TC; nunca LS."));
        JPanel actions2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        actions2.add(simulateA2); actions2.add(executeA2); a2Head.add(actions2); a2Head.add(a2State);
        a2.add(a2Head, BorderLayout.NORTH);
        a2.add(new JLabel("Tras A2: consultar la pagina pendiente en A3. No repetir paginas DONE."), BorderLayout.CENTER);
        stages.addTab("A2. Descargar primera pagina", a2);

        JPanel a3 = new JPanel(new BorderLayout(3, 3));
        JPanel a3Head = new JPanel();a3Head.setLayout(new BoxLayout(a3Head, BoxLayout.Y_AXIS));
        a3Head.add(new JLabel("A3: HistoricalDownloadRunner --phase=discovery. Configuracion propia por liga; no cambia el properties general."));
        JPanel opts3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 3));
        opts3.add(new JLabel("Peticiones maximas por lote:"));opts3.add(requestsA3);
        opts3.add(new JLabel("Horas maximas:"));opts3.add(hoursA3);
        a3Head.add(opts3);
        JPanel actions3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        actions3.add(checkA3);actions3.add(simulateA3);actions3.add(executeA3);
        a3Head.add(actions3);a3Head.add(a3State);a3Head.add(a3Next);a3.add(a3Head,BorderLayout.NORTH);
        a3.add(new JLabel("El lote puede terminar por limite de peticiones; A3 solo se confirma con fin de paginacion."),BorderLayout.CENTER);
        stages.addTab("A3. Calendario completo",a3);

        JPanel a4 = new JPanel(new BorderLayout(4,4));
        JPanel a4Head = new JPanel();a4Head.setLayout(new BoxLayout(a4Head,BoxLayout.Y_AXIS));
        a4Head.add(new JLabel("A4: comprobar partidos TC (equipos/fechas) y temporadas Footiqo. Solo SELECT, sin API ni escrituras."));
        JPanel a4Actions=new JPanel(new FlowLayout(FlowLayout.LEFT,6,4));
        a4Actions.add(consultA4);a4Actions.add(confirmA4);a4Head.add(a4Actions);
        a4Head.add(a4State);a4Head.add(a4Summary);a4Head.add(a4Dates);
        a4.add(a4Head,BorderLayout.NORTH);
        a4Table.setRowHeight(23);a4Table.setFillsViewportHeight(true);
        a4.add(ManagerUiSupport.withTotals(a4Table),BorderLayout.CENTER);
        stages.addTab("A4. Comprobar partidos/temporadas",a4);

        JPanel a51 = new JPanel(new BorderLayout(4,4));
        JPanel a51Head = new JPanel(); a51Head.setLayout(new BoxLayout(a51Head,BoxLayout.Y_AXIS));
        a51Head.add(new JLabel("A5.1: comprobar enlaces VERIFIED existentes con equipos que figuran en ls_match de la liga."));
        a51Head.add(new JLabel("Solo lectura: los equipos SIN_LINK_VERIFICADO no se insertan; A5.2 propondrá candidatos por partidos."));
        JPanel a51Actions = new JPanel(new FlowLayout(FlowLayout.LEFT,6,4));
        a51Actions.add(consultA51);a51Head.add(a51Actions);a51Head.add(a51State);a51Head.add(a51Summary);
        a51.add(a51Head,BorderLayout.NORTH);
        a51Table.setRowHeight(23);a51Table.setFillsViewportHeight(true);
        a51.add(ManagerUiSupport.withTotals(a51Table),BorderLayout.CENTER);
        stages.addTab("A5.1. Comprobar equipos",a51);

        JPanel a52 = new JPanel(new BorderLayout(4,4));
        JPanel a52Head = new JPanel();a52Head.setLayout(new BoxLayout(a52Head,BoxLayout.Y_AXIS));
        a52Head.add(new JLabel("A5.2: propuestas por partidos, fecha Europe/Madrid; CSV separado por comas; no enlaza equipos."));
        a52Head.add(new JLabel("El CSV incluye casos sin candidato y posibles alias; A5.3 aplicara solo tras revision."));
        JPanel a52Options=new JPanel(new FlowLayout(FlowLayout.LEFT,6,3));
        a52Options.add(new JLabel("Desde (Madrid):"));a52Options.add(a52From);
        a52Options.add(new JLabel("Hasta (exclusivo):"));a52Options.add(a52To);
        a52Options.add(new JLabel("Tolerancia (min):"));a52Options.add(a52Tolerance);
        a52Head.add(a52Options);
        JPanel a52Actions=new JPanel(new FlowLayout(FlowLayout.LEFT,6,4));
        a52Actions.add(consultA52);a52Head.add(a52Actions);
        a52Head.add(a52State);a52Head.add(a52Summary);a52Head.add(a52Path);
        a52.add(a52Head,BorderLayout.NORTH);
        a52Table.setAutoCreateRowSorter(true);a52Table.setRowHeight(23);a52Table.setFillsViewportHeight(true);
        a52.add(ManagerUiSupport.withTotals(a52Table),BorderLayout.CENTER);
        stages.addTab("A5.2. Proponer equipos + CSV",a52);

        JPanel a53=new JPanel(new BorderLayout(4,4));
        JPanel a53Head=new JPanel();a53Head.setLayout(new BoxLayout(a53Head,BoxLayout.Y_AXIS));
        a53Head.add(new JLabel("A5.3: seleccion por tipo Y por fila. Alias: varias identidades TC pueden corresponder al mismo equipo Footiqo."));
        a53Head.add(new JLabel("No se aplica nada sin simular y confirmar. El runner comprueba la evidencia actual y realiza transaccion o ROLLBACK."));
        JPanel a53Selection=new JPanel(new FlowLayout(FlowLayout.LEFT,6,4));
        a53Selection.add(loadA53);a53Selection.add(selectStrongA53);a53Selection.add(selectAliasA53);a53Selection.add(clearA53);
        a53Head.add(a53Selection);
        JPanel a53Actions=new JPanel(new FlowLayout(FlowLayout.LEFT,6,4));
        a53Actions.add(previewA53);a53Actions.add(executeA53);a53Head.add(a53Actions);
        a53Head.add(a53State);a53Head.add(a53Summary);
        a53.add(a53Head,BorderLayout.NORTH);
        a53Table.setAutoCreateRowSorter(true);a53Table.setRowHeight(23);a53Table.setFillsViewportHeight(true);
        a53Table.getColumnModel().getColumn(0).setPreferredWidth(68);
        a53Table.getColumnModel().getColumn(0).setMaxWidth(75);
        a53.add(ManagerUiSupport.withTotals(a53Table),BorderLayout.CENTER);
        stages.addTab("A5.3. Revisar y aplicar equipos",a53);

        JPanel a61=new JPanel(new BorderLayout(4,4));
        JPanel a61Head=new JPanel();a61Head.setLayout(new BoxLayout(a61Head,BoxLayout.Y_AXIS));
        a61Head.add(new JLabel("A6.1: partidos Footiqo finalizados como referencia; equipos VERIFIED, horario, marcador y unicidad 1:1."));
        a61Head.add(new JLabel("Solo SELECT. El CSV incluye sin candidato y revision; A6.2 aplicara los enlaces seguros tras comprobarlos."));
        JPanel a61Options=new JPanel(new FlowLayout(FlowLayout.LEFT,6,4));
        a61Options.add(new JLabel("Desde Madrid:"));a61Options.add(a61From);
        a61Options.add(new JLabel("Hasta exclusivo:"));a61Options.add(a61To);
        a61Options.add(new JLabel("Tolerancia min:"));a61Options.add(a61Tolerance);
        a61Head.add(a61Options);
        JPanel a61Actions=new JPanel(new FlowLayout(FlowLayout.LEFT,6,4));a61Actions.add(previewA61);a61Head.add(a61Actions);
        a61Head.add(a61State);a61Head.add(a61Summary);a61Head.add(a61Path);
        a61.add(a61Head,BorderLayout.NORTH);
        a61Table.setAutoCreateRowSorter(true);a61Table.setRowHeight(23);a61Table.setFillsViewportHeight(true);
        a61.add(ManagerUiSupport.withTotals(a61Table),BorderLayout.CENTER);
        stages.addTab("A6.1. Previsualizar partidos + CSV",a61);
        JPanel a62=new JPanel(new BorderLayout(4,4));
        JPanel a62Head=new JPanel();a62Head.setLayout(new BoxLayout(a62Head,BoxLayout.Y_AXIS));
        a62Head.add(new JLabel("A6.2: solo candidatos SEGUROS de A6.1; equipos VERIFIED, horario, marcador y unicidad 1:1."));
        a62Head.add(new JLabel("La simulacion recalcula los candidatos en MySQL. No se ejecutan consultas API."));
        JPanel a62Actions=new JPanel(new FlowLayout(FlowLayout.LEFT,6,4));
        a62Actions.add(previewA62);a62Actions.add(executeA62);a62Head.add(a62Actions);
        a62Head.add(a62State);a62Head.add(a62Summary);
        a62.add(a62Head,BorderLayout.NORTH);
        a62.add(new JLabel("Partidos ambiguos, sin marcador coincidente o sin candidato: revisar en A6.3."),BorderLayout.CENTER);
        stages.addTab("A6.2. Aplicar enlaces seguros",a62);

        JPanel a63=new JPanel(new BorderLayout(5,5));
        JPanel a63Head=new JPanel();a63Head.setLayout(new BoxLayout(a63Head,BoxLayout.Y_AXIS));
        a63Head.add(new JLabel("A6.3 · Buscar pendientes en ambos sentidos. A6.4 · Enlazar SOLO las parejas revisadas y marcadas."));
        a63Head.add(new JLabel("CANDIDATO: ambos equipos VERIFIED, mismo marcador y pareja unica 1:1. Otros tipos: revision manual obligatoria."));
        JPanel a63Options=new JPanel(new FlowLayout(FlowLayout.LEFT,5,3));
        a63Options.add(new JLabel("Desde Madrid:"));a63Options.add(a63From);
        a63Options.add(new JLabel("Hasta excl.:"));a63Options.add(a63To);
        a63Options.add(new JLabel("Margen min:"));a63Options.add(a63Tolerance);
        a63Options.add(new JLabel("Max candidatos/LS:"));a63Options.add(a63Max);
        a63Options.add(new JLabel("Ver:"));a63Options.add(a63Filter);a63Head.add(a63Options);
        JPanel a63Actions=new JPanel(new FlowLayout(FlowLayout.LEFT,5,4));
        a63Actions.add(reportA63);a63Actions.add(clearA63);a63Actions.add(previewA64);a63Actions.add(executeA64);
        a63Head.add(a63Actions);
        JPanel a63Bulk=new JPanel(new FlowLayout(FlowLayout.LEFT,5,3));
        a63Bulk.add(new JLabel("Max minutos para marcar:"));a63Bulk.add(a63SelectMinutes);
        a63Bulk.add(markSafeA63);a63Bulk.add(markVisibleA63);
        a63Head.add(a63Bulk);a63Head.add(a63State);a63Head.add(a63Summary);a63Head.add(a64State);a63Head.add(a63Path);
        a63.add(a63Head,BorderLayout.NORTH);
        a63Table.setAutoCreateRowSorter(true);a63Table.setRowHeight(23);a63Table.setFillsViewportHeight(true);
        a63Table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        a63Table.getColumnModel().getColumn(0).setPreferredWidth(60);
        a63Table.getColumnModel().getColumn(1).setPreferredWidth(95);
        a63Table.getColumnModel().getColumn(14).setPreferredWidth(240);
        a63Table.getColumnModel().getColumn(18).setPreferredWidth(270);
        a63.add(ManagerUiSupport.withTotals(a63Table),BorderLayout.CENTER);
        stages.addTab("A6.3–A6.4. Revisar y enlazar manual",a63);

        JPanel center = new JPanel(new BorderLayout(4, 4));
        stages.setPreferredSize(new Dimension(900, 290));
        // La revision manual necesita visualizar muchas columnas y filas; ampliar solo A6.3/A6.4.
        stages.addChangeListener(e->{int height=stages.getSelectedIndex()==9?510:290;
            if(stages.getPreferredSize().height!=height){stages.setPreferredSize(new Dimension(900,height));stages.revalidate();}});
        center.add(stages, BorderLayout.NORTH);
        log.setEditable(false); log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        center.add(new JScrollPane(log), BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottom.add(stop); bottom.add(new JLabel("El codigo de descarga existente no se modifica. La configuracion original de ligas tampoco."));
        add(bottom, BorderLayout.SOUTH);
        simulateA1.addActionListener(e -> run("a1-simulate"));
        verifyA1.addActionListener(e -> run("a1-verify"));
        simulateA2.addActionListener(e -> run("a2-simulate"));
        executeA2.addActionListener(e -> run("a2-execute"));
        checkA3.addActionListener(e -> runA3("check"));
        simulateA3.addActionListener(e -> runA3("preview"));
        executeA3.addActionListener(e -> runA3("execute"));
        consultA4.addActionListener(e -> runA4());
        confirmA4.addActionListener(e -> confirmA4());
        consultA51.addActionListener(e -> runA51());
        consultA52.addActionListener(e -> runA52());
        loadA53.addActionListener(e -> loadA53());
        selectStrongA53.addActionListener(e -> selectA53("CANDIDATO_CON_EVIDENCIA",false));
        selectAliasA53.addActionListener(e -> {
            if(JOptionPane.showConfirmDialog(this,"Los alias permiten VINCULAR varios IDs TC con un mismo equipo LS.\n"
                +"Selecciona solo los alias que hayas revisado individualmente en A5.2.\n"
                +"Si hay dudas, desmarca su fila antes de simular.","Confirmar seleccion de alias",
                JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)==JOptionPane.YES_OPTION)
                selectA53("POSIBLES_ALIAS_TC_REVISAR",true);
        });
        clearA53.addActionListener(e -> {for(int r=0;r<a53TableModel.getRowCount();r++)a53TableModel.setValueAt(false,r,0);});
        previewA53.addActionListener(e -> runA53(false));
        executeA53.addActionListener(e -> runA53(true));
        previewA61.addActionListener(e -> runA61());
        previewA62.addActionListener(e -> runA62(false));
        executeA62.addActionListener(e -> runA62(true));
        reportA63.addActionListener(e -> runA63());
        clearA63.addActionListener(e -> {for(int i=0;i<a63TableModel.getRowCount();i++)a63TableModel.setValueAt(false,i,0);});
        markSafeA63.addActionListener(e -> selectA63(true));
        markVisibleA63.addActionListener(e -> selectA63(false));
        previewA64.addActionListener(e -> runA64(false));
        executeA64.addActionListener(e -> runA64(true));
        a63TableModel.addTableModelListener(e -> {if(!loadingA63){a64PreviewSignature="";refresh();}});
        a63Filter.addActionListener(e -> filterA63());

        a53TableModel.addTableModelListener(e -> {a53PreviewSignature="";refresh();});
        saveLeague.addActionListener(e -> {
            if(!lsId.getText().trim().matches("[0-9]{1,15}")||
               !tcId.getText().trim().matches("[0-9]{1,15}")||name.getText().trim().isEmpty()) {
                JOptionPane.showMessageDialog(this,"Indica ambos IDs numericos y el nombre esperado de la liga.");return;
            }
            String id="league."+encode(key());
            leagueHistory.setProperty(id+".ls",lsId.getText().trim());
            leagueHistory.setProperty(id+".tc",tcId.getText().trim());
            leagueHistory.setProperty(id+".name",name.getText().trim());
            rebuildSavedLeagues();saveState();
            note("Liga guardada sin confirmar API: "+leagueLabel());
        });
        stop.addActionListener(e -> {
            Process p=process;
            if(p!=null && JOptionPane.showConfirmDialog(this,"Detener proceso en curso?","Confirmar",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION) {
                cancelled=true;p.destroy();
            }
        });
        javax.swing.event.DocumentListener document = new javax.swing.event.DocumentListener() {
            private void changed(){refresh();}
            public void insertUpdate(javax.swing.event.DocumentEvent e){changed();}
            public void removeUpdate(javax.swing.event.DocumentEvent e){changed();}
            public void changedUpdate(javax.swing.event.DocumentEvent e){changed();}
        };
        lsId.getDocument().addDocumentListener(document);
        tcId.getDocument().addDocumentListener(document);
        name.getDocument().addDocumentListener(document);
        a52From.getDocument().addDocumentListener(document);
        a52To.getDocument().addDocumentListener(document);
        a52Tolerance.addChangeListener(e->refresh());
        a61From.getDocument().addDocumentListener(document);
        a61To.getDocument().addDocumentListener(document);
        a61Tolerance.addChangeListener(e->refresh());
        a63From.getDocument().addDocumentListener(document);
        a63To.getDocument().addDocumentListener(document);
        a63Tolerance.addChangeListener(e->refresh());
        a63Max.addChangeListener(e->refresh());
        savedLeagues.addActionListener(e -> {
            if(rebuildingLeagues)return;
            String selection=(String)savedLeagues.getSelectedItem();
            String stored=savedLeagueKeys.get(selection);
            if(stored!=null) {
                String[] fieldsForLeague=stored.split("\\|",3);
                if(fieldsForLeague.length==3){lsId.setText(fieldsForLeague[0]);tcId.setText(fieldsForLeague[1]);name.setText(fieldsForLeague[2]);restorePersistedScopes();}
            }
        });
        loadState(); rebuildSavedLeagues(); refresh();
    }
    public void selectStage(int index) { stages.setSelectedIndex(Math.max(0, Math.min(stages.getTabCount()-1,index))); }
    public String leagueLabel() { return "LS="+lsId.getText().trim()+" / TC="+tcId.getText().trim()+" / "+name.getText().trim(); }
    public boolean a1Verified() {return !a1When().isEmpty();}
    public boolean a2Confirmed() {return a1Verified() && !a2When().isEmpty();}
    public String a1When(){return leagueHistory.getProperty("league."+encode(key())+".a1", "");}
    public String a2When(){return leagueHistory.getProperty("league."+encode(key())+".a2", "");}
    public String a3When(){return leagueHistory.getProperty("league."+encode(key())+".a3", "");}
    public boolean a3Confirmed(){return a2Confirmed() && !a3When().isEmpty();}
    public String a4When(){return leagueHistory.getProperty("league."+encode(key())+".a4", "");}
    public boolean a4Confirmed(){return a3Confirmed() && !a4When().isEmpty();}
    public String a51When(){return leagueHistory.getProperty("league."+encode(key())+".a51", "");}
    public boolean a51Checked(){return a4Confirmed() && !a51When().isEmpty();}
    public String a52When(){return leagueHistory.getProperty("league."+encode(key())+".a52", "");}
    public String a52Csv(){return leagueHistory.getProperty("league."+encode(key())+".a52.csv", "");}
    private String a52Scope(){return a52From.getText().trim()+"|"+a52To.getText().trim()+"|"+a52Tolerance.getValue();}
    public boolean a52Checked(){
        String prefix="league."+encode(key());
        return a51Checked() && !a52When().isEmpty() &&
            a52Scope().equals(leagueHistory.getProperty(prefix+".a52.scope","")) &&
            !a52Csv().isEmpty() && Files.isRegularFile(Paths.get(a52Csv()));
    }
    public String a53When(){return leagueHistory.getProperty("league."+encode(key())+".a53", "");}
    public String a53Count(){return leagueHistory.getProperty("league."+encode(key())+".a53.count", "");}
    public boolean a53Applied(){return a52Checked() && !a53When().isEmpty();}
    private String a61Scope(){return a61From.getText().trim()+"|"+a61To.getText().trim()+"|"+a61Tolerance.getValue();}
    public String a61When(){return leagueHistory.getProperty("league."+encode(key())+".a61","");}
    public String a61Csv(){return leagueHistory.getProperty("league."+encode(key())+".a61.csv","");}
    public String a61Count(){return leagueHistory.getProperty("league."+encode(key())+".a61.count","");}
    public boolean a61Checked(){String prefix="league."+encode(key());return a53Applied()&&!a61When().isEmpty()&&
        a61Scope().equals(leagueHistory.getProperty(prefix+".a61.scope",""))&&
        a53When().equals(leagueHistory.getProperty(prefix+".a61.team_version",""))&&
        !a61Csv().isEmpty()&&Files.isRegularFile(Paths.get(a61Csv()));}

    public String a62When(){return leagueHistory.getProperty("league."+encode(key())+".a62","");}
    public String a62Count(){return leagueHistory.getProperty("league."+encode(key())+".a62.count","");}
    public boolean a62Applied(){return a53Applied()&&!a62When().isEmpty();}
    public String a63When(){return leagueHistory.getProperty("league."+encode(key())+".a63","");}
    public String a63Csv(){return leagueHistory.getProperty("league."+encode(key())+".a63.csv","");}
    private String a63Scope(){return a63From.getText().trim()+"|"+a63To.getText().trim()+"|"+a63Tolerance.getValue()+"|"+a63Max.getValue();}
    public boolean a63Checked(){return a62Applied()&&!a63When().isEmpty()&&a63Scope().equals(leagueHistory.getProperty("league."+encode(key())+".a63.scope",""))&&
        !a63Csv().isEmpty()&&Files.isRegularFile(Paths.get(a63Csv()));}
    public String a64When(){return leagueHistory.getProperty("league."+encode(key())+".a64","");}
    public String a64Count(){return leagueHistory.getProperty("league."+encode(key())+".a64.count","");}
    private String a63Signature(){
        if(!a63Checked()||!key().equals(a63ConsultKey)||!a63Scope().equals(a63ConsultScope))return "";
        StringBuilder b=new StringBuilder(key()).append('|').append(a63Scope()).append('|').append(a63Csv());
        for(int i=0;i<a63TableModel.getRowCount();i++)if(Boolean.TRUE.equals(a63TableModel.getValueAt(i,0))){
            String p=a63TableModel.getValueAt(i,2)+":"+a63TableModel.getValueAt(i,3);
            b.append('|').append(p).append(':').append(a63Fingerprints.get(p));}
        return b.toString();
    }
    private int a63SelectedCount(){int n=0;for(int i=0;i<a63TableModel.getRowCount();i++)
        if(Boolean.TRUE.equals(a63TableModel.getValueAt(i,0)))n++;return n;}
    /** Seleccion por lotes SOLO en filas visibles; nunca crea enlaces sin PREVIEW+confirmacion.
     * Si varias propuestas compiten por el mismo LS/TC, NO elegimos arbitrariamente la primera. */
    private void selectA63(boolean onlySafe){
        if(!a63Table.isEnabled())return;
        int minutes=((Number)a63SelectMinutes.getValue()).intValue();
        List<Integer> eligible=new ArrayList<Integer>();
        Map<String,Integer> lsCounts=new HashMap<String,Integer>(),tcCounts=new HashMap<String,Integer>();
        for(int view=0;view<a63Table.getRowCount();view++){
            int i=a63Table.convertRowIndexToModel(view);
            String kind=String.valueOf(a63TableModel.getValueAt(i,1));
            if("SOLO_LS".equals(kind)||"SOLO_TC".equals(kind))continue;
            if(onlySafe&&!"CANDIDATO".equals(kind))continue;
            double delta;
            try{delta=Math.abs(Double.parseDouble(String.valueOf(a63TableModel.getValueAt(i,7))));}
            catch(NumberFormatException ex){continue;}
            if(delta>minutes)continue;
            String ls=String.valueOf(a63TableModel.getValueAt(i,2));
            String tc=String.valueOf(a63TableModel.getValueAt(i,3));
            eligible.add(i);
            lsCounts.put(ls,lsCounts.containsKey(ls)?lsCounts.get(ls)+1:1);
            tcCounts.put(tc,tcCounts.containsKey(tc)?tcCounts.get(tc)+1:1);
        }
        List<Integer> chosen=new ArrayList<Integer>();int conflicts=0,unsafe=0;
        for(Integer i:eligible){
            String ls=String.valueOf(a63TableModel.getValueAt(i,2));
            String tc=String.valueOf(a63TableModel.getValueAt(i,3));
            if(lsCounts.get(ls)!=1||tcCounts.get(tc)!=1){conflicts++;continue;}
            chosen.add(i);
            if(!"CANDIDATO".equals(a63TableModel.getValueAt(i,1)))unsafe++;
        }
        if(chosen.isEmpty()){
            JOptionPane.showMessageDialog(this,"No hay parejas 1:1 dentro de "+minutes+" minutos con el filtro visible."
                +"\nLos encuentros ambiguos deben marcarse individualmente."
                +"\nPara ampliar la busqueda, cambia el margen superior y reconsulta A6.3.",
                "A6.3",JOptionPane.INFORMATION_MESSAGE);return;
        }
        if(!onlySafe&&unsafe>0){
            int choice=JOptionPane.showConfirmDialog(this,"Vas a MARCAR "+chosen.size()+" parejas, incluidas "+unsafe+
                " que requieren revision de equipos, marcador o ambiguedad.\n"
                +"Las alternativas duplicadas se excluyen: "+conflicts+".\n"
                +"No se aplicara ningun enlace sin simular y confirmar A6.4.\n"
                +"¿Quieres marcarlas para revisarlas?","A6.3 · Seleccion masiva de revision",
                JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE);
            if(choice!=JOptionPane.YES_OPTION)return;
        }
        loadingA63=true;
        try{
            for(int i=0;i<a63TableModel.getRowCount();i++)a63TableModel.setValueAt(false,i,0);
            for(Integer i:chosen)a63TableModel.setValueAt(true,i,0);
        }finally{loadingA63=false;}
        a64PreviewSignature="";
        a63Summary.setText("Marcados="+chosen.size()+" | Requieren revision="+unsafe+
            " | Alternativas excluidas="+conflicts+" | Max="+minutes+" min. Simular A6.4 antes de aplicar.");
        refresh();
    }
    private void filterA63(){
        if(a63Table.getRowSorter() instanceof javax.swing.table.TableRowSorter){
            @SuppressWarnings("unchecked") javax.swing.table.TableRowSorter<javax.swing.table.TableModel> rs=
                (javax.swing.table.TableRowSorter<javax.swing.table.TableModel>)a63Table.getRowSorter();
            String kind=(String)a63Filter.getSelectedItem();
            rs.setRowFilter("TODOS".equals(kind)?null:javax.swing.RowFilter.regexFilter("^"+kind+"$",1));
        }
    }

    private String a62Signature(){
        try{
            if(!a61Checked())return "";
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Paths.get(a61Csv())));
            return key()+"|"+a61Scope()+"|"+a61Csv()+"|"+Base64.getEncoder().encodeToString(hash);
        }catch(Exception ex){return "";}
    }
    public void setExternalBusy(boolean b){otherBusy=b;refresh();}

    private static String key(String ls, String tc, String nm){return ls.trim()+"|"+tc.trim()+"|"+nm.trim();}
    private String key(){return key(lsId.getText(),tcId.getText(),name.getText());}
    private static String encode(String key){return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(key.getBytes(StandardCharsets.UTF_8));}
    private void recordConfirmation(String stage, String when) {
        String id="league."+encode(key());
        leagueHistory.setProperty(id+".ls",lsId.getText().trim());
        leagueHistory.setProperty(id+".tc",tcId.getText().trim());
        leagueHistory.setProperty(id+".name",name.getText().trim());
        leagueHistory.setProperty(id+"."+stage,when);
        rebuildSavedLeagues();saveState();
    }
    private void rebuildSavedLeagues() {
        String selected=(String)savedLeagues.getSelectedItem();
        savedLeagueKeys.clear();
        for(String prop:leagueHistory.stringPropertyNames()) {
            if(!prop.endsWith(".ls"))continue;
            String id=prop.substring(0,prop.length()-3);
            String ls=leagueHistory.getProperty(id+".ls", ""),tc=leagueHistory.getProperty(id+".tc", ""), nm=leagueHistory.getProperty(id+".name", "");
            if(!ls.isEmpty()&&!tc.isEmpty()&&!nm.isEmpty())savedLeagueKeys.put(nm+" | LS "+ls+" | TC "+tc,key(ls,tc,nm));
        }
        rebuildingLeagues=true;
        try {
            savedLeagues.removeAllItems();savedLeagues.addItem("-- Seleccionar liga guardada --");
            for(String title:new TreeSet<String>(savedLeagueKeys.keySet()))savedLeagues.addItem(title);
            if(selected!=null && savedLeagueKeys.containsKey(selected))savedLeagues.setSelectedItem(selected);
            else savedLeagues.setSelectedIndex(0);
        }finally{rebuildingLeagues=false;}
    }
    private void refresh(){
        boolean ready=!ownBusy&&!otherBusy;
        boolean verified=a1Verified(), done=a2Confirmed();
        simulateA1.setEnabled(ready); verifyA1.setEnabled(ready);saveLeague.setEnabled(ready);
        simulateA2.setEnabled(ready&&verified); executeA2.setEnabled(ready&&verified&&!done);
        boolean a3done=a3Confirmed();
        checkA3.setEnabled(ready&&done);simulateA3.setEnabled(ready&&done);
        executeA3.setEnabled(ready&&done&&!a3done && a3PreviewSignature.equals(a3Signature()));
        requestsA3.setEnabled(ready);hoursA3.setEnabled(ready);
        consultA4.setEnabled(ready&&a3done);
        consultA51.setEnabled(ready&&a4Confirmed());
        consultA52.setEnabled(ready&&a51Checked());
        a52From.setEnabled(ready);a52To.setEnabled(ready);a52Tolerance.setEnabled(ready);
        boolean a53Ready=ready && a52Checked() && key().equals(a53LoadedKey) && a52Csv().equals(a53LoadedCsv) && a52Scope().equals(a53LoadedScope);
        loadA53.setEnabled(ready && a52Checked());
        selectStrongA53.setEnabled(a53Ready);selectAliasA53.setEnabled(a53Ready);clearA53.setEnabled(a53Ready);
        a53Table.setEnabled(a53Ready);
        previewA53.setEnabled(a53Ready&&a53SelectedCount()>0);
        executeA53.setEnabled(a53Ready&&a53SelectedCount()>0 && a53PreviewSignature.equals(a53Signature()));
        previewA61.setEnabled(ready && a53Applied());
        boolean a62Ready=ready && a61Checked() && a61Count().matches("[0-9]+") &&
            Integer.parseInt(a61Count())>0 && ((Number)a61Tolerance.getValue()).intValue()<=15;
        previewA62.setEnabled(a62Ready);
        executeA62.setEnabled(a62Ready && !a62PreviewSignature.isEmpty() && a62PreviewSignature.equals(a62Signature()));
        a62State.setText(a62Applied()?"A6.2: ultimo lote VERIFIED: "+a62When()+" | "+a62Count()+" partidos":
            a61Checked()?"A6.2: simular antes de confirmar insercion VERIFIED.":"A6.2: pendiente CSV A6.1 para la liga.");
        a61From.setEnabled(ready);a61To.setEnabled(ready);a61Tolerance.setEnabled(ready);
        boolean a63Ready=ready&&a62Applied();
        reportA63.setEnabled(a63Ready);a63From.setEnabled(ready);a63To.setEnabled(ready);
        a63Tolerance.setEnabled(ready);a63Max.setEnabled(ready);
        a63Table.setEnabled(ready&&a63Checked()&&key().equals(a63ConsultKey)&&a63Scope().equals(a63ConsultScope));
        clearA63.setEnabled(a63Table.isEnabled()&&a63SelectedCount()>0);
        markSafeA63.setEnabled(a63Table.isEnabled());
        markVisibleA63.setEnabled(a63Table.isEnabled());
        a63SelectMinutes.setEnabled(ready);
        previewA64.setEnabled(a63Table.isEnabled()&&a63SelectedCount()>0);
        executeA64.setEnabled(previewA64.isEnabled()&&!a64PreviewSignature.isEmpty()&&a64PreviewSignature.equals(a63Signature()));
        a63State.setText(a63Checked()?"A6.3 CONSULTADO: "+a63When():
            a63Ready?"A6.3 PENDIENTE: buscar candidatos y revisar cada pareja.":"A6.3 requiere aplicar A6.2 primero.");
        a63Path.setText(a63Checked()?"CSV: "+a63Csv():"CSV: pendiente para este margen.");
        a64State.setText(!a64When().isEmpty()?"A6.4 ultimo lote aplicado: "+a64When()+" | "+a64Count()+" enlaces VERIFIED. Reconsultar A6.3 tras cada carga.":
            "A6.4: sin lotes manuales confirmados; seleccionar, simular y aplicar.");
        if(!a63ConsultKey.isEmpty()&&(!key().equals(a63ConsultKey)||!a63Scope().equals(a63ConsultScope))){
            a63ConsultKey="";a63ConsultScope="";a63TableModel.setRowCount(0);a63Fingerprints.clear();a64PreviewSignature="";
            a63Summary.setText("Filtros modificados; volver a consultar A6.3 antes de enlazar.");
        }

        a61State.setText(a61Checked()?"A6.1 CSV CONFIRMADO: "+a61When():
             a53Applied()?"A6.1 PENDIENTE: consultar partidos y revisar CSV antes de A6.2.":
             "A6.1 BLOQUEADO: aplicar antes los equipos revisados de A5.3.");
        a61Path.setText(a61Checked()?"CSV: "+a61Csv():"CSV: pendiente para los filtros actuales.");
        if(!a61ConsultKey.isEmpty()&&(!key().equals(a61ConsultKey)||!a61Scope().equals(a61ConsultScope))){
            a61ConsultKey="";a61ConsultScope="";a61TableModel.setRowCount(0);a61Summary.setText("Sin consulta A6.1 para esta liga.");
        }

        a53State.setText(a53Applied()?"A5.3: ultimo lote aplicado "+a53When()+" | "+a53Count()+" enlaces VERIFIED":
            a52Checked()?"A5.3: cargar CSV, seleccionar filas y simular antes de aplicar.":
            "A5.3: bloqueado hasta completar A5.2.");
        confirmA4.setEnabled(ready&&a3done&&!a4Confirmed()&&a4ConsultOk&&key().equals(a4ConsultKey));
        if(!a4ConsultKey.isEmpty() && !key().equals(a4ConsultKey)) {
            a4ConsultKey="";a4ConsultOk=false;a4Evidence="";
            a4Summary.setText("Sin consulta A4 para esta liga.");
            a4Dates.setText("Fechas UTC: pendientes de consulta.");
            a4TableModel.setRowCount(0);
        }
        a51State.setText(a51Checked()?"A5.1 COMPROBADO: "+a51When():
                 a4Confirmed()?"A5.1 PENDIENTE: consultar mappings VERIFIED de equipos.":
                 "A5.1 BLOQUEADO: confirmar A4 primero.");
        if(!a51ConsultKey.isEmpty() && !key().equals(a51ConsultKey)) {
            a51ConsultKey="";a51TableModel.setRowCount(0);
            a51Summary.setText("Pendiente: no se ha consultado el mapping de equipos de esta liga.");
        }
        if(!a53LoadedKey.isEmpty() && (!key().equals(a53LoadedKey) || !a52Csv().equals(a53LoadedCsv) || !a52Scope().equals(a53LoadedScope))) {
            a53LoadedKey="";a53LoadedCsv="";a53LoadedScope="";a53PreviewSignature="";
            if(a53TableModel.getRowCount()>0)a53TableModel.setRowCount(0);
            a53Summary.setText("Seleccion anterior invalidada: cargar el CSV correspondiente a la liga y fechas actuales.");
        }
        if(!a52ConsultKey.isEmpty() && !key().equals(a52ConsultKey)) {
            a52ConsultKey="";a52TableModel.setRowCount(0);
            a52Summary.setText("Pendiente de generar propuestas para esta liga.");
        }
        a52State.setText(a52Checked()?"A5.2 CSV GENERADO: "+a52When():
            a51Checked()?"A5.2 PENDIENTE: generar CSV con evidencias de partidos.":
            "A5.2 BLOQUEADO: comprobar primero A5.1.");
        a52Path.setText(a52Checked()?"CSV: "+a52Csv():"CSV: pendiente de generacion para esta liga.");
        a4State.setText(a4Confirmed()?"A4 CONFIRMADO: "+a4When():
                a3done?"A4 PENDIENTE: consultar MySQL y confirmar despues de revisar resultados.":
                "A4 BLOQUEADO: completar A3 primero.");
        a3Next.setText(a3PreviewSignature.startsWith(key()+"|") && !a3PreviewPage.isEmpty()
                     ? "Ultima consulta: pagina pendiente "+a3PreviewPage+" (repetir tras ejecutar lote)."
                     : "Pagina pendiente: ejecutar simulacion A3 para consultar MySQL.");
        a3State.setText(a3done?"A3 CONFIRMADO (fin de paginacion): "+a3When():
                done?"A3 PENDIENTE: comprobar siguiente pagina y descargar por lotes hasta el final.":
                "A3 BLOQUEADO: completar A2 primero.");
        // Motivos de bloqueo disponibles desde las pestañas del Bloque 01.
        String activity=ownBusy?"Proceso del Bloque 01 en curso.":"Otro proceso de Manager esta en curso.";
        ManagerUiSupport.gate(simulateA1,ready,activity);
        ManagerUiSupport.gate(verifyA1,ready,activity);
        ManagerUiSupport.gate(saveLeague,ready,activity);
        ManagerUiSupport.gate(simulateA2,ready&&verified,!ready?activity:"Falta verificar A1 con la API.");
        ManagerUiSupport.gate(executeA2,ready&&verified&&!done,!ready?activity:
                !verified?"Falta verificar A1 con la API.":"A2 ya esta confirmado para esta liga.");
        ManagerUiSupport.gate(checkA3,ready&&done,!ready?activity:"Falta descargar la pagina 1 en A2.");
        ManagerUiSupport.gate(simulateA3,ready&&done,!ready?activity:"Falta confirmar A2.");
        ManagerUiSupport.gate(executeA3,ready&&done&&!a3done&&a3PreviewSignature.equals(a3Signature()),
                !ready?activity:!done?"Falta A2.":a3done?"Fin de paginacion A3 ya confirmado.":
                "Falta consultar la pagina pendiente A3 con los parametros actuales.");
        ManagerUiSupport.gate(consultA4,ready&&a3done,!ready?activity:"Falta completar A3.");
        ManagerUiSupport.gate(confirmA4,confirmA4.isEnabled(),!ready?activity:
                !a3done?"Falta A3.":a4Confirmed()?"A4 ya confirmado.":
                "Falta consultar A4 y revisar el resultado para esta liga.");
        ManagerUiSupport.gate(consultA51,ready&&a4Confirmed(),!ready?activity:"Falta confirmar A4.");
        ManagerUiSupport.gate(consultA52,ready&&a51Checked(),!ready?activity:"Falta consultar mappings A5.1.");
        ManagerUiSupport.gate(loadA53,ready&&a52Checked(),!ready?activity:"Falta generar el CSV A5.2.");
        ManagerUiSupport.gate(previewA53,a53Ready&&a53SelectedCount()>0,!ready?activity:
                !a53Ready?"Falta cargar un CSV A5.2 valido para la liga y filtros actuales.":
                "Selecciona al menos una correspondencia de equipo.");
        ManagerUiSupport.gate(executeA53,a53Ready&&a53SelectedCount()>0&&a53PreviewSignature.equals(a53Signature()),
                !ready?activity:!a53Ready?"Falta cargar CSV A5.2 para esta liga y filtros.":
                a53SelectedCount()==0?"Selecciona los equipos que se van a enlazar.":
                "Falta simular A5.3 tras la ultima seleccion de equipos.");
        ManagerUiSupport.gate(previewA61,ready&&a53Applied(),!ready?activity:"Falta aplicar enlaces VERIFIED de equipos en A5.3.");
        ManagerUiSupport.gate(previewA62,previewA62.isEnabled(),!ready?activity:
            !a61Checked()?"Falta CSV A6.1 para esta liga.":"Solo automaticos con tolerancia de hasta 15 min y al menos un seguro.");
        ManagerUiSupport.gate(executeA62,executeA62.isEnabled(),!ready?activity:
            !a61Checked()?"Falta CSV A6.1 actualizado.":"Simular A6.2 con el CSV actual para habilitar la carga.");
        ManagerUiSupport.gate(reportA63,reportA63.isEnabled(),!ready?activity:"Completar A6.2 antes de revisar pendientes.");
        ManagerUiSupport.gate(previewA64,previewA64.isEnabled(),!ready?activity:!a63Checked()?"Consultar A6.3 con los parametros actuales.":"Marca al menos una pareja para revisar.");
        ManagerUiSupport.gate(executeA64,executeA64.isEnabled(),!ready?activity:!a63Checked()?"Consultar A6.3.":"Falta simulacion valida con la seleccion actual.");
        ManagerUiSupport.gate(stop,ownBusy,"No hay un proceso del Bloque 01 en curso.");
        lsId.setEditable(ready);tcId.setEditable(ready);name.setEditable(ready);
        a1State.setText(verified?"A1 VERIFICADO por API: "+a1When():"A1 PENDIENTE: TC="+tcId.getText().trim()+" sin confirmar mediante API.");
        a2State.setText(done?"A2 CONFIRMADO (pagina 1 DONE): "+a2When():(verified?"A2 PENDIENTE: primero simular y luego confirmar pagina 1.":"A2 BLOQUEADO: verificar A1 antes."));
        if(onStateChanged!=null)onStateChanged.run();
    }
    private void loadState(){
        try{if(!Files.isRegularFile(saved))return;Properties p=new Properties();
            try(InputStream in=Files.newInputStream(saved)){p.load(in);}
            verifiedKey=p.getProperty("a1.verified.key","");verifiedAt=p.getProperty("a1.verified.at","");
            pageDoneKey=p.getProperty("a2.done.key","");pageDoneAt=p.getProperty("a2.done.at","");
            for(String prop:p.stringPropertyNames())if(prop.startsWith("league."))leagueHistory.setProperty(prop,p.getProperty(prop));
            // Compatibilidad con v1.1.0: conservar confirmaciones de la ultima liga.
            if(!verifiedKey.isEmpty()) {
                String old="league."+encode(verifiedKey);
                String[] fieldsOld=verifiedKey.split("\\|",3);
                if(fieldsOld.length==3) {
                    leagueHistory.setProperty(old+".ls",fieldsOld[0]);leagueHistory.setProperty(old+".tc",fieldsOld[1]);leagueHistory.setProperty(old+".name",fieldsOld[2]);
                    if(!verifiedAt.isEmpty())leagueHistory.setProperty(old+".a1",verifiedAt);
                    if(verifiedKey.equals(pageDoneKey)&&!pageDoneAt.isEmpty())leagueHistory.setProperty(old+".a2",pageDoneAt);
                }
            }
            lsId.setText(p.getProperty("ls.id","7"));tcId.setText(p.getProperty("tc.id","85"));name.setText(p.getProperty("expected.name","USA MLS"));
            restorePersistedScopes();
            rebuildSavedLeagues();
        }catch(Exception ex){note("AVISO: no se pudo leer estado anterior: "+ex.getClass().getSimpleName());}
    }
    /** Recupera exactamente los filtros con los que se genero el ultimo CSV por liga. */
    private void restorePersistedScopes(){
        String prefix="league."+encode(key());
        restoreScope(leagueHistory.getProperty(prefix+".a52.scope",""),a52From,a52To,a52Tolerance);
        restoreScope(leagueHistory.getProperty(prefix+".a61.scope",""),a61From,a61To,a61Tolerance);
        restoreA63Scope(leagueHistory.getProperty(prefix+".a63.scope",""));
    }
    private static void restoreScope(String scope,JTextField from,JTextField to,JSpinner minutes){
        from.setText("2014-01-01");to.setText("2027-01-01");minutes.setValue(5);
        if(scope==null||scope.isEmpty())return;
        String[] parts=scope.split("\\|",-1);
        if(parts.length!=3)return;
        try{
            java.time.LocalDate f=java.time.LocalDate.parse(parts[0]),t=java.time.LocalDate.parse(parts[1]);
            int m=Integer.parseInt(parts[2]);
            if(t.isAfter(f)&&m>=0&&m<=1440){from.setText(parts[0]);to.setText(parts[1]);minutes.setValue(m);}
        }catch(Exception ignored){}
    }

    private void restoreA63Scope(String scope){
        a63From.setText(a61From.getText().trim());a63To.setText(a61To.getText().trim());a63Tolerance.setValue(120);a63Max.setValue(10);
        if(scope==null||scope.isEmpty())return;
        String[] p=scope.split("\\|",-1);if(p.length!=4)return;
        try{java.time.LocalDate f=java.time.LocalDate.parse(p[0]),t=java.time.LocalDate.parse(p[1]);
            int m=Integer.parseInt(p[2]),limit=Integer.parseInt(p[3]);
            if(t.isAfter(f)&&m>=1&&m<=14400&&limit>=1&&limit<=30){
                a63From.setText(p[0]);a63To.setText(p[1]);a63Tolerance.setValue(m);a63Max.setValue(limit);}}
        catch(Exception ignored){}
    }
    private void saveState(){
        try {Properties p=new Properties();p.setProperty("a1.verified.key",verifiedKey);p.setProperty("a1.verified.at",verifiedAt);
            p.setProperty("a2.done.key",pageDoneKey);p.setProperty("a2.done.at",pageDoneAt);
            p.setProperty("ls.id",lsId.getText().trim());p.setProperty("tc.id",tcId.getText().trim());p.setProperty("expected.name",name.getText().trim());
            for(String prop:leagueHistory.stringPropertyNames())p.setProperty(prop,leagueHistory.getProperty(prop));
            try(OutputStream out=Files.newOutputStream(saved)){p.store(out,"A1/A2/A3/A4/A5.1 by league; NO API tokens, passwords or personal credentials");}
        }catch(Exception ex){note("AVISO: no se pudo guardar estado: "+ex.getMessage());}
    }
    private void note(String s){log.append(s+"\n");log.setCaretPosition(log.getDocument().getLength());}
    private void run(String mode){
        if(ownBusy || managerBusy.get())return;
        final boolean actual = mode.equals("a1-verify") || mode.equals("a2-execute");
        final boolean a1=mode.startsWith("a1");
        final Path dir;final List<String> cmd;final String signature=key();
        try{
            String id=tcId.getText().trim(),ls=lsId.getText().trim(), expected=name.getText().trim();
            if(!id.matches("[0-9]{1,15}")||!ls.matches("[0-9]{1,15}")||expected.isEmpty())
                throw new IOException("Introduce los IDs numericos y el nombre esperado.");
            if(!a1&&!a1Verified())throw new IOException("A2 requiere A1 confirmado por API con estos IDs y nombre.");
            dir=Paths.get(projectPath.get().trim()).toAbsolutePath().normalize();
            Path java=Paths.get(java8Path.get().trim()).toAbsolutePath().normalize();
            if(!Files.isDirectory(dir)||!Files.isRegularFile(dir.resolve("pom.xml")))throw new IOException("Comprueba la ruta de totalcorner-downloader.");
            TotalCornerManager.verifyJava8(java);
            String cp=TotalCornerManager.buildClasspath(dir);
            String needed=a1?"TotalCornerLeagueIdVerifier.class":"LeagueScheduleBackfill.class";
            if(!Files.isRegularFile(dir.resolve("target/classes/com/footiqo/totalcorner/"+needed)))
                throw new IOException("No se encontro "+needed+". Compila totalcorner-downloader en Eclipse.");
            if(actual && System.getenv("TOTALCORNER_API_TOKEN")==null)
                throw new IOException("Falta TOTALCORNER_API_TOKEN en el entorno de Manager.");
            if(!a1 && (System.getenv("TOTALCORNER_DB_PASSWORD")==null))
                throw new IOException("A2 requiere TOTALCORNER_DB_PASSWORD en el entorno de Manager.");
            cmd=new ArrayList<String>();
            Collections.addAll(cmd,java.toString(),"-Dfile.encoding=UTF-8","-cp",cp,a1?VERIFY:BACKFILL);
            if(a1){cmd.add("--league-id="+id);cmd.add("--expected-name="+expected);}
            else{cmd.add("--league="+id);cmd.add("--from-page=1");cmd.add("--pages=1");}
            if(actual)cmd.add("--execute");
            if(actual) {
                String message=a1?"A1 consultara 1 pagina de la API para verificar la liga. No modificara MySQL."
                        :"A2 descargara la pagina 1 y podra escribir en tablas TC de MySQL. No modifica tablas ls_.";
                if(JOptionPane.showConfirmDialog(this,message+"\nContinuar?", "Confirmar "+(a1?"A1":"A2"),JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.YES_OPTION)return;
            }
        }catch(Exception ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"Bloque 01",JOptionPane.ERROR_MESSAGE);return;}
        ownBusy=true;cancelled=false;setManagerBusy.accept(true);refresh();
        note("=== "+mode.toUpperCase(Locale.ROOT)+" | "+signature+" ===");
        final Path logs=dir.resolve("target/totalcorner-manager-logs");
        new SwingWorker<String,String>() {
            @Override protected String doInBackground(){
                boolean verified=false, done=false, simulated=false, sawProblem=false; int code=-1;
                Path file=logs.resolve("A-"+mode+"-"+TS.format(LocalDateTime.now())+".log");
                try{
                    Files.createDirectories(logs);publish("Log: "+file);
                    ProcessBuilder pb=new ProcessBuilder(cmd);pb.directory(dir.toFile());pb.redirectErrorStream(true);
                    process=pb.start();
                    try(BufferedReader r=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
                        BufferedWriter w=Files.newBufferedWriter(file,StandardCharsets.UTF_8)) {
                        String line;
                        while((line=r.readLine())!=null){publish(line);w.write(line);w.newLine();
                            if(a1){
                                if(line.contains("estado=OK")&&line.contains("HTTP=200")&&line.contains("ID_API="+tcId.getText().trim()+" |"))verified=true;
                                if(line.contains("VERIFICACION COMPLETA:"))done=true;
                                if(line.contains("SIMULACION COMPLETA:"))simulated=true;
                            }else{
                                if(line.contains("SIMULACION terminada. Peticiones=0, escrituras=0."))simulated=true;
                                if(line.contains("OK: pagina 1 DONE (COMMIT)"))done=true;
                                if(line.contains("pagina 1: OMITIDA (DONE"))verified=true;
                            }
                            if(line.contains("ERROR")||line.contains("REVISAR")||line.contains("FALLO")||line.contains("HTTP 429"))sawProblem=true;
                        }
                    }
                    code=process.waitFor();
                }catch(Exception ex){return "ERROR: "+ex.getClass().getSimpleName()+": "+ex.getMessage();}
                if(cancelled)return "DETENIDO: no se marca completado.";
                if(code!=0 || sawProblem)return "ERROR: fallo o incidencias; revisar log. Codigo="+code;
                if(!actual)return simulated?"SIMULACION_OK":"ERROR: simulacion no confirmada por el descargador.";
                if(a1)return verified&&done?"A1_VERIFICADO":"ERROR: API no confirmo HTTP=200, ID, nombre y estado OK.";
                return done||verified?"A2_CONFIRMADO":"ERROR: pagina 1 no ha quedado DONE confirmado.";
            }
            @Override protected void process(List<String> lines){for(String line:lines)note(line);}
            @Override protected void done(){
                String result;try{result=get();}catch(Exception ex){result="ERROR: "+ex.getMessage();}
                note("== "+result+" ==");
                if(signature.equals(key())){
                    if("A1_VERIFICADO".equals(result)) {
                        verifiedKey=signature;verifiedAt=LocalDateTime.now().toString();
                        recordConfirmation("a1",verifiedAt);
                    }
                    if("A2_CONFIRMADO".equals(result)) {
                        pageDoneKey=signature;pageDoneAt=LocalDateTime.now().toString();
                        recordConfirmation("a2",pageDoneAt);
                    }
                }
                process=null;ownBusy=false;setManagerBusy.accept(false);refresh();
            }
        }.execute();
    }
    /**
     * A3: la unica fuente de verdad es el trabajo LEAGUE_SCHEDULE_PAGE y el fin de paginacion
     * verificado por HistoricalDownloadRunner. Un lote ejecutado no implica calendario completo.
     */
    private String a3Signature(){return key()+"|"+requestsA3.getValue()+"|"+hoursA3.getValue();}
    private Path a3Config(Path dir, String id, String expected) throws IOException {
        if(!id.matches("[0-9]{1,15}") || expected.isEmpty() || expected.indexOf('\n')>=0 || expected.indexOf('\r')>=0)
            throw new IOException("Liga invalida para la configuracion A3.");
        Path config = dir.resolve("config/totalcorner-manager-league-"+id+".properties");
        Files.createDirectories(config.getParent());
        if (Files.isRegularFile(config)) {
            Properties p = new Properties();
            try(InputStream in=Files.newInputStream(config)){p.load(in);}
            if(!id.equals(p.getProperty("league.ids", "").trim()) ||
               !expected.equalsIgnoreCase(p.getProperty("league."+id+".name", "").trim()))
                throw new IOException("El fichero "+config.getFileName()+" ya existe con IDs/nombre diferentes. Revisalo manualmente sin sobrescribirlo.");
            return config;
        }
        Properties p=new Properties();
        p.setProperty("league.ids",id);
        p.setProperty("league."+id+".name",expected);
        p.setProperty("league."+id+".priority","1");
        try(OutputStream out=Files.newOutputStream(config,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            p.store(out,"A3 calendario completo - configuracion exclusiva liga TC="+id);
        }
        return config;
    }

    private void runA3(String mode) {
        if(ownBusy || managerBusy.get())return;
        final boolean actual="execute".equals(mode);
        final String signature=key(), tc=tcId.getText().trim(), nm=name.getText().trim();
        final String previewSignature=a3Signature();
        final Path dir, config;
        final List<String> cmd=new ArrayList<String>();
        try{
            if(!a2Confirmed())throw new IOException("A3 requiere A1 y A2 confirmados para esta liga.");
            if(actual&&a3Confirmed())throw new IOException("A3 ya tiene fin de paginacion confirmado.");
            if(actual&&!previewSignature.equals(a3PreviewSignature))
                throw new IOException("Antes de descargar, consulta de nuevo la pagina pendiente A3 con los mismos limites.");
            dir=Paths.get(projectPath.get().trim()).toAbsolutePath().normalize();
            Path java=Paths.get(java8Path.get().trim()).toAbsolutePath().normalize();
            if(!Files.isDirectory(dir)||!Files.isRegularFile(dir.resolve("pom.xml")))
                throw new IOException("Comprueba el proyecto totalcorner-downloader.");
            TotalCornerManager.verifyJava8(java);
            if(!Files.isRegularFile(dir.resolve("target/classes/com/footiqo/totalcorner/HistoricalDownloadRunner.class")))
                throw new IOException("Falta HistoricalDownloadRunner.class. Compila totalcorner-downloader.");
            if(!"check".equals(mode)&&System.getenv("TOTALCORNER_DB_PASSWORD")==null)
                throw new IOException("Falta TOTALCORNER_DB_PASSWORD en el entorno de Manager.");
            if(actual && System.getenv("TOTALCORNER_API_TOKEN")==null)
                throw new IOException("Falta TOTALCORNER_API_TOKEN en el entorno de Manager.");
            config=a3Config(dir,tc,nm);
            Collections.addAll(cmd,java.toString(),"-Dfile.encoding=UTF-8","-cp",
                    TotalCornerManager.buildClasspath(dir),DISCOVERY,
                    "--config="+config.toString(),"--phase=discovery",
                    "--max-hours="+hoursA3.getValue(),"--max-requests="+requestsA3.getValue());
            if("check".equals(mode))cmd.add("--check-config");
            else if(actual)cmd.add("--execute");
            if(actual&&JOptionPane.showConfirmDialog(this,
                    "A3 descargara hasta "+requestsA3.getValue()+" paginas / solicitudes API para TC="+tc+
                    ".\nSe conservaran las paginas DONE y las respuestas originales.\nNO modifica tablas ls_. Continuar?",
                    "Confirmar descarga por lote A3",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)
                    !=JOptionPane.YES_OPTION)return;
        }catch(Exception ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"Bloque 01 A3",JOptionPane.ERROR_MESSAGE);return;}

        ownBusy=true;cancelled=false;setManagerBusy.accept(true);refresh();
        note("=== A3 "+mode.toUpperCase(Locale.ROOT)+" | "+signature+" ===");
        note("Configuracion conservada: "+config);
        final Path logs=dir.resolve("target/totalcorner-manager-logs");
        new SwingWorker<String,String>(){
            @Override protected String doInBackground(){
                boolean configOk=false, previewOk=false, previewFinal=false, executedFinal=false;
                boolean sawError=false, endLog=false, partial=false;
                String pendingPage="";
                int code=-1;
                Path file=logs.resolve("A-a3-"+mode+"-"+TS.format(LocalDateTime.now())+".log");
                try{
                    Files.createDirectories(logs);publish("Log: "+file);
                    ProcessBuilder pb=new ProcessBuilder(cmd);pb.directory(dir.toFile());pb.redirectErrorStream(true);
                    process=pb.start();
                    try(BufferedReader r=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
                        BufferedWriter w=Files.newBufferedWriter(file,StandardCharsets.UTF_8)){
                        String line;
                        while((line=r.readLine())!=null){
                            publish(line);w.write(line);w.newLine();
                            if(line.contains("CONFIGURACION VALIDA | SQL=0 | API=0"))configOk=true;
                            if(line.contains("SIMULACION COMPLETADA | HTTP=0 | SQL=SOLO LECTURA"))previewOk=true;
                            if(line.startsWith("DESCUBRIMIENTO ") && line.contains(" | FINALIZADO en pagina "))previewFinal=true;
                            if(line.startsWith("DESCUBRIMIENTO ") && line.contains(" | siguiente pagina=")){
                                java.util.regex.Matcher match=java.util.regex.Pattern.compile("siguiente pagina=([0-9]+)").matcher(line);
                                if(match.find())pendingPage=match.group(1);
                                publish("SIGUIENTE ACCION: continuar desde la pagina pendiente indicada (no ajustar manualmente).");
                            }
                            if(line.startsWith("Liga "+tc+" | ultimo indice=") && line.contains("fin paginacion verificado=true"))executedFinal=true;
                            if(line.startsWith("FIN HISTORICAL |"))endLog=true;
                            if(line.contains("Descubrimiento incompleto.") || line.contains("limite/pausa/fin="))partial=true;
                            if(line.contains("ERROR CONFIGURACION") || line.contains("ERROR SQL") ||
                               line.contains("ERROR HISTORICAL") || line.contains("BLOQUEO:") ||
                               line.contains("no termino DONE") || line.contains("HTTP 429") ||
                               line.startsWith("Faltan TOTALCORNER_") || line.startsWith("Falta TOTALCORNER_")) sawError=true;
                        }
                    }
                    code=process.waitFor();
                }catch(Exception ex){return "ERROR A3: "+ex.getClass().getSimpleName()+": "+ex.getMessage();}
                if(cancelled)return "A3 DETENIDO: solo persisten las paginas DONE; no se marca completo.";
                if(code!=0||sawError)return "ERROR A3: revisar log antes de reanudar (codigo="+code+").";
                if("check".equals(mode))return configOk?"A3_CONFIG_OK":"ERROR A3: config no verificada.";
                if(!actual){
                    if(!previewOk)return "ERROR A3: simulacion no confirmada.";
                    return previewFinal?"A3_FINAL_VERIFICADO_DB":"A3_SIMULACION_OK"+(
                            pendingPage.isEmpty()?"":" pagina="+pendingPage);
                }
                if(executedFinal && endLog)return "A3_FINAL_VERIFICADO_DB";
                if(endLog || partial)return "A3_LOTE_OK_PENDIENTE";
                return "ERROR A3: ejecucion sin resumen confirmado; revisar log.";
            }
            @Override protected void process(List<String> lines){for(String line:lines)note(line);}
            @Override protected void done(){
                String result;try{result=get();}catch(Exception ex){result="ERROR A3: "+ex.getMessage();}
                note("== "+result+" ==");
                if(signature.equals(key())&&"A3_FINAL_VERIFICADO_DB".equals(result))
                    recordConfirmation("a3",LocalDateTime.now().toString());
                if(signature.equals(key()) && result.startsWith("A3_SIMULACION_OK")){
                    a3PreviewSignature=previewSignature;
                    a3PreviewPage=result.contains(" pagina=")?result.substring(result.indexOf(" pagina=")+8):"?";
                }
                if(actual){a3PreviewSignature="";a3PreviewPage="";}
                if("A3_LOTE_OK_PENDIENTE".equals(result))
                    note("Siguiente: consultar pagina pendiente (A3), y repetir lotes hasta FINALIZADO.");
                process=null;ownBusy=false;setManagerBusy.accept(false);refresh();
            }
        }.execute();
    }

    /** A4: solo consultas SQL; la confirmacion exige revision manual de la evidencia del mismo ID de liga. */
    private void runA4() {
        if(ownBusy || managerBusy.get())return;
        final String signature=key();
        final Path dir;
        final List<String> cmd=new ArrayList<String>();
        try {
            if(!a3Confirmed())throw new IOException("A4 requiere A3 confirmado para esta liga.");
            String ls=lsId.getText().trim(),tc=tcId.getText().trim();
            if(!ls.matches("[0-9]{1,15}")||!tc.matches("[0-9]{1,15}"))
                throw new IOException("A4 requiere IDs LS/TC numericos.");
            dir=Paths.get(projectPath.get().trim()).toAbsolutePath().normalize();
            Path java=Paths.get(java8Path.get().trim()).toAbsolutePath().normalize();
            if(!Files.isRegularFile(dir.resolve("pom.xml")))throw new IOException("Revisa el directorio totalcorner-downloader.");
            TotalCornerManager.verifyJava8(java);
            String password=System.getenv("TOTALCORNER_DB_PASSWORD");
            if(password==null || password.trim().isEmpty())throw new IOException("Falta TOTALCORNER_DB_PASSWORD en Manager.");
            Collections.addAll(cmd,java.toString(),"-Dfile.encoding=UTF-8","-cp",
                    TotalCornerManager.managerCodeLocation()+File.pathSeparator+TotalCornerManager.buildClasspath(dir),
                    AUDIT,"--ls-league-id="+ls,"--tc-league-id="+tc);
        }catch(Exception ex){
            JOptionPane.showMessageDialog(this,ex.getMessage(),"Bloque 01 A4",JOptionPane.ERROR_MESSAGE);return;
        }
        a4ConsultKey="";a4ConsultOk=false;a4Evidence="";
        a4TableModel.setRowCount(0);
        a4Summary.setText("Consultando partidos y temporadas...");
        a4Dates.setText("Fechas UTC: pendientes de consulta.");
        ownBusy=true;cancelled=false;setManagerBusy.accept(true);refresh();
        note("=== A4 CONSULTA | "+signature+" | API=0, SQL=SOLO LECTURA ===");
        final Path logs=dir.resolve("target/totalcorner-manager-logs");
        new SwingWorker<A4Outcome,String>() {
            @Override protected A4Outcome doInBackground() {
                A4Outcome out=new A4Outcome();
                Path file=logs.resolve("A-a4-consult-"+TS.format(LocalDateTime.now())+".log");
                try {
                    Files.createDirectories(logs);publish("Log: "+file);
                    ProcessBuilder pb=new ProcessBuilder(cmd);pb.directory(dir.toFile());pb.redirectErrorStream(true);
                    process=pb.start();
                    try(BufferedReader r=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
                        BufferedWriter w=Files.newBufferedWriter(file,StandardCharsets.UTF_8)) {
                        String line;
                        while((line=r.readLine())!=null) {
                            publish(line);w.write(line);w.newLine();
                            if(line.startsWith("A4_TC\t")) {
                                String[] f=line.split("\t",-1);
                                if(f.length==6)out.tcLine=f;
                            }
                            if(line.startsWith("A4_TEMPORADA\t")){
                                String[] f=line.split("\t",-1);
                                if(f.length==5)out.rows.add(f);
                            }
                            if(line.startsWith("A4_RESULT\t")){
                                String[] f=line.split("\t",-1);
                                if(f.length==8)out.resultLine=f;
                            }
                            if(line.startsWith("A4_ERROR:"))out.problem=true;
                            if(line.startsWith("A4 FIN |"))out.finished=true;
                        }
                    }
                    out.exitCode=process.waitFor();
                }catch(Exception ex){out.error=ex.getClass().getSimpleName()+": "+ex.getMessage();}
                return out;
            }
            @Override protected void process(List<String> lines){for(String line:lines)note(line);}
            @Override protected void done(){
                A4Outcome out;
                try{out=get();}catch(Exception ex){out=new A4Outcome();out.error=ex.getMessage();}
                if(!cancelled && out.error==null && !out.problem && out.exitCode==0 && out.finished &&
                   out.tcLine!=null && out.resultLine!=null && signature.equals(key())){
                    for(String[] season:out.rows)a4TableModel.addRow(new Object[]{season[1],season[2],season[3],season[4]});
                    a4ConsultKey=signature;
                    a4ConsultOk=("OK".equals(out.resultLine[1]) || "ADVERTENCIAS".equals(out.resultLine[1])) &&
                         Integer.toString(out.rows.size()).equals(out.resultLine[5]);
                    a4Evidence="TC partidos="+out.resultLine[2]+", sin equipo="+out.resultLine[3]+
                            ", sin fecha="+out.resultLine[4]+", temporadas LS="+out.resultLine[5]+
                            ", partidos LS="+out.resultLine[6];
                    boolean warning="ADVERTENCIAS".equals(out.resultLine[1]);
                    a4Summary.setText((a4ConsultOk?(warning?"ADVERTENCIAS TC (no bloqueantes): ":"CONTROL OK: "):"BLOQUEADO: ")+a4Evidence);
                    a4Dates.setText("Primer partido TC (UTC): "+out.tcLine[4]+
                            " | Ultimo partido TC (UTC): "+out.tcLine[5]);
                    note(a4ConsultOk ? "ADVERTENCIAS".equals(out.resultLine[1])
                          ? "== A4: registros TC incompletos; NO BLOQUEAN A5. Revisar las ausencias LS al enlazar en A6. =="
                          : "== A4 CONSULTA OK: revisar la tabla y confirmar manualmente =="
                          : "== A4 BLOQUEADO: falta calendario TC o temporadas/partidos en Footiqo ==");
                }else{
                    a4ConsultOk=false;
                    String msg=cancelled?"Proceso interrumpido":out.error!=null?out.error:
                            "El runner A4 no devolvio un resultado integro (codigo "+out.exitCode+").";
                    a4Summary.setText("A4 SIN CONFIRMAR: "+msg);
                    note("== ERROR A4: "+msg+" ==");
                }
                process=null;ownBusy=false;setManagerBusy.accept(false);refresh();
            }
        }.execute();
    }

    private static final class A4Outcome {
        String[] tcLine,resultLine;
        final List<String[]> rows=new ArrayList<String[]>();
        boolean finished,problem;
        int exitCode=-1;
        String error;
    }
    private void confirmA4() {
        if(!a3Confirmed() || !a4ConsultOk || !key().equals(a4ConsultKey) || ownBusy || managerBusy.get())return;
        String confirmation="A4 comprobo:\n"+a4Evidence+"\n\n"
                +"Has revisado las temporadas LS y los limites de fechas de los partidos TC en el registro?\n"
                +"Incidencias de registros TC incompletos no bloquean A5; las diferencias en los partidos LS se revisaran en A6.\n"
                +"A4 no crea ni enlaza partidos o temporadas.";
        if(JOptionPane.showConfirmDialog(this,confirmation,"Confirmar revision A4",JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE)==JOptionPane.YES_OPTION){
            recordConfirmation("a4",LocalDateTime.now().toString());
            note("== A4 CONFIRMADO (solo lectura). Siguiente: A5.1 comprobar equipos existentes. ==");
            refresh();
        }
    }

    /** A5.1: estado de correspondencias existentes; ninguna escritura, no marca A5 completo. */
    private void runA51() {
        if(ownBusy || managerBusy.get())return;
        final String signature=key();
        final Path dir;
        final List<String> cmd=new ArrayList<String>();
        try {
            if(!a4Confirmed())throw new IOException("A5.1 requiere A4 confirmado para esta liga.");
            String ls=lsId.getText().trim(),tc=tcId.getText().trim();
            if(!ls.matches("[0-9]{1,15}") || !tc.matches("[0-9]{1,15}"))
                throw new IOException("A5.1 requiere IDs LS/TC numericos.");
            dir=Paths.get(projectPath.get().trim()).toAbsolutePath().normalize();
            Path java=Paths.get(java8Path.get().trim()).toAbsolutePath().normalize();
            if(!Files.isRegularFile(dir.resolve("pom.xml")))throw new IOException("Revisa el directorio totalcorner-downloader.");
            TotalCornerManager.verifyJava8(java);
            String pass=System.getenv("TOTALCORNER_DB_PASSWORD");
            if(pass==null || pass.trim().isEmpty())throw new IOException("Falta TOTALCORNER_DB_PASSWORD en Manager.");
            Collections.addAll(cmd,java.toString(),"-Dfile.encoding=UTF-8","-cp",
                TotalCornerManager.managerCodeLocation()+File.pathSeparator+TotalCornerManager.buildClasspath(dir),
                TEAMS_A51,"--ls-league-id="+ls,"--tc-league-id="+tc);
        }catch(Exception ex){
            JOptionPane.showMessageDialog(this,ex.getMessage(),"Bloque 01 A5.1",JOptionPane.ERROR_MESSAGE);return;
        }
        a51ConsultKey="";a51TableModel.setRowCount(0);a51Summary.setText("Consultando mappings verificados...");
        ownBusy=true;cancelled=false;setManagerBusy.accept(true);refresh();
        note("=== A5.1 CONSULTA | "+signature+" | API=0, SQL=SOLO LECTURA ===");
        final Path logs=dir.resolve("target/totalcorner-manager-logs");
        new SwingWorker<A51Outcome,String>() {
            @Override protected A51Outcome doInBackground() {
                A51Outcome out=new A51Outcome();
                Path file=logs.resolve("A-a51-consult-"+TS.format(LocalDateTime.now())+".log");
                try {
                    Files.createDirectories(logs);publish("Log: "+file);
                    ProcessBuilder pb=new ProcessBuilder(cmd);pb.directory(dir.toFile());pb.redirectErrorStream(true);
                    process=pb.start();
                    try(BufferedReader r=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
                        BufferedWriter w=Files.newBufferedWriter(file,StandardCharsets.UTF_8)) {
                        String line;
                        while((line=r.readLine())!=null) {
                            publish(line);w.write(line);w.newLine();
                            if(line.startsWith("A51_TEAM\t")) {
                                String[] f=line.split("\t",-1);if(f.length==6)out.rows.add(f);
                            }
                            if(line.startsWith("A51_RESULT\t")){
                                String[] f=line.split("\t",-1);if(f.length==4)out.result=f;
                            }
                            if(line.startsWith("A51_ERROR:"))out.problem=true;
                            if(line.startsWith("A51 FIN |"))out.finished=true;
                        }
                    }
                    out.exitCode=process.waitFor();
                }catch(Exception ex){out.error=ex.getClass().getSimpleName()+": "+ex.getMessage();}
                return out;
            }
            @Override protected void process(List<String> lines){for(String line:lines)note(line);}
            @Override protected void done() {
                A51Outcome out;
                try{out=get();}catch(Exception ex){out=new A51Outcome();out.error=ex.getMessage();}
                if(!cancelled && out.error==null && !out.problem && out.finished && out.exitCode==0
                   && out.result!=null && signature.equals(key()) &&
                   Integer.toString(out.rows.size()).equals(out.result[1]) && !out.rows.isEmpty()) {
                    a51TableModel.setRowCount(0);
                    for(String[] t:out.rows)a51TableModel.addRow(new Object[]{t[1],t[2],t[3],t[4],t[5]});
                    a51ConsultKey=signature;
                    a51Summary.setText("TC equipos="+out.result[1]+" | VERIFIED="+out.result[2]
                            +" | sin enlace verificado en la liga="+out.result[3]);
                    recordConfirmation("a51",LocalDateTime.now().toString());
                    note("== A5.1 COMPROBADO: siguientes A5.2 propuestas por partidos y A5.3 aplicar tras revisar ==");
                } else {
                    String message=cancelled?"Proceso interrumpido":out.error!=null?out.error:
                            "Resultado incompleto o sin equipos TC (codigo "+out.exitCode+")";
                    a51Summary.setText("A5.1 SIN CONFIRMAR: "+message);
                    note("== A5.1 ERROR: "+message+" ==");
                }
                process=null;ownBusy=false;setManagerBusy.accept(false);refresh();
            }
        }.execute();
    }
    private static final class A51Outcome {
        final List<String[]> rows=new ArrayList<String[]>();
        String[] result;
        boolean finished,problem;
        int exitCode=-1;
        String error;
    }

    /** A5.2: emitir propuestas por partido unico y generar CSV para revision humana. */
    private void runA52(){
        if(ownBusy || managerBusy.get())return;
        final String signature=key();
        final Path dir;
        final List<String> cmd=new ArrayList<String>();
        final Path output;
        try {
            if(!a51Checked())throw new IOException("A5.2 requiere completar A5.1 de esta liga.");
            String ls=lsId.getText().trim(),tc=tcId.getText().trim();
            if(!ls.matches("[0-9]{1,15}")||!tc.matches("[0-9]{1,15}"))
                throw new IOException("IDs LS/TC invalidos para A5.2.");
            java.time.LocalDate from=java.time.LocalDate.parse(a52From.getText().trim());
            java.time.LocalDate to=java.time.LocalDate.parse(a52To.getText().trim());
            if(!to.isAfter(from))throw new IOException("Hasta exclusivo debe ser posterior a Desde.");
            int tol=(Integer)a52Tolerance.getValue();
            dir=Paths.get(projectPath.get().trim()).toAbsolutePath().normalize();
            Path java=Paths.get(java8Path.get().trim()).toAbsolutePath().normalize();
            if(!Files.isRegularFile(dir.resolve("pom.xml")))throw new IOException("Comprueba el proyecto totalcorner-downloader.");
            TotalCornerManager.verifyJava8(java);
            if(System.getenv("TOTALCORNER_DB_PASSWORD")==null)
                throw new IOException("Falta TOTALCORNER_DB_PASSWORD en Manager.");
            Path reports=dir.resolve("reports/totalcorner");
            String file="a52_propuestas_ls"+ls+"_tc"+tc+"_"+TS.format(LocalDateTime.now())+".csv";
            output=reports.resolve(file);
            Collections.addAll(cmd,java.toString(),"-Dfile.encoding=UTF-8","-cp",
                TotalCornerManager.managerCodeLocation()+File.pathSeparator+TotalCornerManager.buildClasspath(dir),
                TEAMS_A52,"--ls-league-id="+ls,"--tc-league-id="+tc,
                "--from="+from,"--to-exclusive="+to,"--tolerance-minutes="+tol,
                "--output="+output);
            if(tol>15 && JOptionPane.showConfirmDialog(this,
                "La tolerancia supera 15 minutos. Se pueden detectar falsas coincidencias.\n"
                +"El CSV sera solo una propuesta para revision manual. Continuar?",
                "Revision ampliada A5.2",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)
                    !=JOptionPane.YES_OPTION)return;
        }catch(Exception ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"Bloque 01 A5.2",JOptionPane.ERROR_MESSAGE);return;}
        ownBusy=true;cancelled=false;setManagerBusy.accept(true);
        a52TableModel.setRowCount(0);a52Summary.setText("Consultando evidencia de partidos...");
        a52Path.setText("CSV en preparacion: "+output);
        refresh();
        note("=== A5.2 CONSULTA | "+signature+" | MySQL SOLO LECTURA | API=0 ===");
        final Path logs=dir.resolve("target/totalcorner-manager-logs");
        new SwingWorker<A52Outcome,String>() {
            @Override protected A52Outcome doInBackground(){
                A52Outcome out=new A52Outcome();
                Path file=logs.resolve("A-a52-proposals-"+TS.format(LocalDateTime.now())+".log");
                try {
                    Files.createDirectories(logs);publish("Log: "+file);
                    ProcessBuilder pb=new ProcessBuilder(cmd);pb.directory(dir.toFile());pb.redirectErrorStream(true);
                    process=pb.start();
                    try(BufferedReader r=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
                        BufferedWriter w=Files.newBufferedWriter(file,StandardCharsets.UTF_8)){
                        String line;
                        while((line=r.readLine())!=null){
                            publish(line);w.write(line);w.newLine();
                            if(line.startsWith("A52_TEAM\t")){
                                String[] parts=line.split("\t",-1);if(parts.length==7)out.rows.add(parts);
                            }
                            if(line.startsWith("A52_RESULT\t")){
                                String[] parts=line.split("\t",-1);if(parts.length==8)out.summary=parts;
                            }
                            if(line.startsWith("A52_CSV\t"))out.csv=line.substring(8);
                            if(line.startsWith("A52_ERROR:"))out.problem=true;
                            if(line.startsWith("A52 FIN |"))out.finished=true;
                        }
                    }
                    out.exitCode=process.waitFor();
                }catch(Exception ex){out.error=ex.getClass().getSimpleName()+": "+ex.getMessage();}
                return out;
            }
            @Override protected void process(List<String> lines){for(String line:lines)note(line);}
            @Override protected void done(){
                A52Outcome out;
                try{out=get();}catch(Exception ex){out=new A52Outcome();out.error=ex.getMessage();}
                if(!cancelled&&out.error==null&&!out.problem&&out.finished&&out.exitCode==0&&
                   out.summary!=null&&signature.equals(key())&&out.csv!=null&&
                   Integer.toString(out.rows.size()).equals(out.summary[1])&&
                   Files.isRegularFile(Paths.get(out.csv))){
                    for(String[] values:out.rows)a52TableModel.addRow(new Object[]{
                        values[1],values[2],values[3],values[4],values[5],values[6]});
                    a52ConsultKey=signature;
                    a52Summary.setText("Propuestas="+out.summary[1]+" | TC equipos="+out.summary[2]
                       +" | evidencia>=3="+out.summary[3]+" | alias TC="+out.summary[4]
                       +" | ambiguos="+out.summary[5]+" | limitados="+out.summary[6]
                       +" | sin candidato="+out.summary[7]);
                    a52Path.setText("CSV: "+out.csv);
                    if(Integer.parseInt(out.summary[1])>0 && Integer.parseInt(out.summary[2])>0){
                        recordConfirmation("a52",LocalDateTime.now().toString());
                        leagueHistory.setProperty("league."+encode(signature)+".a52.csv",out.csv);
                        leagueHistory.setProperty("league."+encode(signature)+".a52.scope",a52Scope());
                        saveState();
                        note("== A5.2 CSV GENERADO: revisar propuestas; NO se ha enlazado ningun equipo. ==");
                    }else note("== A5.2 SIN PROPUESTAS: revisar rango, zona horaria y datos antes de confirmar. ==");
                }else{
                    String msg=cancelled?"Proceso interrumpido":out.error!=null?out.error:
                         "Resultado A5.2 incompleto (codigo "+out.exitCode+")";
                    a52Summary.setText("A5.2 ERROR: "+msg);note("== A5.2 ERROR: "+msg+" ==");
                }
                process=null;ownBusy=false;setManagerBusy.accept(false);refresh();
            }
        }.execute();
    }
    private static final class A52Outcome {
        final List<String[]> rows=new ArrayList<String[]>();
        String[] summary;String csv;boolean finished,problem;int exitCode=-1;String error;
    }

    private void loadA53(){
        if(!a52Checked())return;
        try{
            String csv=a52Csv();java.nio.file.Path file=Paths.get(csv);
            Map<String,String[]> rows=A53TeamLinksRunner.loadCsv(file);
            a53LoadedKey="";a53TableModel.setRowCount(0);
            int strong=0,alias=0,other=0;
            for(String[] v:rows.values()){
                a53TableModel.addRow(new Object[]{false,v[0],v[2],v[3],v[4],v[5],v[8]});
                if("CANDIDATO_CON_EVIDENCIA".equals(v[8]))strong++;
                else if("POSIBLES_ALIAS_TC_REVISAR".equals(v[8]))alias++;else other++;
            }
            a53LoadedCsv=csv;a53LoadedScope=a52Scope();a53LoadedKey=key();a53PreviewSignature="";
            a53Summary.setText("CSV cargado: evidencia="+strong+" | alias="+alias+" | otros no seleccionables="+other+"."
               +" Revisa individualmente los alias.");
            note("=== A5.3 CSV CARGADO (NO SQL): "+file+" ===");
        }catch(Exception ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"A5.3 CSV",JOptionPane.ERROR_MESSAGE);}
        refresh();
    }
    private void selectA53(String category,boolean addToSelection){
        for(int i=0;i<a53TableModel.getRowCount();i++){
            boolean matches=category.equals(a53TableModel.getValueAt(i,6));
            boolean next=matches||(addToSelection && Boolean.TRUE.equals(a53TableModel.getValueAt(i,0)));
            a53TableModel.setValueAt(next,i,0);
        }
        refresh();
    }
    private String a53Pairs(){
        StringJoiner join=new StringJoiner(",");
        for(int i=0;i<a53TableModel.getRowCount();i++)
            if(Boolean.TRUE.equals(a53TableModel.getValueAt(i,0)))
                join.add(a53TableModel.getValueAt(i,1)+":"+a53TableModel.getValueAt(i,3));
        return join.toString();
    }
    private int a53SelectedCount(){int n=0;for(int i=0;i<a53TableModel.getRowCount();i++)
        if(Boolean.TRUE.equals(a53TableModel.getValueAt(i,0)))n++;return n;}
    private int a53AliasCount(){int n=0;for(int i=0;i<a53TableModel.getRowCount();i++)
        if(Boolean.TRUE.equals(a53TableModel.getValueAt(i,0))&&"POSIBLES_ALIAS_TC_REVISAR".equals(a53TableModel.getValueAt(i,6)))n++;return n;}
    private String a53Signature(){
        try {return key()+"|"+a53LoadedScope+"|"+a53LoadedCsv+"|"+
                 java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(
                    Files.readAllBytes(Paths.get(a53LoadedCsv))))+"|"+a53Pairs();}
        catch(Exception ex){return "";}
    }
    private void runA53(final boolean apply){
        if(ownBusy||managerBusy.get())return;
        final String signature=key(),selection=a53Pairs(),stamp=a53Signature();
        final Path dir,audit;final List<String> cmd=new ArrayList<String>();
        try{
            if(!a52Checked()||!signature.equals(a53LoadedKey)||!a52Csv().equals(a53LoadedCsv)||!a52Scope().equals(a53LoadedScope))
                throw new IOException("Carga el CSV A5.2 actual antes de aplicar.");
            if(selection.isEmpty())throw new IOException("Selecciona al menos una fila.");
            if(apply && (stamp.isEmpty()||!stamp.equals(a53PreviewSignature)))
                throw new IOException("Simula primero la seleccion exacta, sin modificar CSV ni filas despues.");
            dir=Paths.get(projectPath.get().trim()).toAbsolutePath().normalize();
            Path java=Paths.get(java8Path.get().trim()).toAbsolutePath().normalize();
            TotalCornerManager.verifyJava8(java);
            if(!Files.isRegularFile(dir.resolve("pom.xml")))throw new IOException("Proyecto totalcorner-downloader no encontrado.");
            if(System.getenv("TOTALCORNER_DB_PASSWORD")==null)throw new IOException("Falta TOTALCORNER_DB_PASSWORD.");
            audit=dir.resolve("reports/totalcorner/a53_aplicados_ls"+lsId.getText().trim()+"_tc"+tcId.getText().trim()+"_"+TS.format(LocalDateTime.now())+".csv");
            Collections.addAll(cmd,java.toString(),"-Dfile.encoding=UTF-8","-cp",
                TotalCornerManager.managerCodeLocation()+File.pathSeparator+TotalCornerManager.buildClasspath(dir),
                TEAMS_A53,"--mode="+(apply?"APPLY":"PREVIEW"),
                "--ls-league-id="+lsId.getText().trim(),"--tc-league-id="+tcId.getText().trim(),
                "--from="+a52From.getText().trim(),"--to-exclusive="+a52To.getText().trim(),
                "--tolerance-minutes="+a52Tolerance.getValue(),"--csv="+a53LoadedCsv,
                "--pairs="+selection,"--expected="+a53SelectedCount());
            if(apply){cmd.add("--audit="+audit);
                if(JOptionPane.showConfirmDialog(this,
                    "Se insertaran "+a53SelectedCount()+" enlaces VERIFIED en tc_team_link, incluidos "
                    +a53AliasCount()+" alias revisados.\n"
                    +"NO se modifica ls_match ni se reescriben enlaces existentes.\n"
                    +"Se validara MySQL antes del INSERT y se abortara ante conflictos.\n"
                    +"Has revisado CADA equipo seleccionado?",
                    "A5.3: confirmar insercion SQL",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)
                    !=JOptionPane.YES_OPTION)return;
            }
        }catch(Exception ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"Bloque 01 A5.3",JOptionPane.ERROR_MESSAGE);return;}
        ownBusy=true;cancelled=false;setManagerBusy.accept(true);refresh();
        note("=== A5.3 "+(apply?"APLICAR":"SIMULAR")+" | "+signature+" | seleccion="+a53SelectedCount()+" | alias="+a53AliasCount()+" ===");
        final Path logs=dir.resolve("target/totalcorner-manager-logs");
        new SwingWorker<A53Outcome,String>(){
            @Override protected A53Outcome doInBackground(){
                A53Outcome out=new A53Outcome();
                Path file=logs.resolve("A-a53-"+(apply?"apply":"preview")+"-"+TS.format(LocalDateTime.now())+".log");
                try{
                    Files.createDirectories(logs);publish("Log: "+file);
                    ProcessBuilder pb=new ProcessBuilder(cmd);pb.directory(dir.toFile());pb.redirectErrorStream(true);
                    process=pb.start();
                    try(BufferedReader r=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
                        BufferedWriter w=Files.newBufferedWriter(file,StandardCharsets.UTF_8)){
                        String line;while((line=r.readLine())!=null){
                            publish(line);w.write(line);w.newLine();
                            if(line.startsWith("A53_PLAN\t"))out.plan=line.split("\t",-1);
                            if(line.startsWith("A53_APPLIED\t"))out.applied=line.split("\t",-1);
                            if(line.startsWith("A53_AUDIT\t"))out.audit=line.substring(10);
                            if(line.startsWith("A53_ERROR:"))out.problem=true;
                            if(line.startsWith("A53_PREVIEW_OK"))out.preview=true;
                            if(line.startsWith("A53_DONE |"))out.finished=true;
                        }
                    }
                    out.code=process.waitFor();
                }catch(Exception ex){out.error=ex.getClass().getSimpleName()+": "+ex.getMessage();}
                return out;
            }
            @Override protected void process(List<String> lines){for(String line:lines)note(line);}
            @Override protected void done(){
                A53Outcome result;
                try{result=get();}catch(Exception ex){result=new A53Outcome();result.error=ex.getMessage();}
                if(!cancelled&&result.code==0&&result.error==null&&!result.problem&&signature.equals(key())&&
                   stamp.equals(a53Signature())&&result.plan!=null&&Integer.toString(a53SelectedCount()).equals(result.plan[1])){
                    if(!apply&&result.preview){a53PreviewSignature=stamp;
                        a53Summary.setText("SIMULACION OK: "+result.plan[1]+" enlaces; candidatos="+result.plan[2]
                            +"; alias="+result.plan[3]+"; 0 escrituras SQL.");
                        note("== A5.3_SIMULACION_OK: aplicar solo si estas de acuerdo con el resumen ==");
                    }else if(apply&&result.finished&&result.applied!=null&&result.plan[1].equals(result.applied[1])){
                        a53PreviewSignature="";recordConfirmation("a53",LocalDateTime.now().toString());
                        String prefix="league."+encode(signature);
                        leagueHistory.setProperty(prefix+".a53.count",result.applied[1]);
                        leagueHistory.setProperty(prefix+".a53.audit",result.audit==null?"":result.audit);saveState();
                        a53Summary.setText("APLICADOS="+result.applied[1]+" | alias="+result.applied[3]+" | Informe: "+result.audit);
                        note("== A5.3 ENLACES VERIFIED CONFIRMADOS: "+result.applied[1]+" ==");
                    }
                }else{
                    String msg=cancelled?"Proceso interrumpido":result.error!=null?result.error:
                        "Fallo o resultado incompleto. Ver log; puede haber ocurrido COMMIT antes del fallo de interfaz.";
                    a53PreviewSignature="";a53Summary.setText("A5.3 sin confirmar: "+msg);
                    note("== A5.3 ERROR: "+msg+" ==");
                }
                process=null;ownBusy=false;setManagerBusy.accept(false);refresh();
            }
        }.execute();
    }
    private static final class A53Outcome {
        String[] plan,applied;String audit,error;boolean problem,preview,finished;int code=-1;
    }

    /** A6.1 solo SELECT; informe CSV por ls_match, sin cambios de enlaces ni temporadas. */
    private void runA61(){
        if(ownBusy||managerBusy.get())return;
        final String signature=key(),scope=a61Scope();
        final Path dir,output;final List<String> cmd=new ArrayList<String>();
        try{
            if(!a53Applied())throw new IOException("A6.1 requiere que A5.3 tenga al menos un lote de equipos VERIFIED.");
            dir=Paths.get(projectPath.get().trim()).toAbsolutePath().normalize();
            Path javaExecutable=Paths.get(java8Path.get().trim()).toAbsolutePath().normalize();
            if(!Files.isRegularFile(dir.resolve("pom.xml")))throw new IOException("Proyecto downloader no encontrado.");
            TotalCornerManager.verifyJava8(javaExecutable);
            if(System.getenv("TOTALCORNER_DB_PASSWORD")==null)throw new IOException("Falta TOTALCORNER_DB_PASSWORD.");
            java.time.LocalDate f=java.time.LocalDate.parse(a61From.getText().trim());
            java.time.LocalDate t=java.time.LocalDate.parse(a61To.getText().trim());
            if(!t.isAfter(f))throw new IOException("Hasta debe ser posterior a Desde.");
            output=dir.resolve("reports/totalcorner/a61_partidos_ls"+lsId.getText().trim()+"_tc"+tcId.getText().trim()+"_"+TS.format(LocalDateTime.now())+".csv");
            Collections.addAll(cmd,javaExecutable.toString(),"-Dfile.encoding=UTF-8","-cp",
                TotalCornerManager.managerCodeLocation()+File.pathSeparator+TotalCornerManager.buildClasspath(dir),
                MATCHES_A61,"--ls-league-id="+lsId.getText().trim(),"--tc-league-id="+tcId.getText().trim(),
                "--from="+f,"--to-exclusive="+t,"--tolerance-minutes="+a61Tolerance.getValue(),
                "--output="+output);
        }catch(Exception ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"Bloque 01 A6.1",JOptionPane.ERROR_MESSAGE);return;}
        ownBusy=true;cancelled=false;setManagerBusy.accept(true);refresh();
        a62PreviewSignature="";a61ConsultKey="";a61TableModel.setRowCount(0);
        note("=== A6.1 PREVIEW | "+signature+" | SQL SOLO SELECT, API=0 ===");
        final Path logs=dir.resolve("target/totalcorner-manager-logs");
        new SwingWorker<A61Outcome,String>() {
            @Override protected A61Outcome doInBackground(){
                A61Outcome result=new A61Outcome();
                Path file=logs.resolve("A-a61-preview-"+TS.format(LocalDateTime.now())+".log");
                try{
                    Files.createDirectories(logs);publish("Log: "+file);
                    ProcessBuilder pb=new ProcessBuilder(cmd);pb.directory(dir.toFile());pb.redirectErrorStream(true);
                    process=pb.start();
                    try(BufferedReader r=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
                        BufferedWriter w=Files.newBufferedWriter(file,StandardCharsets.UTF_8)){
                        String line;while((line=r.readLine())!=null){publish(line);w.write(line);w.newLine();
                            if(line.startsWith("A61_STATUS\t"))result.status.add(line.split("\t",-1));
                            if(line.startsWith("A61_RESULT\t"))result.summary=line.split("\t",-1);
                            if(line.startsWith("A61_CSV\t"))result.csv=line.substring(8);
                            if(line.startsWith("A61_FIN |"))result.finished=true;
                            if(line.startsWith("A61_ERROR:"))result.problem=true;
                        }
                    }
                    result.code=process.waitFor();
                }catch(Exception ex){result.error=ex.getMessage();}
                return result;
            }
            @Override protected void process(List<String> lines){for(String l:lines)note(l);}
            @Override protected void done(){
                A61Outcome out;try{out=get();}catch(Exception ex){out=new A61Outcome();out.error=ex.getMessage();}
                if(!cancelled&&out.code==0&&!out.problem&&out.error==null&&out.finished&&
                   out.summary!=null&&out.summary.length==7&&output.toString().equals(out.csv)&&
                   Files.isRegularFile(output)&&signature.equals(key())&&scope.equals(a61Scope())){
                    a61ConsultKey=signature;a61ConsultScope=scope;a61TableModel.setRowCount(0);
                    for(String[] st:out.status)if(st.length==3)a61TableModel.addRow(new Object[]{st[1],st[2]});
                    a61Summary.setText("Footiqo="+out.summary[1]+" | ya VERIFIED="+out.summary[2]+
                        " | listos="+out.summary[3]+" | revisar="+out.summary[4]+" | sin candidato="+out.summary[5]);
                    recordConfirmation("a61",LocalDateTime.now().toString());
                    String prefix="league."+encode(signature);
                    leagueHistory.setProperty(prefix+".a61.scope",scope);
                    leagueHistory.setProperty(prefix+".a61.team_version",a53When());
                    leagueHistory.setProperty(prefix+".a61.csv",output.toString());
                    leagueHistory.setProperty(prefix+".a61.count",out.summary[3]);saveState();
                    note("== A6.1 CSV GENERADO: NO se han creado enlaces. Siguiente A6.2 tras revisar. ==");
                }else{
                    a61Summary.setText("A6.1 sin confirmar: "+(out.error==null?"revisar log":out.error));
                    note("== A6.1 ERROR: resultado incompleto; CSV no confirmado. ==");
                }
                process=null;ownBusy=false;setManagerBusy.accept(false);refresh();
            }
        }.execute();
    }
    /** A6.2: 1) recalcular y simular; 2) confirmar e insertar en una sola transaccion. */
    private void runA62(final boolean apply){
        if(ownBusy||managerBusy.get())return;
        final String signature=key(),stamp=a62Signature();
        final Path dir;final List<String> cmd=new ArrayList<String>();
        try{
            if(!a61Checked()||stamp.isEmpty())throw new IOException("Genera y revisa CSV A6.1 para esta liga.");
            if(((Number)a61Tolerance.getValue()).intValue()>15)throw new IOException("A6.2 automatico requiere tolerancia <=15 min. Resto A6.3.");
            if(apply&&!stamp.equals(a62PreviewSignature))throw new IOException("Simula A6.2 con este CSV antes de aplicar.");
            dir=Paths.get(projectPath.get().trim()).toAbsolutePath().normalize();
            Path java=Paths.get(java8Path.get().trim()).toAbsolutePath().normalize();
            TotalCornerManager.verifyJava8(java);
            if(!Files.isRegularFile(dir.resolve("pom.xml")))throw new IOException("Proyecto totalcorner-downloader no encontrado.");
            if(System.getenv("TOTALCORNER_DB_PASSWORD")==null)throw new IOException("Falta TOTALCORNER_DB_PASSWORD.");
            Path audit=dir.resolve("reports/totalcorner/a62_aplicados_ls"+lsId.getText().trim()+"_tc"+
                tcId.getText().trim()+"_"+TS.format(LocalDateTime.now())+".csv");
            Collections.addAll(cmd,java.toString(),"-Dfile.encoding=UTF-8","-cp",
                TotalCornerManager.managerCodeLocation()+File.pathSeparator+TotalCornerManager.buildClasspath(dir),
                MATCHES_A62,"--mode="+(apply?"APPLY":"PREVIEW"),"--ls-league-id="+lsId.getText().trim(),
                "--tc-league-id="+tcId.getText().trim(),"--from="+a61From.getText().trim(),
                "--to-exclusive="+a61To.getText().trim(),"--tolerance-minutes="+a61Tolerance.getValue(),
                "--csv="+a61Csv(),"--expected="+a61Count(),"--audit="+(apply?audit.toString():"-"));
            if(apply&&JOptionPane.showConfirmDialog(this,
                "Se insertaran "+a61Count()+" enlaces VERIFIED de partidos en tc_match_link.\n"
               +"La app revalidara todos los candidatos con MySQL actual y abortara TODO ante conflicto.\n"
               +"No modifica ls_match, marcadores, cuotas ni equipos.\n"
               +"¿Has revisado los candidatos seguros de A6.1?",
                "A6.2: confirmar carga masiva",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)
                !=JOptionPane.YES_OPTION)return;
        }catch(Exception ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"Bloque 01 A6.2",JOptionPane.ERROR_MESSAGE);return;}
        ownBusy=true;cancelled=false;setManagerBusy.accept(true);refresh();
        note("=== A6.2 "+(apply?"APPLY":"PREVIEW")+" | "+signature+" | esperados="+a61Count()+" ===");
        final Path logs=dir.resolve("target/totalcorner-manager-logs");
        new SwingWorker<A62Outcome,String>(){
            @Override protected A62Outcome doInBackground(){
                A62Outcome out=new A62Outcome();
                Path file=logs.resolve("A-a62-"+(apply?"apply":"preview")+"-"+TS.format(LocalDateTime.now())+".log");
                try{
                    Files.createDirectories(logs);publish("Log: "+file);
                    ProcessBuilder pb=new ProcessBuilder(cmd);pb.directory(dir.toFile());pb.redirectErrorStream(true);
                    process=pb.start();
                    try(BufferedReader r=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
                        BufferedWriter w=Files.newBufferedWriter(file,StandardCharsets.UTF_8)){
                        String line;while((line=r.readLine())!=null){publish(line);w.write(line);w.newLine();
                            if(line.startsWith("A62_PLAN\t"))out.plan=line.split("\t",-1);
                            if(line.startsWith("A62_APPLIED\t"))out.applied=line.split("\t",-1);
                            if(line.startsWith("A62_AUDIT\t"))out.audit=line.substring(10);
                            if(line.startsWith("A62_ERROR:"))out.problem=true;
                            if(line.startsWith("A62_PREVIEW_OK"))out.preview=true;
                            if(line.startsWith("A62_DONE |"))out.finished=true;
                        }
                    }
                    out.code=process.waitFor();
                }catch(Exception ex){out.error=ex.getClass().getSimpleName()+": "+ex.getMessage();}
                return out;
            }
            @Override protected void process(List<String> lines){for(String line:lines)note(line);}
            @Override protected void done(){
                A62Outcome out;try{out=get();}catch(Exception ex){out=new A62Outcome();out.error=ex.getMessage();}
                boolean valid=!cancelled&&out.code==0&&!out.problem&&out.error==null&&
                    signature.equals(key())&&stamp.equals(a62Signature())&&
                    out.plan!=null&&out.plan.length>=2&&a61Count().equals(out.plan[1]);
                if(valid&&!apply&&out.preview){
                    a62PreviewSignature=stamp;
                    a62Summary.setText("SIMULACION OK: "+out.plan[1]+" enlaces seguros. Listo para aplicar.");
                    note("== A6.2_SIMULACION_OK | MySQL sin escrituras ==");
                }else if(valid&&apply&&out.finished&&out.applied!=null&&out.applied.length>=2&&out.plan[1].equals(out.applied[1])){
                    a62PreviewSignature="";recordConfirmation("a62",LocalDateTime.now().toString());
                    String prefix="league."+encode(signature);
                    leagueHistory.setProperty(prefix+".a62.count",out.applied[1]);
                    leagueHistory.setProperty(prefix+".a62.audit",out.audit==null?"":out.audit);saveState();
                    a62Summary.setText("COMMIT confirmado: "+out.applied[1]+" enlaces VERIFIED. Repetir A6.1 para actualizar pendientes.");
                    note("== A6.2_ENLACES_VERIFIED_CONFIRMADOS: "+out.applied[1]+" ==");
                }else{
                    a62PreviewSignature="";
                    String message=cancelled?"Interrumpido; comprobar SQL antes de reintentar.":out.error!=null?out.error:
                        "No se ha confirmado el resultado. Revisar log y MySQL antes de reintentar.";
                    a62Summary.setText("A6.2 ERROR: "+message);note("== A6.2 ERROR: "+message+" ==");
                }
                process=null;ownBusy=false;setManagerBusy.accept(false);refresh();
            }
        }.execute();
    }
    /** A6.3: solo SELECT, informe de pendientes en ambos sentidos y CSV para revision. */
    private void runA63(){
        if(ownBusy||managerBusy.get())return;
        final String league=key(),scope=a63Scope();final Path dir,out;final List<String> cmd=new ArrayList<String>();
        try{
            if(!a62Applied())throw new IOException("A6.3 requiere A6.2 aplicado.");
            java.time.LocalDate from=java.time.LocalDate.parse(a63From.getText().trim()),to=java.time.LocalDate.parse(a63To.getText().trim());
            if(!to.isAfter(from))throw new IOException("Rango de fechas incorrecto.");
            dir=Paths.get(projectPath.get().trim()).toAbsolutePath().normalize();
            Path java=Paths.get(java8Path.get().trim()).toAbsolutePath().normalize();TotalCornerManager.verifyJava8(java);
            if(!Files.isRegularFile(dir.resolve("pom.xml")))throw new IOException("Falta totalcorner-downloader.");
            if(System.getenv("TOTALCORNER_DB_PASSWORD")==null)throw new IOException("Falta TOTALCORNER_DB_PASSWORD.");
            out=dir.resolve("reports/totalcorner/a63_pendientes_ls"+lsId.getText().trim()+"_tc"+tcId.getText().trim()+"_"+TS.format(LocalDateTime.now())+".csv");
            Collections.addAll(cmd,java.toString(),"-Dfile.encoding=UTF-8","-cp",TotalCornerManager.managerCodeLocation()+File.pathSeparator+TotalCornerManager.buildClasspath(dir),
                MATCHES_A63,"--mode=REPORT","--ls-league-id="+lsId.getText().trim(),"--tc-league-id="+tcId.getText().trim(),
                "--from="+from,"--to-exclusive="+to,"--tolerance-minutes="+a63Tolerance.getValue(),"--max-proposals="+a63Max.getValue(),
                "--output="+out,"--selection=-","--expected=0","--audit=-");
            if(((Number)a63Tolerance.getValue()).intValue()>1440&&JOptionPane.showConfirmDialog(this,
                "Margen superior a 24 horas. Se pueden mostrar candidatos no relacionados.\nLa consulta no enlaza ninguno. ¿Continuar?",
                "A6.3 revision ampliada",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.YES_OPTION)return;
        }catch(Exception ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"A6.3",JOptionPane.ERROR_MESSAGE);return;}
        ownBusy=true;cancelled=false;setManagerBusy.accept(true);a64PreviewSignature="";a63ConsultKey="";a63TableModel.setRowCount(0);a63Fingerprints.clear();refresh();
        note("=== A6.3 CONSULTAR | "+league+" | margen "+a63Tolerance.getValue()+" min | SOLO LECTURA ===");
        final Path logs=dir.resolve("target/totalcorner-manager-logs");
        new SwingWorker<A63Outcome,String>(){
            @Override protected A63Outcome doInBackground(){
                A63Outcome result=new A63Outcome();Path logFile=logs.resolve("A-a63-report-"+TS.format(LocalDateTime.now())+".log");
                try{Files.createDirectories(logs);publish("Log: "+logFile);
                    ProcessBuilder pb=new ProcessBuilder(cmd);pb.directory(dir.toFile());pb.redirectErrorStream(true);process=pb.start();
                    try(BufferedReader r=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
                        BufferedWriter w=Files.newBufferedWriter(logFile,StandardCharsets.UTF_8)){
                        String line;while((line=r.readLine())!=null){publish(line);w.write(line);w.newLine();
                            if(line.startsWith("A63_RESULT\t"))result.plan=line.split("\t",-1);
                            if(line.startsWith("A63_REPORT_OK"))result.finished=true;
                            if(line.startsWith("A63_ERROR:"))result.problem=true;
                            if(line.startsWith("A63_CSV\t"))result.csv=line.substring(8);
                        }}
                    result.code=process.waitFor();
                }catch(Exception ex){result.error=ex.getClass().getSimpleName()+": "+ex.getMessage();}
                return result;
            }
            @Override protected void process(List<String> lines){for(String line:lines)note(line);}
            @Override protected void done(){
                A63Outcome r;try{r=get();}catch(Exception e){r=new A63Outcome();r.error=e.getMessage();}
                if(!cancelled&&r.code==0&&r.error==null&&!r.problem&&r.finished&&r.plan!=null&&r.plan.length>=5&&
                    out.toString().equals(r.csv)&&Files.isRegularFile(out)&&league.equals(key())&&scope.equals(a63Scope())){
                    try{
                        List<String[]> rows=A63ManualMatchRunner.loadReport(out);
                        loadingA63=true;
                        try{
                        a63TableModel.setRowCount(0);a63Fingerprints.clear();
                        for(String[] v:rows){
                            if(!"SOLO_LS".equals(v[0])&&!"SOLO_TC".equals(v[0]))a63Fingerprints.put(v[1]+":"+v[2],v[20]);
                            a63TableModel.addRow(new Object[]{false,v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[8],v[9],v[10],v[11],v[12],v[13],v[14],v[15],v[16],v[17],v[19]});
                        }
                        }finally{loadingA63=false;}
                        a63ConsultKey=league;a63ConsultScope=scope;
                        a63Summary.setText("LS sin enlazar="+(r.plan.length>6?r.plan[6]:"?")+" | LS con candidatos="+(r.plan.length>8?r.plan[8]:"?")+
                            " | LS sin candidato="+r.plan[2]+" | Pares propuestos="+r.plan[1]+" | TC sin candidato="+r.plan[3]+" | Marcados=0");
                        recordConfirmation("a63",LocalDateTime.now().toString());
                        String prefix="league."+encode(league);leagueHistory.setProperty(prefix+".a63.scope",scope);
                        leagueHistory.setProperty(prefix+".a63.csv",out.toString());saveState();
                        filterA63();note("== A6.3 CSV GENERADO: revisar candidatos; ninguno enlazado. ==");
                    }catch(Exception ex){r.error="CSV no cargado: "+ex.getMessage();note("== A6.3 ERROR CSV: "+r.error+" ==");}
                }else{note("== A6.3 ERROR: "+(r.error==null?"resultado incompleto, ver log":r.error)+" ==");}
                process=null;ownBusy=false;setManagerBusy.accept(false);refresh();
            }
        }.execute();
    }
    /** A6.4: aplicar filas revisadas marcadas, transaccion 1:1 y nueva revision SQL. */
    private void runA64(final boolean apply){
        if(ownBusy||managerBusy.get())return;
        final String league=key(),stamp=a63Signature();final int expected=a63SelectedCount();
        final Path dir;final List<String> cmd=new ArrayList<String>();
        try{
            if(!a63Checked()||stamp.isEmpty())throw new IOException("Reconsulta A6.3 con los filtros actuales.");
            if(expected<=0)throw new IOException("Selecciona al menos una pareja para revisar.");
            if(apply&&!stamp.equals(a64PreviewSignature))throw new IOException("Simula A6.4 con la seleccion actual antes de aplicar.");
            Set<String> ls=new HashSet<String>(),tc=new HashSet<String>();int weak=0;
            for(int i=0;i<a63TableModel.getRowCount();i++)if(Boolean.TRUE.equals(a63TableModel.getValueAt(i,0))){
                String l=a63TableModel.getValueAt(i,2).toString(),t=a63TableModel.getValueAt(i,3).toString();
                if(!ls.add(l)||!tc.add(t))throw new IOException("Has marcado varias parejas para un mismo partido LS o TC; desmarca las alternativas.");
                if(!"CANDIDATO".equals(a63TableModel.getValueAt(i,1)))weak++;
            }
            if(apply){
                if(JOptionPane.showConfirmDialog(this,"Se van a insertar "+expected+" enlaces VERIFIED manuales de "+league+".\n"
                    +"Parejas en estado REVISAR/AMBIGUO: "+weak+". Se permiten diferencias de marcador, nombres o fecha.\n"
                    +"Has revisado personalmente cada pareja seleccionada, fecha, equipos y fase de competicion?\n"
                    +"Una transaccion cancelara TODO si encuentra duplicados o conflictos.","A6.4 · Confirmacion manual",
                    JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.YES_OPTION)return;
                if(JOptionPane.showConfirmDialog(this,"Confirmacion FINAL: aplicar exactamente "+expected+" enlaces manuales?",
                    "A6.4: insertar en MySQL",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.YES_OPTION)return;
            }
            dir=Paths.get(projectPath.get().trim()).toAbsolutePath().normalize();
            Path java=Paths.get(java8Path.get().trim()).toAbsolutePath().normalize();TotalCornerManager.verifyJava8(java);
            if(System.getenv("TOTALCORNER_DB_PASSWORD")==null)throw new IOException("Falta TOTALCORNER_DB_PASSWORD.");
            Path selection=dir.resolve("target/totalcorner-manager-logs/a64_seleccion_ls"+lsId.getText().trim()+"_tc"+tcId.getText().trim()+"_"+TS.format(LocalDateTime.now())+".csv");
            Files.createDirectories(selection.getParent());
            try(BufferedWriter w=Files.newBufferedWriter(selection,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)){
                w.write("ls_match_id,tc_match_pk,huella\n");
                for(int i=0;i<a63TableModel.getRowCount();i++)if(Boolean.TRUE.equals(a63TableModel.getValueAt(i,0))){
                    String l=a63TableModel.getValueAt(i,2).toString(),t=a63TableModel.getValueAt(i,3).toString();
                    String hash=a63Fingerprints.get(l+":"+t);if(hash==null)throw new IOException("Falta huella para "+l+":"+t);
                    w.write(l+","+t+","+hash+"\n");
                }
            }
            Collections.addAll(cmd,java.toString(),"-Dfile.encoding=UTF-8","-cp",TotalCornerManager.managerCodeLocation()+File.pathSeparator+TotalCornerManager.buildClasspath(dir),
                MATCHES_A63,"--mode="+(apply?"APPLY":"PREVIEW"),"--ls-league-id="+lsId.getText().trim(),"--tc-league-id="+tcId.getText().trim(),
                "--from="+a63From.getText().trim(),"--to-exclusive="+a63To.getText().trim(),
                "--tolerance-minutes="+a63Tolerance.getValue(),"--max-proposals="+a63Max.getValue(),"--output=-",
                "--selection="+selection,"--expected="+expected,
                "--audit="+(apply?dir.resolve("reports/totalcorner/a64_enlaces_manuales_ls"+lsId.getText().trim()+"_tc"+tcId.getText().trim()+"_"+TS.format(LocalDateTime.now())+".csv").toString():"-"));
        }catch(Exception ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"A6.4",JOptionPane.ERROR_MESSAGE);return;}
        ownBusy=true;cancelled=false;setManagerBusy.accept(true);refresh();note("=== A6.4 "+(apply?"APLICAR":"SIMULAR")+" | "+league+" | seleccion="+expected+" ===");
        final Path logs=dir.resolve("target/totalcorner-manager-logs");
        new SwingWorker<A63Outcome,String>(){
            @Override protected A63Outcome doInBackground(){
                A63Outcome r=new A63Outcome();Path file=logs.resolve("A-a64-"+(apply?"apply":"preview")+"-"+TS.format(LocalDateTime.now())+".log");
                try{Files.createDirectories(logs);publish("Log: "+file);
                    ProcessBuilder pb=new ProcessBuilder(cmd);pb.directory(dir.toFile());pb.redirectErrorStream(true);process=pb.start();
                    try(BufferedReader br=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
                        BufferedWriter w=Files.newBufferedWriter(file,StandardCharsets.UTF_8)){
                        String line;while((line=br.readLine())!=null){publish(line);w.write(line);w.newLine();
                            if(line.startsWith("A64_PLAN\t"))r.plan=line.split("\t",-1);
                            if(line.startsWith("A64_PREVIEW_OK"))r.preview=true;
                            if(line.startsWith("A64_APPLIED\t"))r.applied=line.split("\t",-1);
                            if(line.startsWith("A64_AUDIT\t"))r.csv=line.substring(10);
                            if(line.startsWith("A64_DONE |"))r.finished=true;
                            if(line.startsWith("A63_ERROR:"))r.problem=true;
                        }}
                    r.code=process.waitFor();
                }catch(Exception ex){r.error=ex.getClass().getSimpleName()+": "+ex.getMessage();}
                return r;
            }
            @Override protected void process(List<String> lines){for(String line:lines)note(line);}
            @Override protected void done(){
                A63Outcome r;try{r=get();}catch(Exception e){r=new A63Outcome();r.error=e.getMessage();}
                boolean ok=!cancelled&&r.code==0&&r.error==null&&!r.problem&&league.equals(key())&&stamp.equals(a63Signature())&&
                    r.plan!=null&&r.plan.length>=2&&Integer.toString(expected).equals(r.plan[1]);
                if(ok&&!apply&&r.preview){a64PreviewSignature=stamp;a64State.setText("A6.4 SIMULACION OK: "+expected+" enlaces manuales; 0 escrituras.");
                    note("== A6.4_SIMULACION_OK; revisar antes de aplicar ==");}
                else if(ok&&apply&&r.finished&&r.applied!=null&&r.applied.length>=2&&Integer.toString(expected).equals(r.applied[1])){
                    a64PreviewSignature="";recordConfirmation("a64",LocalDateTime.now().toString());
                    String prefix="league."+encode(league);leagueHistory.setProperty(prefix+".a64.count",Integer.toString(expected));
                    leagueHistory.setProperty(prefix+".a63.scope","");saveState();
                    a63ConsultKey="";a63ConsultScope="";a63TableModel.setRowCount(0);a63Fingerprints.clear();
                    a63Summary.setText("A6.4 COMMIT: "+expected+" enlaces. Informe: "+(r.csv==null?"ver log":r.csv)+". Repetir A6.3.");
                    note("== A6.4 COMMIT CONFIRMADO: "+expected+" enlaces VERIFIED; revisar los restantes. ==");
                }else{a64PreviewSignature="";
                    String error=cancelled?"Proceso interrumpido; verificar SQL antes de reintentar":r.error!=null?r.error:
                        "Resultado incompleto. Si llego a COMMIT, verificar MySQL antes de repetir.";
                    a64State.setText("A6.4 ERROR: "+error);note("== A6.4 ERROR: "+error+" ==");}
                process=null;ownBusy=false;setManagerBusy.accept(false);refresh();
            }
        }.execute();
    }
    private static final class A63Outcome{
        String[] plan,applied;String csv,error;boolean finished,preview,problem;int code=-1;
    }

    private static final class A62Outcome{
        String[] plan,applied;String audit,error;boolean problem,preview,finished;int code=-1;
    }

    private static final class A61Outcome{
        String[] summary;String csv,error;boolean finished,problem;int code=-1;
        List<String[]> status=new ArrayList<String[]>();
    }

    public static void selfTest(){
        if(!"7|85|USA MLS".equals(key("7","85","USA MLS")))throw new AssertionError("key");
        System.out.println("OK: NewLeaguePanel A1/A2 signature and UI wiring");
    }
}
