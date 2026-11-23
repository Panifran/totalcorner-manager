package com.footiqo.manager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TotalCorner Manager v1.3.0: asistente 02 (B1.1 -> B1.2 -> B2.1 -> B2.2 -> B2.3 -> B3 -> B4). Java 8, Swing.
 * Lanza el descargador existente EN UN PROCESO EXTERNO, no altera su codigo.
 * No almacena ni solicita tokens, contrasenas o credenciales.
 */
public final class TotalCornerManager extends JFrame {
    private static final long serialVersionUID = 1L;
    private static final String MAIN_CLASS = "com.footiqo.totalcorner.LeagueScheduleRefreshRunner";
    private static final Pattern SUMMARY = Pattern.compile("ligas_confirmadas=(\\d+)/(\\d+)");
    private static final Pattern CONFLICTS = Pattern.compile("conflictos_marcador=(\\d+)");
    private static final Pattern DST_UNCERTAIN = Pattern.compile("horas DST sin UTC=(\\d+)");
    private static final String SETTINGS = ".totalcorner-manager.properties";
    private static final String CONTROL_MAIN = "com.footiqo.manager.B11ControlRunner";
    private static final String B21_MAIN = "com.footiqo.manager.B21PreviewRunner";
    private static final String B22_MAIN = "com.footiqo.manager.B22LinkRunner";
    private static final String B23_MAIN = "com.footiqo.manager.B23ReportRunner";
    private static final String B3_CONTROL_MAIN = "com.footiqo.manager.B3ControlRunner";
    private static final String B3_RUNNER = "com.footiqo.totalcorner.HistoricalDownloadRunner";
    private static final String B4_CONTROL_MAIN = "com.footiqo.manager.B4CoverageControlRunner";
    private static final String B4_DIAG_MAIN = "com.footiqo.manager.B4PendingDiagnosticRunner";
    private static final String B4_RUNNER = "com.footiqo.totalcorner.OddsCoverageBuilder";
    private final JTextField project = new JTextField(37);
    private final JTextField java8 = new JTextField(37);
    private final JTextField config = new JTextField(37);
    private final JTextField runId = new JTextField(15);
    private final JSpinner fromPage = new JSpinner(new SpinnerNumberModel(1, 1, 100000, 1));
    private final JSpinner maxPages = new JSpinner(new SpinnerNumberModel(3, 1, 10, 1));
    private final DefaultListModel<League> leagueModel = new DefaultListModel<League>();
    private final JList<League> leagues = new JList<League>(leagueModel);
    private final JTextArea output = new JTextArea(15, 85);
    private final DefaultTableModel controlModel = new DefaultTableModel(new Object[] {
            "TC", "Liga", "Ult. TC (UTC)", "Ult. Footiqo (Madrid)", "Revision API (UTC)",
            "Sin enlace (60d)", "Sin cuotas (60d)", "Total", "Estado local"
    }, 0) {
        private static final long serialVersionUID = 1L;
        @Override public boolean isCellEditable(int row, int col) { return false; }
    };
    private final JTable controlTable = new JTable(controlModel);
    private final JButton controlButton = new JButton("1. Consultar estado B1.1 (solo lectura)");
    private final JButton gotoRefresh = new JButton("2. Refrescar calendarios B1.2 (cada ciclo)");
    private final JLabel guidance = new JLabel("<html><b>Paso 1:</b> consulta B1.1. Aunque indique cero pendientes, "
            + "<b>el siguiente paso es refrescar B1.2</b> en cada ciclo.</html>");
    private final JLabel flowStatus = new JLabel("Paso 1: consultar B1.1 (antes de refrescar).");
    private String lastConfirmedB12Key = "";
    private String lastConfirmedB12At = "";
    private final JLabel controlNote = new JLabel("SIN PENDIENTES LOCALES no sustituye B1.2. Se cuentan solo los ultimos 60 dias; el refresco consulta la API.");
    private final DefaultTableModel b21Model = new DefaultTableModel(new Object[] {
            "TC", "Liga", "Sin enlace", "Listos", "Revision", "Sin candidato", "Siguiente accion"
    }, 0) {
        private static final long serialVersionUID = 1L;
        @Override public boolean isCellEditable(int row, int col) { return false; }
    };
    private final JTable b21Table = new JTable(b21Model);
    private final DefaultTableModel b21DetailModel = new DefaultTableModel(new Object[] {
            "Liga", "ls_match_id", "tc_match_pk", "TC API ID", "Footiqo (Madrid)",
            "TotalCorner (Madrid)", "Diferencia (seg)", "Estado"
    }, 0) {
        private static final long serialVersionUID = 1L;
        @Override public boolean isCellEditable(int row, int col) { return false; }
    };
    private final JTable b21DetailTable = new JTable(b21DetailModel);
    private final JButton gotoB21 = new JButton("3. Ir a B2.1");
    private final JButton b21Button = new JButton("Consultar candidatos B2.1 (solo lectura)");
    private final JSpinner b21Tolerance = new JSpinner(new SpinnerNumberModel(5, 0, 14400, 1));
    private final JLabel b21Guidance = new JLabel("<html>Despues de B1.2 y repetir B1.1: ejecuta este control de enlaces. No modifica MySQL.</html>");
    private final JSpinner b22Tolerance = new JSpinner(new SpinnerNumberModel(5, 0, 14400, 1));
    private final JButton b22Simulate = new JButton("1. Simular B2.2 (sin escrituras)");
    private final JButton b22Apply = new JButton("2. Aplicar enlaces seleccionados");
    private final JButton gotoB22 = new JButton("Ir a B2.2");
    private final JLabel b22Guidance = new JLabel("Simula B2.2; puedes seleccionar manualmente cualquier estado. Los enlaces repetidos o contradictorios se rechazan.");
    private final DefaultTableModel b22Model = new DefaultTableModel(new Object[] {
            "Aplicar", "Estado", "Liga", "ls_match_id", "tc_match_pk", "Footiqo", "TotalCorner",
            "Fecha Footiqo", "Fecha TotalCorner", "Dif. minutos", "Marcadores"
    }, 0) {
        private static final long serialVersionUID = 1L;
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int row, int column) {
            // Estado informativo: cualquier pareja mostrada puede marcarse tras revision.
            return column == 0 && !busy;
        }
    };
    private final JTable b22Table = new JTable(b22Model);
    private B22Response b22Preview;
    // B2.3: informe amplio, sin modificar datos permanentes.
    private final JComboBox<String> b23Period = new JComboBox<String>(new String[]{"60 dias", "Historico desde 2015"});
    private final JSpinner b23Margin = new JSpinner(new SpinnerNumberModel(48, 1, 240, 1));
    private final JCheckBox b23IncludeTc = new JCheckBox("Incluir SOLO_TC", true);
    private final JButton b23Query = new JButton("Consultar informe B2.3 (solo lectura)");
    private final JButton b23Export = new JButton("Exportar CSV");
    private final JLabel b23Guidance = new JLabel("Consulta los pendientes historicos o recientes; ninguna pareja se enlaza automaticamente.");
    private final DefaultTableModel b23SummaryModel = readonlyModel(new String[]{"LS liga", "TC", "Pais", "Liga", "LS sin verificar", "Con excepcion", "TC con goles sin verificar", "Candidatos", "LS sin candidato", "TC sin candidato"});
    private final JTable b23SummaryTable = new JTable(b23SummaryModel);
    private static final String[] B23_COLUMNS = {"Tipo", "Valoracion", "Pais", "Liga", "LS liga", "TC liga", "Temporada LS", "ls_match_id", "tc_match_pk", "TC API ID", "Fecha LS (Madrid)", "Fecha TC (Madrid)", "Dif. minutos", "LS local", "LS visitante", "Resultado LS", "TC local", "TC visitante", "Resultado TC", "Local VERIFIED", "Visitante VERIFIED", "Nombre local", "Nombre visitante", "Zona TC", "Estado TC", "Clasificacion TC", "Enlace LS", "Enlace TC", "Excepcion", "Notas excepcion"};
    private final DefaultTableModel b23DetailModel = readonlyModel(B23_COLUMNS);
    private final JTable b23DetailTable = new JTable(b23DetailModel);

    // B3: control, simulacion y descarga real del proyecto existente.
    private final JSpinner b3Newest = new JSpinner(new SpinnerNumberModel(2026, 2015, 2100, 1));
    private final JSpinner b3Oldest = new JSpinner(new SpinnerNumberModel(2015, 2015, 2100, 1));
    private final JSpinner b3Batch = new JSpinner(new SpinnerNumberModel(5, 1, 10, 1));
    private final JSpinner b3Requests = new JSpinner(new SpinnerNumberModel(1000, 1, 1000000, 1));
    private final JSpinner b3Hours = new JSpinner(new SpinnerNumberModel(12, 1, 72, 1));
    private final JButton b3Config = new JButton("1. Verificar configuracion (0 API / SQL)");
    private final JButton b3Control = new JButton("2. Consultar pendientes B3");
    private final JButton b3Diagnostic = new JButton("5. Diagnosticar diferencias B3 + CSV");
    private final JButton b3Simulate = new JButton("3. Simular B3");
    private final JButton b3Execute = new JButton("4. Descargar cuotas B3");
    private final JButton b3Stop = new JButton("Detener B3");
    private final JLabel b3Guidance = new JLabel("B3 utiliza LS finalizado + enlace VERIFIED; acepta TC sin marcador. Primero consulta y simula.");
    private final DefaultTableModel b3Model = readonlyModel(new String[]{"TC", "Liga", "Elegibles", "Dos jobs DONE", "Partidos pendientes", "Peticiones pendientes", "Dos JSON OK", "Ultimo pendiente UTC", "TC no final"});
    private final JTable b3Table = new JTable(b3Model);
    private final JTextArea b3Output = new JTextArea(12, 80);
    private String b3SimulatedKey = "";
    private String b3ControlSnapshotKey = "";
    private long b3ControlSnapshotPending = -1;
    private long b3ControlSnapshotMissingJson = -1;
    private final Map<String,long[]> b3ControlByLeague = new LinkedHashMap<String,long[]>();
    private long b3SimulatedPending = -1;
    // B4: control de cobertura, simulacion y procesamiento v2downloaded.
    private final JSpinner b4Limit = new JSpinner(new SpinnerNumberModel(5, 1, 500, 1));
    private final JSpinner b4Minutes = new JSpinner(new SpinnerNumberModel(15, 1, 240, 1));
    private final JButton b4Control = new JButton("1. Consultar estado B4");
    private final JButton b4Diagnose = new JButton("5. Diagnosticar pendientes + CSV");
    private final JButton b4Config = new JButton("2. Verificar configuracion B4");
    private final JButton b4Simulate = new JButton("3. Simular B4");
    private final JButton b4Execute = new JButton("4. Procesar cuotas B4");
    private final JButton b4Stop = new JButton("Detener B4");
    private final JLabel b4Guidance = new JLabel("Primero consulta cobertura; despues valida y simula. No llama a la API ni modifica ls_.");
    private final DefaultTableModel b4Model = readonlyModel(new String[]{"TC", "Liga", "En alcance", "Cobertura al dia", "Pendiente / desactualizada", "Ultimo pendiente", "Procesables B4", "Esperando JSON B3", "Bloqueados B4"});
    private final JTable b4Table = new JTable(b4Model);
    private final JTextArea b4Output = new JTextArea(13,80);
    private String b4SimulatedKey = "";
    private long b4Pending = -1L;
    private long b4Ready = -1L;
    private long b4WaitingB3 = -1L;
    private long b4Blocked = -1L;
    private String b4ControlKey = "";
    private static DefaultTableModel readonlyModel(String[] columns) {
        return new DefaultTableModel(columns, 0) { private static final long serialVersionUID=1L;
            @Override public boolean isCellEditable(int r,int c) { return false; } };
    }
    private final JTabbedPane tabs = new JTabbedPane(); // Pestañas B dentro del bloque 02, indices historicos intactos
    private final JTabbedPane sections = new JTabbedPane(JTabbedPane.TOP);
    private final WorkflowProgress progress = new WorkflowProgress(() -> runId.getText(), () -> chosenIds());
    private B5Panel b5Panel;
    private B6Panel b6Panel;
    private B7PublishPanel b7Panel;
    private NewLeaguePanel newLeaguePanel;
    private WorkflowDashboardPanel dashboardPanel;
    private volatile boolean busy;
    private final JLabel status = new JLabel("Configura las rutas y carga las ligas.");
    private final JButton check = new JButton("Simular / comprobar configuracion");
    private final JButton execute = new JButton("Ejecutar refresco B1.2");
    private final JButton stop = new JButton("Detener proceso");
    private volatile Process child;
    private volatile boolean cancelledByUser;

    public static void main(String[] args) {
        if (args.length > 0 && "--version".equals(args[0])) {
            System.out.println("TotalCorner Manager 1.8.8 - Flujo B1.1 > B1.2 > B2.1 > B2.2 > B2.3 > B3 > B4 > B5 > B6 > B7 - Java 8 compatible");
            return;
        }
        if (args.length > 0 && "--self-test-b7".equals(args[0])) {
            try { B7PublishPanel.selfTest(); } catch (Exception ex) { throw new IllegalStateException(ex); }
            return;
        }
        if (args.length > 0 && "--self-test-a1".equals(args[0])) { NewLeaguePanel.selfTest(); return; }
        if (args.length > 0 && "--self-test-a4".equals(args[0])) { A4AuditRunner.main(new String[]{"--self-test"}); return; }
        if (args.length > 0 && "--self-test-a51".equals(args[0])) { A51ExistingTeamsRunner.main(new String[]{"--self-test"}); return; }
        if (args.length > 0 && "--self-test-a52".equals(args[0])) { A52TeamCandidatesRunner.main(new String[]{"--self-test"}); return; }
        if (args.length > 0 && "--self-test-a53".equals(args[0])) { A53TeamLinksRunner.main(new String[]{"--self-test"}); return; }
        if (args.length > 0 && "--self-test-a61".equals(args[0])) { A61MatchPreviewRunner.main(new String[]{"--self-test"}); return; }
        if (args.length > 0 && "--self-test-progress".equals(args[0])) {
            try { WorkflowProgress.selfTest(); } catch(Exception ex) { throw new IllegalStateException(ex); }
            return;
        }
        if (args.length > 0 && "--self-test-flow".equals(args[0])) {
            testGuidedFlow();
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() { new TotalCornerManager().setVisible(true); }
        });
    }

    private TotalCornerManager() {
        super("Footiqo - TotalCorner Manager | Bloque 01 / Bloque 02 / Panel general");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(1190, 870);
        setMinimumSize(new Dimension(900, 650));
        setLocationRelativeTo(null);
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        setContentPane(root);
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        root.add(top, BorderLayout.NORTH);
        JLabel heading = new JLabel("TotalCorner Manager · Procesos de TotalCorner");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(heading);
        JLabel sub = new JLabel("01 Incorporar liga nueva  |  02 Actualizar ligas existentes  |  Panel de seguimiento por liga y ciclo");
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(sub);
        top.add(Box.createVerticalStrut(6));
        JPanel pathsGroup=new JPanel();pathsGroup.setLayout(new BoxLayout(pathsGroup,BoxLayout.Y_AXIS));
        pathsGroup.setAlignmentX(Component.LEFT_ALIGNMENT);
        pathsGroup.add(fileRow("Proyecto Eclipse", project, true));
        pathsGroup.add(fileRow("Java 8 (java.exe)", java8, false));
        JPanel configRow=fileRow("Config. ligas", config, false);
        pathsGroup.add(configRow);
        top.add(pathsGroup);

        // La seleccion multiliga solo pertenece al Bloque 02.
        JPanel block2Inputs = new JPanel();
        block2Inputs.setLayout(new BoxLayout(block2Inputs, BoxLayout.Y_AXIS));
        block2Inputs.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel leagueHeader = new JPanel(new BorderLayout());
        leagueHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        leagueHeader.add(new JLabel("Ligas para este ciclo (seleccion multiple):"), BorderLayout.WEST);
        JButton load = new JButton("Cargar ligas del properties");
        load.addActionListener(e -> loadLeagues(true));
        leagueHeader.add(load, BorderLayout.EAST);
        block2Inputs.add(Box.createVerticalStrut(5));
        block2Inputs.add(leagueHeader);
        leagues.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        leagues.setVisibleRowCount(5);
        JScrollPane leagueScroll = new JScrollPane(leagues);
        leagueScroll.setPreferredSize(new Dimension(740, 120));
        leagueScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        block2Inputs.add(leagueScroll);
        top.add(block2Inputs);
        leagues.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && (controlModel.getRowCount() > 0 || b21Model.getRowCount() > 0)) {
                guidance.setText("<html><b>Seleccion cambiada:</b> vuelve a consultar B1.1 para actualizar las recomendaciones.</html>");
                flowStatus.setText("Paso 1: comprobar seleccion y consultar B1.1.");
                b21Model.setRowCount(0);
                b21DetailModel.setRowCount(0);
                b21Guidance.setText("Seleccion cambiada. Repite B1.1 y despues B2.1.");
                invalidateB22();
                b23SummaryModel.setRowCount(0); b23DetailModel.setRowCount(0); b23Export.setEnabled(false);
                b3Model.setRowCount(0); b3SimulatedKey=""; b3ControlSnapshotKey="";
                b3ControlSnapshotPending=-1; b3ControlSnapshotMissingJson=-1; b3ControlByLeague.clear(); b3Execute.setEnabled(false);
                invalidateB4();
            }
        });

        JPanel guidePanel = new JPanel(new BorderLayout(7, 7));
        guidePanel.setBorder(new EmptyBorder(12, 4, 6, 4));
        JPanel guideIntro = new JPanel();
        guideIntro.setLayout(new BoxLayout(guideIntro, BoxLayout.Y_AXIS));
        flowStatus.setFont(flowStatus.getFont().deriveFont(Font.BOLD, 14f));
        flowStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        guideIntro.add(flowStatus);
        guideIntro.add(Box.createVerticalStrut(8));
        JLabel guideHeading = new JLabel("B1.1 - Control sencillo multiliga");
        guideHeading.setFont(guideHeading.getFont().deriveFont(Font.BOLD, 16f));
        guideHeading.setAlignmentX(Component.LEFT_ALIGNMENT);
        guideIntro.add(guideHeading);
        JLabel guideSubtitle = new JLabel("Lee MySQL sin modificar datos ni consumir API. Una fila por liga seleccionada.");
        guideSubtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        guideIntro.add(guideSubtitle);
        JPanel guideButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 7));
        guideButtons.setAlignmentX(Component.LEFT_ALIGNMENT);
        controlButton.addActionListener(e -> runControl());
        gotoRefresh.addActionListener(e -> tabs.setSelectedIndex(1));
        gotoB21.addActionListener(e -> tabs.setSelectedIndex(2));
        guideButtons.add(controlButton); guideButtons.add(gotoRefresh); guideButtons.add(gotoB21);
        guideIntro.add(guideButtons);
        guidePanel.add(guideIntro, BorderLayout.NORTH);
        controlTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        controlTable.setRowHeight(25);
        controlTable.getTableHeader().setReorderingAllowed(false);
        int[] widths = {42, 180, 154, 160, 160, 115, 120, 65, 195};
        for (int i = 0; i < widths.length; i++) controlTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        guidePanel.add(ManagerUiSupport.withTotals(controlTable), BorderLayout.CENTER);
        JPanel guideFoot = new JPanel();
        guideFoot.setLayout(new BoxLayout(guideFoot, BoxLayout.Y_AXIS));
        guidance.setAlignmentX(Component.LEFT_ALIGNMENT);
        controlNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        guideFoot.add(guidance);
        guideFoot.add(Box.createVerticalStrut(7));
        guideFoot.add(controlNote);
        guideFoot.add(Box.createVerticalStrut(15));
        JLabel roadmapLabel = new JLabel("Recorrido: B1 Calendario -> B2 Enlaces -> B3 Descargar cuotas -> B4 Procesar cuotas -> B5 ZIP -> B6 Coverage -> WinSCP");
        roadmapLabel.setFont(roadmapLabel.getFont().deriveFont(Font.BOLD, 12f));
        roadmapLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        guideFoot.add(roadmapLabel);
        JLabel pending = new JLabel("B1-B7 disponibles en el Bloque 02. El Panel general muestra evidencias reales por ciclo; sin ticks por simples simulaciones.");
        pending.setAlignmentX(Component.LEFT_ALIGNMENT);
        guideFoot.add(pending);
        guidePanel.add(guideFoot, BorderLayout.SOUTH);
        tabs.addTab("1. Asistente / Control B1.1", guidePanel);

        JPanel refreshPanel = new JPanel(new BorderLayout(5, 5));
        refreshPanel.setBorder(new EmptyBorder(9, 4, 6, 4));
        JPanel refreshTop = new JPanel();
        refreshTop.setLayout(new BoxLayout(refreshTop, BoxLayout.Y_AXIS));
        JLabel refreshHeading = new JLabel("B1.2 - Refresco incremental de calendarios y marcadores");
        refreshHeading.setFont(refreshHeading.getFont().deriveFont(Font.BOLD, 16f));
        refreshHeading.setAlignmentX(Component.LEFT_ALIGNMENT);
        refreshTop.add(refreshHeading);
        JLabel refresher = new JLabel("PASO 2 OBLIGATORIO CADA CICLO: ejecutar B1.2 aunque B1.1 tenga cero pendientes locales.");
        refresher.setAlignmentX(Component.LEFT_ALIGNMENT);
        refreshTop.add(refresher);
        JLabel caution = new JLabel("Mismo ID de ciclo = reutiliza paginas DONE. En otra jornada usa nuevo ID; si repites el dia, usa -r2.");
        caution.setAlignmentX(Component.LEFT_ALIGNMENT);
        refreshTop.add(caution);
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT));
        options.setAlignmentX(Component.LEFT_ALIGNMENT);
        options.add(new JLabel("Pagina inicial:")); options.add(fromPage);
        options.add(Box.createHorizontalStrut(12));
        options.add(new JLabel("Max. paginas por liga:")); options.add(maxPages);
        options.add(Box.createHorizontalStrut(12));
        options.add(new JLabel("ID del ciclo:")); options.add(runId);
        refreshTop.add(options);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        check.addActionListener(e -> launch(false));
        execute.addActionListener(e -> launch(true));
        stop.addActionListener(e -> stopChild());
        stop.setEnabled(false);
        actions.add(check); actions.add(execute); actions.add(stop);
        refreshTop.add(actions);
        refreshPanel.add(refreshTop, BorderLayout.NORTH);
        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        output.setLineWrap(false);
        refreshPanel.add(new JScrollPane(output), BorderLayout.CENTER);
        tabs.addTab("2. Ejecutar B1.2", refreshPanel);

        JPanel b21Panel = new JPanel(new BorderLayout(6, 6));
        b21Panel.setBorder(new EmptyBorder(8, 4, 6, 4));
        JPanel b21Top = new JPanel();
        b21Top.setLayout(new BoxLayout(b21Top, BoxLayout.Y_AXIS));
        JLabel b21Heading = new JLabel("B2.1 - Previsualizar enlaces de partidos (60 dias, sin escrituras)");
        b21Heading.setFont(b21Heading.getFont().deriveFont(Font.BOLD, 16f));
        b21Heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        b21Top.add(b21Heading);
        JLabel b21Info = new JLabel("Exige equipos VERIFIED, marcador igual y zona horaria PROFILE_VERIFIED. Tolerancia ampliada: solo revision.");
        b21Info.setAlignmentX(Component.LEFT_ALIGNMENT);
        b21Top.add(b21Info);
        JPanel b21Actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        b21Actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        b21Button.addActionListener(e -> runB21());
        JButton b21Back = new JButton("Volver a B1.1");
        b21Back.addActionListener(e -> tabs.setSelectedIndex(0));
        b21Actions.add(new JLabel("Tolerancia (minutos):"));
        b21Tolerance.setPreferredSize(new Dimension(82, 26));
        b21Actions.add(b21Tolerance);
        b21Actions.add(b21Button); b21Actions.add(b21Back);
        gotoB22.addActionListener(e -> tabs.setSelectedIndex(3));
        b21Actions.add(gotoB22);
        b21Top.add(b21Actions);
        b21Panel.add(b21Top, BorderLayout.NORTH);
        b21Table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        b21Table.setRowHeight(25);
        b21Table.getTableHeader().setReorderingAllowed(false);
        int[] b21Widths = {50, 206, 105, 82, 90, 114, 390};
        for (int i = 0; i < b21Widths.length; i++)
            b21Table.getColumnModel().getColumn(i).setPreferredWidth(b21Widths[i]);
        b21DetailTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        b21DetailTable.setRowHeight(23);
        b21DetailTable.getTableHeader().setReorderingAllowed(false);
        int[] b21DetailWidths = {190, 100, 100, 100, 160, 160, 110, 250};
        for (int i = 0; i < b21DetailWidths.length; i++)
            b21DetailTable.getColumnModel().getColumn(i).setPreferredWidth(b21DetailWidths[i]);
        JSplitPane b21Split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                ManagerUiSupport.withTotals(b21Table), ManagerUiSupport.withTotals(b21DetailTable));
        b21Split.setResizeWeight(0.45);
        b21Split.setDividerLocation(260);
        b21Panel.add(b21Split, BorderLayout.CENTER);
        b21Guidance.setBorder(new EmptyBorder(6, 0, 6, 0));
        b21Panel.add(b21Guidance, BorderLayout.SOUTH);
        tabs.addTab("3. B2.1 Enlaces (lectura)", b21Panel);
        JPanel b22Panel = new JPanel(new BorderLayout(6, 6));
        b22Panel.setBorder(new EmptyBorder(8, 4, 6, 4));
        JPanel b22Top = new JPanel();
        b22Top.setLayout(new BoxLayout(b22Top, BoxLayout.Y_AXIS));
        JLabel b22Heading = new JLabel("B2.2 - Simular y enlazar partidos nuevos (60 dias)");
        b22Heading.setFont(b22Heading.getFont().deriveFont(Font.BOLD, 16f));
        b22Heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        b22Top.add(b22Heading);
        JLabel b22Info = new JLabel("Todos los estados son seleccionables; solo LISTO se marca por defecto. Revisar antes de aplicar.");
        b22Info.setAlignmentX(Component.LEFT_ALIGNMENT);
        b22Top.add(b22Info);
        JPanel b22Actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        b22Actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        b22Actions.add(new JLabel("Tolerancia (minutos):"));
        b22Tolerance.setPreferredSize(new Dimension(95, 26));
        b22Actions.add(b22Tolerance);
        b22Simulate.addActionListener(e -> runB22(false));
        b22Apply.addActionListener(e -> runB22(true));
        b22Apply.setEnabled(false);
        b22Actions.add(b22Simulate);
        b22Actions.add(b22Apply);
        JButton backToB21 = new JButton("Volver a B2.1");
        backToB21.addActionListener(e -> tabs.setSelectedIndex(2));
        b22Actions.add(backToB21);
        b22Top.add(b22Actions);
        b22Panel.add(b22Top, BorderLayout.NORTH);
        b22Table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        b22Table.setRowHeight(25);
        b22Table.getTableHeader().setReorderingAllowed(false);
        int[] b22Widths = {65, 205, 180, 90, 95, 230, 230, 155, 155, 92, 140};
        for (int i = 0; i < b22Widths.length; i++) b22Table.getColumnModel().getColumn(i).setPreferredWidth(b22Widths[i]);
        b22Panel.add(ManagerUiSupport.withTotals(b22Table), BorderLayout.CENTER);
        b22Guidance.setBorder(new EmptyBorder(8, 0, 8, 0));
        b22Panel.add(b22Guidance, BorderLayout.SOUTH);
        tabs.addTab("4. B2.2 Enlazar", b22Panel);
        buildB23Tab();
        buildB3Tab();
        buildB4Tab();
        b5Panel = new B5Panel(() -> busy, v -> setBusy(v), () -> project.getText());
        tabs.addTab("8. B5 ZIP", b5Panel);
        b6Panel = new B6Panel(() -> b5Panel.publicationSettings(), () -> busy, v -> setBusy(v));
        tabs.addTab("9. B6 Coverage", b6Panel);
        b7Panel = new B7PublishPanel(() -> b5Panel.publicationSettings(), () -> busy, v -> setBusy(v));
        tabs.addTab("10. B7 Publicar", b7Panel);
        newLeaguePanel = new NewLeaguePanel(() -> project.getText(), () -> java8.getText(),
                () -> busy, v -> setBusy(v), () -> { if (dashboardPanel != null) dashboardPanel.refreshState(); });
        dashboardPanel = new WorkflowDashboardPanel(newLeaguePanel, progress,
                () -> isB12ConfirmedForCurrentCycle(), () -> lastConfirmedB12At,
                i -> {
                    if(i==10){sections.setSelectedComponent(newLeaguePanel);}
                    else {sections.setSelectedComponent(tabs);tabs.setSelectedIndex(i);}
                });
        b5Panel.setOnCompletion(message -> { if (message.startsWith("CONFIRMADO B5 PACKAGER"))markProgress("B5",WorkflowProgress.DONE,"Exporter/Packager y ZIP verificados"); });
        b6Panel.setOnCompletion(message -> { if (message.startsWith("B6 COMPLETADO"))markProgress("B6",WorkflowProgress.DONE,"Excel/CSV revisados y version archivada"); });
        b7Panel.setOnCompletion(message -> {
            if(message.startsWith("B7 VERIFICADO"))markProgress("B7",WorkflowProgress.REVIEW,"SHA-256 remoto correcto; falta revisar la descarga web");
            else if(message.startsWith("B7 WEB CONFIRMADA"))markProgress("B7",WorkflowProgress.DONE,"Control remoto y descarga web comprobados manualmente");
        });
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        sections.addTab("01 · Incorporar liga nueva",newLeaguePanel);
        sections.addTab("02 · Actualizar ligas",tabs);
        sections.addTab("Panel general · Estado",dashboardPanel);
        sections.addChangeListener(e -> {
            block2Inputs.setVisible(sections.getSelectedComponent()==tabs);
            pathsGroup.setVisible(sections.getSelectedComponent()!=dashboardPanel);
            configRow.setVisible(sections.getSelectedComponent()==tabs);
            if(sections.getSelectedComponent()==dashboardPanel)dashboardPanel.refreshState();
            top.revalidate();top.repaint();
        });
        root.add(sections,BorderLayout.CENTER);
        root.add(status, BorderLayout.SOUTH);

        loadSettings();
        String todayMadrid = LocalDate.now(ZoneId.of("Europe/Madrid")).toString();
        String storedRun = runId.getText().trim();
        if (storedRun.isEmpty() || (storedRun.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                && storedRun.compareTo(todayMadrid) < 0)) runId.setText(todayMadrid);
        if (java8.getText().trim().isEmpty()) {
            Path java = Paths.get(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
            java8.setText(java.toString());
        }
        if (!config.getText().trim().isEmpty() && Files.isRegularFile(Paths.get(config.getText().trim())))
            loadLeagues(false);
        sections.setSelectedComponent(dashboardPanel);
        block2Inputs.setVisible(false);
        pathsGroup.setVisible(false);
        dashboardPanel.refreshState();
    }

    private void markProgress(String stage,String state,String evidence) {
        progress.mark(stage,state,evidence);
        if(dashboardPanel!=null)dashboardPanel.refreshState();
    }

    private JPanel fileRow(String label, final JTextField field, final boolean directory) {
        JPanel panel = new JPanel(new BorderLayout(6, 1));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(155, 24));
        panel.add(l, BorderLayout.WEST);
        panel.add(field, BorderLayout.CENTER);
        JButton browse = new JButton("Examinar...");
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(directory ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
            String existing = field.getText().trim();
            if (!existing.isEmpty()) {
                File near = new File(existing);
                if (near.exists()) chooser.setSelectedFile(near);
                else if (near.getParentFile() != null && near.getParentFile().isDirectory())
                    chooser.setCurrentDirectory(near.getParentFile());
            }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                field.setText(chooser.getSelectedFile().getAbsolutePath());
                if (directory) {
                    Path suggested = chooser.getSelectedFile().toPath().resolve("config/totalcorner-history.properties");
                    if (Files.isRegularFile(suggested)) config.setText(suggested.toString());
                }
                if (directory || field == config) loadLeagues(false);
            }
        });
        panel.add(browse, BorderLayout.EAST);
        return panel;
    }

    private static final class League {
        String id;
        String name;
        int priority;
        @Override public String toString() { return "TC " + id + "  -  " + name + "  (prioridad " + priority + ")"; }
    }

    private void loadLeagues(boolean alert) {
        if (busy) {
            if (alert) JOptionPane.showMessageDialog(this, "Termina el proceso actual antes de cambiar las ligas.");
            return;
        }
        leagueModel.clear();
        controlModel.setRowCount(0);
        b21Model.setRowCount(0);
        b21DetailModel.setRowCount(0);
        guidance.setText("<html><b>Paso 1:</b> consultar B1.1. Aunque no existan pendientes, despues toca B1.2.</html>");
        flowStatus.setText("Paso 1: consultar B1.1 antes de refrescar.");
        try {
            Path path = Paths.get(config.getText().trim());
            if (!Files.isRegularFile(path)) throw new IOException("El properties no existe: " + path);
            Properties p = new Properties();
            try (InputStream is = Files.newInputStream(path)) { p.load(is); }
            String ids = p.getProperty("league.ids", "").trim();
            if (ids.isEmpty()) throw new IOException("No se encontro league.ids");
            HashSet<String> seen = new HashSet<String>();
            List<League> items = new ArrayList<League>();
            for (String raw : ids.split(",")) {
                String id = raw.trim();
                if (!id.matches("[0-9]{1,30}") || !seen.add(id))
                    throw new IOException("ID invalido o duplicado: " + id);
                League league = new League();
                league.id = id;
                league.name = p.getProperty("league." + id + ".name", "Liga " + id);
                league.priority = Integer.parseInt(p.getProperty("league." + id + ".priority", "100").trim());
                items.add(league);
            }
            Collections.sort(items, new Comparator<League>() {
                @Override public int compare(League a, League b) {
                    int r = Integer.compare(a.priority, b.priority);
                    return r != 0 ? r : a.id.compareTo(b.id);
                }
            });
            for (League league : items) leagueModel.addElement(league);
            if (!items.isEmpty()) leagues.setSelectionInterval(0, items.size() - 1);
            status.setText("Cargadas " + items.size() + " ligas del fichero de configuracion.");
        } catch (Exception e) {
            status.setText("No se pudieron cargar las ligas: " + e.getMessage());
            if (alert) JOptionPane.showMessageDialog(this, e.getMessage(), "Configuracion de ligas", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void launch(final boolean actual) {
        if (busy) { JOptionPane.showMessageDialog(this, "Ya hay un proceso en marcha."); return; }
        final List<League> selected = leagues.getSelectedValuesList();
        if (selected.isEmpty()) { JOptionPane.showMessageDialog(this, "Selecciona al menos una liga."); return; }
        final Path dir, javaPath, properties;
        final String cycle = runId.getText().trim();
        try {
            dir = Paths.get(project.getText().trim()).toAbsolutePath().normalize();
            javaPath = Paths.get(java8.getText().trim()).toAbsolutePath().normalize();
            properties = Paths.get(config.getText().trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve("pom.xml")))
                throw new IOException("Selecciona la carpeta raiz de totalcorner-downloader (contiene pom.xml).");
            if (!Files.isRegularFile(javaPath)) throw new IOException("No se encontro el ejecutable de Java 8.");
            verifyJava8(javaPath);
            if (!Files.isRegularFile(properties)) throw new IOException("No se encontro el properties de ligas.");
            if (!cycle.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}(-r[0-9]{1,3})?"))
                throw new IOException("Formato de ciclo: AAAA-MM-DD o AAAA-MM-DD-r2.");
            LocalDate.parse(cycle.substring(0, 10));
            String cp = buildClasspath(dir);
            List<String> args = new ArrayList<String>();
            args.add(javaPath.toString()); args.add("-cp"); args.add(cp); args.add(MAIN_CLASS);
            args.add("--config=" + properties);
            args.add("--from-page=" + fromPage.getValue());
            args.add("--max-pages=" + maxPages.getValue());
            args.add("--run-id=" + cycle);
            StringJoiner idJoiner = new StringJoiner(",");
            for (League l : selected) idJoiner.add(l.id);
            args.add("--league-ids=" + idJoiner.toString());
            args.add(actual ? "--execute" : "--check-config");
            if (actual) {
                boolean noToken = blank(System.getenv("TOTALCORNER_API_TOKEN"));
                boolean noPass = blank(System.getenv("TOTALCORNER_DB_PASSWORD"));
                if (noToken || noPass) throw new IOException("Faltan variables de entorno para esta aplicacion: "
                        + (noToken ? "TOTALCORNER_API_TOKEN " : "")
                        + (noPass ? "TOTALCORNER_DB_PASSWORD" : "")
                        + ". Si estaban definidas solo dentro de Eclipse, el gestor no las hereda.");
                int answer = JOptionPane.showConfirmDialog(this,
                        "Se van a consultar y actualizar datos en TotalCorner / MySQL.\n"
                        + "Ligas: " + selected.size() + " | Paginas maximas: " + maxPages.getValue()
                        + " por liga\nCiclo: " + cycle
                        + "\n\n¿Ejecutar con --execute?",
                        "Confirmar B1.2", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) return;
            }
            saveSettings();
            output.setText(actual ? "INICIO REFRESCO REAL B1.2\n" : "SIMULACION B1.2 (sin API ni MySQL)\n");
            status.setText(actual ? "Refresco en ejecucion..." : "Comprobando configuracion...");
            setBusy(true);
            cancelledByUser = false;
            runProcess(args, dir, actual, selected.size(), currentCycleKey());
        } catch (Exception err) {
            JOptionPane.showMessageDialog(this, err.getMessage(), "No se puede ejecutar", JOptionPane.ERROR_MESSAGE);
        }
    }

    static void verifyJava8(Path path) throws IOException, InterruptedException {
        Process check = new ProcessBuilder(path.toString(), "-version").redirectErrorStream(true).start();
        boolean exited = check.waitFor(8, TimeUnit.SECONDS);
        if (!exited) {
            check.destroyForcibly();
            throw new IOException("No se pudo verificar la version de Java en 8 segundos.");
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(check.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) result.append(line).append('\n');
        }
        Matcher version = Pattern.compile("(?:java|openjdk) version \"([0-9]+)(?:\\.([0-9]+))?").matcher(result);
        if (check.exitValue() != 0 || !version.find())
            throw new IOException("No se puede identificar Java 8 desde el ejecutable seleccionado.");
        int major = Integer.parseInt(version.group(1));
        if (major == 1 && version.group(2) != null) major = Integer.parseInt(version.group(2));
        if (major != 8)
            throw new IOException("El ejecutable seleccionado utiliza Java " + major + " y totalcorner-downloader debe ejecutarse con Java 8. Selecciona el java.exe de JDK/JRE 8.");
    }

    private static boolean blank(String x) { return x == null || x.trim().isEmpty(); }
    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

    static String buildClasspath(Path dir) throws IOException {
        Path classes = dir.resolve("target/classes");
        if (!Files.isDirectory(classes) || !Files.isRegularFile(classes.resolve("com/footiqo/totalcorner/LeagueScheduleRefreshRunner.class")))
            throw new IOException("Falta target/classes: compila primero totalcorner-downloader en Eclipse (Maven > Update Project, Project > Build Project).");
        Path cpFile = dir.resolve("target/classpath.txt");
        List<String> elements = new ArrayList<String>();
        elements.add(classes.toString());
        if (Files.isRegularFile(cpFile)) {
            String deps = new String(Files.readAllBytes(cpFile), StandardCharsets.UTF_8).trim();
            if (deps.isEmpty()) throw new IOException("target/classpath.txt esta vacio.");
            elements.add(deps);
        } else {
            // Plan B para el pom.xml exacto recibido, si Eclipse ya descargó Maven deps.
            Path repo = Paths.get(System.getProperty("user.home"), ".m2", "repository");
            String[] jars = {
                    "mysql/mysql-connector-java/8.0.29/mysql-connector-java-8.0.29.jar",
                    "com/fasterxml/jackson/core/jackson-databind/2.17.3/jackson-databind-2.17.3.jar",
                    "com/fasterxml/jackson/core/jackson-core/2.17.3/jackson-core-2.17.3.jar",
                    "com/fasterxml/jackson/core/jackson-annotations/2.17.3/jackson-annotations-2.17.3.jar"
            };
            for (String relative : jars) {
                Path f = repo.resolve(relative);
                if (!Files.isRegularFile(f)) {
                    throw new IOException("No se encontro " + f + ".\nEn Eclipse ejecuta Maven goal: "
                            + "dependency:build-classpath -Dmdep.outputFile=target/classpath.txt");
                }
                elements.add(f.toString());
            }
            Path proto = repo.resolve("com/google/protobuf/protobuf-java/3.19.4/protobuf-java-3.19.4.jar");
            if (Files.isRegularFile(proto)) elements.add(proto.toString());
        }
        StringJoiner joiner = new StringJoiner(File.pathSeparator);
        for (String s : elements) joiner.add(s);
        return joiner.toString();
    }

    private void runProcess(final List<String> cmd, final Path dir, final boolean actual,
                            final int expected, final String executedCycleKey) {
        SwingWorker<String, String> worker = new SwingWorker<String, String>() {
            @Override protected String doInBackground() {
                boolean finished = false;
                boolean sawError = false;
                boolean preview = false;
                int confirmed = -1;
                int planned = -1;
                int conflicts = 0;
                int uncertainHours = 0;
                try {
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.directory(dir.toFile());
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    child = p;
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            publish(line);
                            if (line.contains("SIMULACION: 0 peticiones API; 0 escrituras MySQL")) preview = true;
                            Matcher conflictMatcher = CONFLICTS.matcher(line);
                            if (conflictMatcher.find()) conflicts += Integer.parseInt(conflictMatcher.group(1));
                            Matcher dstMatcher = DST_UNCERTAIN.matcher(line);
                            if (dstMatcher.find()) uncertainHours += Integer.parseInt(dstMatcher.group(1));
                            if (line.startsWith("ERROR ") || line.startsWith("DETENIDO:") || line.startsWith("INTERRUMPIDO:")
                                    || line.startsWith("ERROR:") || line.startsWith("Exception in thread")) sawError = true;
                            Matcher match = SUMMARY.matcher(line);
                            if (line.startsWith("FIN B1.2") && match.find()) {
                                confirmed = Integer.parseInt(match.group(1));
                                planned = Integer.parseInt(match.group(2));
                                finished = true;
                            }
                        }
                    }
                    int exit = p.waitFor();
                    if (cancelledByUser) return "DETENIDO por el usuario. Revisar jobs y reanudar con el MISMO ciclo.";
                    if (exit != 0) return "FALLO: proceso Java salio con codigo " + exit;
                    if (sawError) return "REVISAR: el descargador notifico errores; no se considera completado.";
                    if (actual && (conflicts > 0 || uncertainHours > 0))
                        return "REVISAR B1.2: conflictos_marcador=" + conflicts
                                + ", horas DST sin UTC=" + uncertainHours
                                + ". Resolver incidencias antes de continuar el ciclo.";
                    if (actual && finished && confirmed == planned && planned == expected)
                        return "CONFIRMADO B1.2: " + confirmed + "/" + planned + " ligas verificadas. Revisar marcadores/conflictos antes de B2.";
                    if (!actual && preview) return "SIMULACION CORRECTA: cero solicitudes API, cero escrituras MySQL.";
                    return "SIN CONFIRMACION: la clase termino, pero no genero el resumen esperado. Revisar consola.";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "INTERRUMPIDO. Se conserva el ID de ciclo para reanudacion.";
                } catch (Exception e) {
                    return "ERROR DE EJECUCION: " + e.getClass().getSimpleName() + " (" + e.getMessage() + ")";
                } finally { child = null; }
            }
            @Override protected void process(List<String> chunks) {
                for (String line : chunks) output.append(line + "\n");
                output.setCaretPosition(output.getDocument().getLength());
            }
            @Override protected void done() {
                String result;
                try { result = get(); } catch (Exception e) { result = "ERROR: " + e.getMessage(); }
                output.append("\n== " + result + " ==\n");
                status.setText(result);
                setBusy(false);
                if (actual && result.startsWith("CONFIRMADO B1.2")) {
                    lastConfirmedB12Key = executedCycleKey;
                    lastConfirmedB12At = java.time.LocalDateTime.now(ZoneId.of("Europe/Madrid")).toString();
                    markProgress("B1.2",WorkflowProgress.DONE,"Refresco confirmado: "+result);
                    markProgress("B1.1",WorkflowProgress.REVIEW,"Repetir B1.1 despues de B1.2; consulta automatica pendiente");
                    saveSettings();
                    tabs.setSelectedIndex(0);
                    flowStatus.setText("Paso 3: volver a consultar B1.1 despues del refresco.");
                    guidance.setText("<html><b>B1.2 confirmado para las paginas seleccionadas.</b> "
                            + "Volviendo a consultar B1.1 automaticamente. No se considera revisado hasta ver el nuevo resultado.</html>");
                    runControl();
                } else if (actual) {
                    flowStatus.setText("Paso 2 pendiente: resolver B1.2 antes de continuar.");
                }
            }
        };
        worker.execute();
    }

    private void setBusy(boolean value) {
        busy = value;
        controlButton.setEnabled(!value);
        b21Button.setEnabled(!value);
        b21Tolerance.setEnabled(!value);
        b22Tolerance.setEnabled(!value);
        b22Simulate.setEnabled(!value);
        b22Apply.setEnabled(!value && b22Preview != null);
        b23Query.setEnabled(!value);
        b23Export.setEnabled(!value && b23DetailModel.getRowCount()>0);
        b3Config.setEnabled(!value);
        b3Control.setEnabled(!value);
        b3Diagnostic.setEnabled(!value);
        b3Simulate.setEnabled(!value);
        b3Execute.setEnabled(!value && !b3SimulatedKey.isEmpty()
                && b3SimulatedKey.equals(b3Key()) && b3ControlSnapshotKey.equals(b3Key())
                && b3SimulatedPending>0);
        b3Stop.setEnabled(value);
        b4Control.setEnabled(!value);
        b4Diagnose.setEnabled(!value);
        b4Config.setEnabled(!value);
        b4Simulate.setEnabled(!value);
        b4Execute.setEnabled(!value && !b4SimulatedKey.isEmpty()
                && b4SimulatedKey.equals(b4Key()) && b4Pending > 0
                && b4Ready>0 && b4ControlKey.equals(b4Key()));
        b4Stop.setEnabled(value);
        if (b5Panel != null) b5Panel.setExternalBusy(value);
        if (b6Panel != null) b6Panel.setExternalBusy(value);
        if (b7Panel != null) b7Panel.setExternalBusy(value);
        if (newLeaguePanel != null) newLeaguePanel.setExternalBusy(value);
        if (dashboardPanel != null) dashboardPanel.refreshState();
        String active="Hay otro proceso en curso.";
        ManagerUiSupport.gate(b22Apply,b22Apply.isEnabled(),value?active:
                b22Preview==null?"Falta 1. Simular B2.2 con la seleccion actual.":"Previsualizacion no validada; revisa B2.2.");
        ManagerUiSupport.gate(b3Execute,b3Execute.isEnabled(),value?active:
                b3ControlSnapshotKey.isEmpty()?"Falta 2. Consultar pendientes B3.":
                b3SimulatedKey.isEmpty()?"Falta 3. Simular B3 y comprobar los recuentos.":
                b3SimulatedPending<=0?"No hay trabajos pendientes elegibles para descargar.":
                "La seleccion o la configuracion ha cambiado. Vuelve a consultar y simular B3.");
        ManagerUiSupport.gate(b4Execute,b4Execute.isEnabled(),value?active:
                b4ControlKey.isEmpty()?"Falta 1. Consultar estado B4.":
                b4Ready<=0?"No hay partidos procesables: revisa el diagnostico y los JSON de B3.":
                b4SimulatedKey.isEmpty()?"Falta 3. Simular B4 con el lote actual.":
                "Cambio de configuracion: repite consulta y simulacion B4.");
        b4Limit.setEnabled(!value);
        b4Minutes.setEnabled(!value);
        check.setEnabled(!value);
        execute.setEnabled(!value);
        stop.setEnabled(value);
        leagues.setEnabled(!value);
        runId.setEditable(!value);
        fromPage.setEnabled(!value);
        maxPages.setEnabled(!value);
    }

    /** Ejecuta la consulta read-only en un Java externo para reutilizar el driver Maven original. */
    private void runControl() {
        if (busy) { JOptionPane.showMessageDialog(this, "Ya hay un proceso en marcha."); return; }
        final List<League> selected = leagues.getSelectedValuesList();
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Selecciona las ligas que quieres consultar.");
            return;
        }
        final Path dir;
        final List<String> command = new ArrayList<String>();
        try {
            dir = Paths.get(project.getText().trim()).toAbsolutePath().normalize();
            Path javaPath = Paths.get(java8.getText().trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve("pom.xml")))
                throw new IOException("Selecciona la carpeta raiz de totalcorner-downloader.");
            if (!Files.isRegularFile(javaPath)) throw new IOException("No se encontro Java 8.");
            verifyJava8(javaPath);
            if (blank(System.getenv("TOTALCORNER_DB_PASSWORD")))
                throw new IOException("Falta TOTALCORNER_DB_PASSWORD en el entorno de TotalCorner Manager.");
            // Reutiliza la misma conexion/driver que B1.2; ninguna dependencia nueva.
            String cp = managerCodeLocation() + File.pathSeparator + buildClasspath(dir);
            command.add(javaPath.toString());
            command.add("-Dfile.encoding=UTF-8");
            command.add("-cp"); command.add(cp);
            command.add(CONTROL_MAIN);
            StringJoiner chosen = new StringJoiner(",");
            for (League league : selected) chosen.add(league.id);
            command.add("--league-ids=" + chosen.toString());
            saveSettings();
            controlModel.setRowCount(0);
            guidance.setText("<html><b>Consultando B1.1...</b> Solo lectura; no se llama a la API.</html>");
            status.setText("Consultando estado de " + selected.size() + " ligas (MySQL solo lectura)...");
            setBusy(true);
            cancelledByUser = false;
        } catch (Exception err) {
            JOptionPane.showMessageDialog(this, err.getMessage(), "B1.1 - no se puede consultar", JOptionPane.ERROR_MESSAGE);
            return;
        }
        SwingWorker<ControlResponse, Void> worker = new SwingWorker<ControlResponse, Void>() {
            @Override protected ControlResponse doInBackground() {
                ControlResponse resp = new ControlResponse();
                try {
                    ProcessBuilder pb = new ProcessBuilder(command);
                    pb.directory(dir.toFile());
                    pb.redirectErrorStream(true);
                    pb.environment().remove("TOTALCORNER_API_TOKEN"); // No requiere API
                    Process p = pb.start();
                    child = p;
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("B11_ROW\t")) {
                                String[] fields = line.split("\t", -1);
                                if (fields.length == 10) resp.rows.add(Arrays.copyOfRange(fields, 1, 10));
                                else resp.error = "Respuesta B1.1 no reconocida (numero de columnas).";
                            } else if (line.startsWith("B11_DONE\t")) {
                                String[] fields = line.split("\t", -1);
                                if (fields.length >= 3) {
                                    resp.received = Integer.parseInt(fields[1]);
                                    resp.expected = Integer.parseInt(fields[2]);
                                }
                            } else if (line.startsWith("B11_ERROR\t")) {
                                resp.error = line.substring("B11_ERROR\t".length());
                            } else if (line.startsWith("Exception") || line.startsWith("Error")) {
                                resp.error = line;
                            }
                        }
                    }
                    int exit = p.waitFor();
                    if (cancelledByUser) resp.error = "Consulta interrumpida por el usuario.";
                    else if (exit != 0 && resp.error == null)
                        resp.error = "El proceso de consulta termino con codigo " + exit;
                    else if (resp.error == null && (resp.received != selected.size()
                            || resp.expected != selected.size() || resp.rows.size() != selected.size()))
                        resp.error = "Se solicitaron " + selected.size() + " ligas, pero MySQL devolvio "
                                + resp.rows.size() + ". Comprueba tc_league.enabled y los IDs; no se puede dar el control por valido.";
                } catch (Exception e) {
                    resp.error = "Error B1.1: " + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : " - " + e.getMessage());
                } finally { child = null; }
                return resp;
            }
            @Override protected void done() {
                ControlResponse resp;
                try { resp = get(); }
                catch (Exception e) { resp = new ControlResponse(); resp.error = "Fallo al recuperar los datos B1.1."; }
                controlModel.setRowCount(0);
                if (resp.error != null) {
                    guidance.setText("<html><b>B1.1 sin confirmar:</b> " + htmlEscape(resp.error)
                            + ". No se ha modificado MySQL.</html>");
                    status.setText("B1.1 pendiente: " + resp.error);
                    flowStatus.setText("PASO PENDIENTE: no se pudo completar la consulta B1.1.");
                    JOptionPane.showMessageDialog(TotalCornerManager.this, resp.error,
                            "B1.1 - revisar consulta", JOptionPane.WARNING_MESSAGE);
                } else {
                    for (String[] row : resp.rows) controlModel.addRow(row);
                    boolean refreshedThisCycle = isB12ConfirmedForCurrentCycle();
                    guidance.setText("<html>" + htmlEscape(guidedNextStep(resp.rows, refreshedThisCycle)) + "</html>");
                    flowStatus.setText(refreshedThisCycle
                            ? "Paso 3: B1.2 confirmado y B1.1 revisado. Continuar con B2.1 (pestana 3)."
                            : "Paso 2: refrescar B1.2 (aunque no haya pendientes locales).");
                    status.setText("B1.1 completado: " + resp.rows.size()
                            + " ligas consultadas, 0 escrituras, 0 peticiones API.");
                    markProgress("B1.1",WorkflowProgress.DONE,refreshedThisCycle?
                        "Control revisado despues de B1.2, ligas="+resp.rows.size():
                        "Control inicial, ligas="+resp.rows.size()+"; falta B1.2");
                }
                setBusy(false);
            }
        };
        worker.execute();
    }

    /** B2.1 lee los candidatos actuales sin modificar tablas permanentes. */
    private void runB21() {
        if (busy) { JOptionPane.showMessageDialog(this, "Hay un proceso activo."); return; }
        final List<League> selected = leagues.getSelectedValuesList();
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Selecciona las ligas que vas a comprobar."); return;
        }
        final int tolerance = ((Number) b21Tolerance.getValue()).intValue();
        if (tolerance > 5) {
            int option = JOptionPane.showConfirmDialog(this,
                    "Tolerancia ampliada: +/-" + tolerance + " minutos.\n"
                    + "Sirve para encontrar discrepancias horarias, NO para autorizar enlaces automaticos.\n"
                    + "Los candidatos a mas de 5 minutos se marcaran para revision. ¿Continuar?",
                    "B2.1: tolerancia ampliada", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (option != JOptionPane.YES_OPTION) return;
        }
        final boolean b12Confirmed = isB12ConfirmedForCurrentCycle();
        if (!b12Confirmed) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "B1.2 no consta confirmado para el ciclo/ligas/paginas actuales.\n"
                    + "Puedes consultar B2.1 para diagnostico, pero primero se debe completar B1.2.\n"
                    + "¿Continuar con la consulta de solo lectura?",
                    "B2.1: control previo", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return;
        }
        final Path dir;
        final List<String> command = new ArrayList<String>();
        try {
            dir = Paths.get(project.getText().trim()).toAbsolutePath().normalize();
            Path javaPath = Paths.get(java8.getText().trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve("pom.xml")))
                throw new IOException("Selecciona la carpeta totalcorner-downloader con pom.xml.");
            if (!Files.isRegularFile(javaPath)) throw new IOException("Java 8 no encontrado.");
            verifyJava8(javaPath);
            if (blank(System.getenv("TOTALCORNER_DB_PASSWORD")))
                throw new IOException("Falta TOTALCORNER_DB_PASSWORD para consultar MySQL.");
            String cp = managerCodeLocation() + File.pathSeparator + buildClasspath(dir);
            command.add(javaPath.toString()); command.add("-Dfile.encoding=UTF-8");
            command.add("-cp"); command.add(cp); command.add(B21_MAIN);
            StringJoiner chosen = new StringJoiner(",");
            for (League league : selected) chosen.add(league.id);
            command.add("--league-ids=" + chosen.toString());
            command.add("--tolerance-minutes=" + tolerance);
            saveSettings();
            b21Model.setRowCount(0); b21DetailModel.setRowCount(0);
            b21Guidance.setText("Consultando B2.1 (+/-" + tolerance + " minutos) en MySQL... sin API ni escrituras.");
            status.setText("B2.1: comprobando " + selected.size() + " ligas con +/-" + tolerance + " min (solo lectura)...");
            setBusy(true); cancelledByUser = false;
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "B2.1 - no se puede consultar", JOptionPane.ERROR_MESSAGE);
            return;
        }
        SwingWorker<B21Response, Void> worker = new SwingWorker<B21Response, Void>() {
            @Override protected B21Response doInBackground() {
                B21Response r = new B21Response();
                try {
                    ProcessBuilder pb = new ProcessBuilder(command);
                    pb.directory(dir.toFile()); pb.redirectErrorStream(true);
                    pb.environment().remove("TOTALCORNER_API_TOKEN");
                    Process proc = pb.start(); child = proc;
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("B21_ROW\t")) {
                                String[] parts = line.split("\t", -1);
                                if (parts.length == 7) r.rows.add(Arrays.copyOfRange(parts, 1, 7));
                                else r.error = "Formato de fila B2.1 no reconocido.";
                            } else if (line.startsWith("B21_DETAIL\t")) {
                                String[] parts = line.split("\t", -1);
                                if (parts.length == 9) r.details.add(Arrays.copyOfRange(parts, 1, 9));
                                else r.error = "Formato de detalle B2.1 no reconocido.";
                            } else if (line.startsWith("B21_DONE\t")) {
                                String[] parts = line.split("\t", -1);
                                if (parts.length >= 10) {
                                    if (Integer.parseInt(parts[9]) != tolerance)
                                        r.error = "La tolerancia aplicada no coincide con la solicitada.";
                                    r.reportedRows = Integer.parseInt(parts[1]);
                                    r.reportedLeagues = Integer.parseInt(parts[2]);
                                    r.safe = Integer.parseInt(parts[3]);
                                    r.review = Integer.parseInt(parts[4]);
                                    r.unmatched = Integer.parseInt(parts[5]);
                                    r.reportedDetails = Integer.parseInt(parts[6]);
                                } else r.error = "Resumen B2.1 incompleto.";
                            } else if (line.startsWith("B21_ERROR\t")) r.error = line.substring(10);
                            else if (line.startsWith("Exception") || line.startsWith("Error")) r.error = line;
                        }
                    }
                    int exit = proc.waitFor();
                    if (cancelledByUser) r.error = "Consulta B2.1 interrumpida.";
                    else if (exit != 0 && r.error == null) r.error = "Java B2.1 termino con codigo " + exit;
                    else if (r.error == null && (r.reportedRows != selected.size()
                            || r.reportedLeagues != selected.size() || r.rows.size() != selected.size()
                            || r.reportedDetails != r.details.size())) {
                        r.error = "B2.1 no devolvio todos los datos solicitados. No se valida el control.";
                    }
                } catch (Exception ex) {
                    r.error = "Error B2.1: " + ex.getClass().getSimpleName()
                            + (ex.getMessage() == null ? "" : " - " + ex.getMessage());
                } finally { child = null; }
                return r;
            }
            @Override protected void done() {
                B21Response r;
                try { r = get(); }
                catch (Exception ex) { r = new B21Response(); r.error = "No se pudo recuperar la consulta B2.1."; }
                b21Model.setRowCount(0); b21DetailModel.setRowCount(0);
                if (r.error != null) {
                    b21Guidance.setText("<html><b>B2.1 sin confirmar:</b> " + htmlEscape(r.error) + "</html>");
                    status.setText("B2.1 no validado: " + r.error);
                    JOptionPane.showMessageDialog(TotalCornerManager.this, r.error,
                            "B2.1 - revisar", JOptionPane.WARNING_MESSAGE);
                } else {
                    int pendingTotal = 0;
                    for (String[] row : r.rows) {
                        int pending = safeCount(row[2]);
                        int safe = safeCount(row[3]);
                        int review = safeCount(row[4]);
                        int unmatched = safeCount(row[5]);
                        pendingTotal += pending;
                        String action = pending == 0 ? "Sin pendientes (60d)"
                                : review > 0 ? "Revisar ambiguedad/incidencia"
                                : unmatched > 0 ? "Revisar sin candidato"
                                : safe > 0 ? "Simular B2.2 (pestana 4)" : "Revisar";
                        b21Model.addRow(new Object[]{ row[0], row[1], row[2], row[3], row[4], row[5], action });
                    }
                    for (String[] detail : r.details) b21DetailModel.addRow(detail);
                    String next;
                    if (r.safe > 0) {
                        next = "B2.1 confirmado: " + r.safe + " candidatos seguros. Siguiente: B2.2, primero simular "
                                + "y confirmar el numero ACTUAL antes de aplicar (pestana 4).";
                    } else if (pendingTotal > 0) {
                        next = "B2.1 confirmado: no hay enlaces automaticos seguros. "
                                + "Revisar B2.3 y casos ambiguos/sin candidato; no enlazar a ciegas.";
                    } else {
                        next = "B2.1 confirmado: cero partidos recientes pendientes. B2.2 no es necesario ahora. "
                                + "Siguiente: control historico B2.3 (pestana 5) y despues B3 cuotas (pestana 6).";
                    }
                    if (r.review > 0 || r.unmatched > 0)
                        next += " Pendientes de revision: " + r.review + " con candidatos no seguros; "
                                + r.unmatched + " sin candidato a +/-" + tolerance + " min.";
                    if (!b12Confirmed)
                        next = "AVISO: falta confirmar B1.2 del ciclo actual. " + next;
                    b21Guidance.setText("<html><b>" + htmlEscape(next) + "</b></html>");
                    markProgress("B2.1",!b12Confirmed?WorkflowProgress.REVIEW:pendingTotal==0?WorkflowProgress.SKIP:
                        (r.review>0||r.unmatched>0?WorkflowProgress.REVIEW:WorkflowProgress.DONE),
                        "Consulta: pendientes="+pendingTotal+", seguros="+r.safe+", revision="+r.review+", sin candidato="+r.unmatched+
                            (!b12Confirmed?"; falta B1.2 de este ciclo":""));
                    if(!b12Confirmed)markProgress("B2.2",WorkflowProgress.REVIEW,"Antes confirmar B1.2 del ciclo actual");
                    else if(pendingTotal==0 && !WorkflowProgress.DONE.equals(progress.state("B2.2")))
                        markProgress("B2.2",WorkflowProgress.SKIP,"B2.1 sin pendientes; no hace falta enlazar");
                    else markProgress("B2.2",WorkflowProgress.REVIEW,"Pendientes detectados en B2.1; simular antes de aplicar");
                    status.setText("B2.1: " + r.rows.size() + " ligas; " + r.safe + " listos; "
                            + r.review + " en revision; " + r.unmatched + " sin candidato. Sin escrituras.");
                }
                setBusy(false);
            }
        };
        worker.execute();
    }

    private void invalidateB22() {
        b22Preview = null;
        b22Model.setRowCount(0);
        b22Apply.setEnabled(false);
        b22Guidance.setText("La seleccion de ligas ha cambiado. Repite la simulacion B2.2.");
    }

    private static final class B22Response {
        final List<String[]> rows = new ArrayList<String[]>();
        String error, hash, desde, hasta, leagues;
        int count = -1, safe = -1, extended = -1, missingTc = -1, tolerance = -1;
        int applied = -1, appliedExtended = -1, appliedMissingTc = -1, appliedManual = -1;
    }

    /** Recalcula los enlaces en MySQL, sin escrituras en la simulacion.
     * La aplicacion vuelve a validar la misma propuesta dentro de la transaccion.
     */
    private void runB22(final boolean apply) {
        if (busy) { JOptionPane.showMessageDialog(this, "Hay un proceso activo."); return; }
        final List<League> selectedLeagues = leagues.getSelectedValuesList();
        if (selectedLeagues.isEmpty()) { JOptionPane.showMessageDialog(this, "Selecciona alguna liga."); return; }
        StringJoiner joiner = new StringJoiner(",");
        for (League lg : selectedLeagues) joiner.add(lg.id);
        final String ids = joiner.toString();
        final int tolerance = ((Number) b22Tolerance.getValue()).intValue();
        final B22Response snapshot = b22Preview;
        final List<String> selectedKeys = new ArrayList<String>();
        int extendedSelected = 0;
        int missingTcSelected = 0;
        int manualSelected = 0;
        final Set<String> seenLs = new HashSet<String>();
        final Set<String> seenTc = new HashSet<String>();
        if (apply) {
            if (snapshot == null || !ids.equals(snapshot.leagues) || snapshot.tolerance != tolerance) {
                JOptionPane.showMessageDialog(this, "La seleccion de ligas o tolerancia ha cambiado. Simula B2.2 de nuevo."); return;
            }
            for (int i = 0; i < b22Model.getRowCount(); i++) {
                if (Boolean.TRUE.equals(b22Model.getValueAt(i, 0))) {
                    String state = String.valueOf(b22Model.getValueAt(i, 1));
                    String ls = String.valueOf(b22Model.getValueAt(i, 3));
                    String tc = String.valueOf(b22Model.getValueAt(i, 4));
                    if (!seenLs.add(ls) || !seenTc.add(tc)) {
                        JOptionPane.showMessageDialog(this,
                                "Has seleccionado dos alternativas para un mismo partido LS/TC.\n"+
                                "Deja una sola correspondencia para cada partido antes de aplicar.",
                                "B2.2 - seleccion ambigua", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    selectedKeys.add(ls + ":" + tc);
                    if (!"LISTO_PARA_ENLAZAR".equals(state)) manualSelected++;
                    if ("REVISAR_TOLERANCIA_AMPLIADA".equals(state)) extendedSelected++;
                    if ("TC_SIN_MARCADOR".equals(state)) missingTcSelected++;
                }
            }
            if (selectedKeys.isEmpty()) { JOptionPane.showMessageDialog(this, "No hay enlaces seleccionados para aplicar."); return; }
            if (selectedKeys.size() > 1000) { JOptionPane.showMessageDialog(this, "Maximo 1000 enlaces por ejecucion."); return; }
            int answer = JOptionPane.showConfirmDialog(this,
                    "Se insertaran " + selectedKeys.size() + " enlaces VERIFIED en tc_match_link.\n" +
                    "Automaticos: " + (selectedKeys.size()-manualSelected) + ", aprobados manualmente: " + manualSelected + ".\n" +
                    "Hay " + extendedSelected + " con tolerancia ampliada y " + missingTcSelected + " sin marcador TC.\n" +
                    "Confirma que revisaste equipos, fechas y marcadores, y tienes copia de seguridad.\n" +
                    "La aplicacion recalculara y cancelara si hay cambios. ¿Continuar?",
                    "B2.2 - confirmar escritura", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return;
            String typed = JOptionPane.showInputDialog(this,
                    "Escribe el numero exacto de enlaces que apruebas: " + selectedKeys.size(), "Confirmar B2.2", JOptionPane.WARNING_MESSAGE);
            if (typed == null || !typed.trim().equals(String.valueOf(selectedKeys.size()))) return;
            if (manualSelected > 0) {
                String confirm = JOptionPane.showInputDialog(this,
                        "Has seleccionado " + manualSelected + " parejas NO consideradas seguras por la simulacion.\n" +
                        "Pueden existir diferencias de marcador, fecha, zona horaria o candidatos ambiguos.\n" +
                        "Confirma que has revisado CADA pareja; el resultado de LS/TC no se modificara.\n" +
                        "Escribe: REVISADOS " + manualSelected,
                        "Confirmar enlaces manuales", JOptionPane.WARNING_MESSAGE);
                if (confirm == null || !confirm.trim().equals("REVISADOS " + manualSelected)) return;
            }
        } else {
            if (!isB12ConfirmedForCurrentCycle()) {
                int answer=JOptionPane.showConfirmDialog(this,
                        "B1.2 no consta confirmado para este ciclo. ¿Simular B2.2 de todas formas?",
                        "Control previo", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) return;
            }
        }
        final int extendedApproved = extendedSelected;
        final int missingTcApproved = missingTcSelected;
        final int manualApproved = manualSelected;
        final Path dir;
        final List<String> command = new ArrayList<String>();
        try {
            dir = Paths.get(project.getText().trim()).toAbsolutePath().normalize();
            Path javaPath = Paths.get(java8.getText().trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve("pom.xml")))
                throw new IOException("Selecciona el proyecto totalcorner-downloader.");
            if (!Files.isRegularFile(javaPath)) throw new IOException("No se encontro Java 8.");
            verifyJava8(javaPath);
            if (blank(System.getenv("TOTALCORNER_DB_PASSWORD")))
                throw new IOException("Falta TOTALCORNER_DB_PASSWORD para consultar MySQL.");
            String cp = managerCodeLocation() + File.pathSeparator + buildClasspath(dir);
            command.add(javaPath.toString()); command.add("-Dfile.encoding=UTF-8");
            command.add("-cp"); command.add(cp); command.add(B22_MAIN);
            command.add("--mode=" + (apply ? "apply" : "simulate"));
            command.add("--league-ids=" + ids);
            command.add("--tolerance-minutes=" + tolerance);
            String desde, hasta;
            if (apply) { desde=snapshot.desde; hasta=snapshot.hasta; }
            else {
                java.time.LocalDateTime now = java.time.LocalDateTime.now(ZoneId.of("Europe/Madrid"));
                java.time.format.DateTimeFormatter f=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss");
                desde=now.minusDays(60).format(f); hasta=now.format(f);
            }
            command.add("--desde=" + desde); command.add("--hasta=" + hasta);
            if (apply) {
                command.add("--expected-hash=" + snapshot.hash);
                command.add("--expected-count=" + selectedKeys.size());
                command.add("--selected=" + String.join(",", selectedKeys));
                command.add("--confirmed-manual=" + (manualApproved > 0));
            }
            if (!apply) { invalidateB22(); b22Guidance.setText("Simulando B2.2... sin escrituras."); }
            else b22Guidance.setText("Aplicando B2.2 bajo control transaccional. No interrumpas el proceso.");
            saveSettings();
            status.setText("B2.2: " + (apply ? "aplicando" : "simulando") + " con +/-"+tolerance+" minutos...");
            setBusy(true); cancelledByUser = false;
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "B2.2 - imposible ejecutar", JOptionPane.ERROR_MESSAGE);
            return;
        }
        SwingWorker<B22Response,Void> worker = new SwingWorker<B22Response,Void>() {
            @Override protected B22Response doInBackground() {
                B22Response r = new B22Response();
                try {
                    ProcessBuilder pb = new ProcessBuilder(command);
                    pb.directory(dir.toFile()); pb.redirectErrorStream(true);
                    pb.environment().remove("TOTALCORNER_API_TOKEN");
                    Process proc = pb.start(); child = proc;
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line=reader.readLine())!=null) {
                            if (line.startsWith("B22_ROW\t")) {
                                String[] parts=line.split("\t",-1);
                                if(parts.length==14) r.rows.add(Arrays.copyOfRange(parts,1,14));
                                else r.error="Fila B2.2 no reconocida";
                            } else if(line.startsWith("B22_DONE\t")) {
                                String[] parts=line.split("\t",-1);
                                if(parts.length==9) {
                                    r.count=Integer.parseInt(parts[1]);
                                    r.safe=Integer.parseInt(parts[2]);r.extended=Integer.parseInt(parts[3]);
                                    r.missingTc=Integer.parseInt(parts[4]);
                                    r.hash=parts[5];r.desde=parts[6];r.hasta=parts[7];r.tolerance=Integer.parseInt(parts[8]);
                                } else r.error="Resumen B2.2 incompleto";
                            } else if(line.startsWith("B22_APPLIED\t")) {
                                String[] parts=line.split("\t",-1);
                                if(parts.length==7) {
                                    r.applied=Integer.parseInt(parts[1]);r.appliedExtended=Integer.parseInt(parts[3]);
                                    r.appliedMissingTc=Integer.parseInt(parts[4]);r.appliedManual=Integer.parseInt(parts[5]);r.hash=parts[6];
                                } else r.error="Confirmacion de escritura invalida";
                            } else if(line.startsWith("B22_ERROR\t")) r.error=line.substring(10);
                            else if(line.startsWith("Exception") || line.startsWith("Error")) r.error=line;
                        }
                    }
                    int exit=proc.waitFor();
                    if (cancelledByUser) r.error="B2.2 interrumpido: comprueba en BD antes de repetir.";
                    else if(exit!=0 && r.error==null)r.error="B2.2 termino con codigo " +exit;
                    else if(r.error==null && !apply && (r.count!=r.rows.size() || r.tolerance!=tolerance))
                        r.error="Simulacion B2.2 incompleta o tolerancia incorrecta";
                    else if(r.error==null && apply && (r.applied!=selectedKeys.size() || r.appliedExtended!=extendedApproved
                            || r.appliedMissingTc!=missingTcApproved || r.appliedManual!=manualApproved
                            || !snapshot.hash.equals(r.hash)))
                        r.error="El resultado de B2.2 no coincide con la operacion solicitada. Comprueba MySQL.";
                } catch(Exception ex) { r.error="Error B2.2: " + ex.getMessage(); }
                finally { child=null; }
                return r;
            }
            @Override protected void done() {
                B22Response r;
                try { r=get(); } catch(Exception ex) { r=new B22Response();r.error="No se obtuvo el resultado de B2.2"; }
                if(r.error!=null) {
                    invalidateB22();
                    b22Guidance.setText("B2.2 NO validado: " + htmlEscape(r.error) + ". Repite simulacion antes de aplicar.");
                    status.setText("B2.2: error o resultado no confirmado");
                    JOptionPane.showMessageDialog(TotalCornerManager.this,r.error,"B2.2 - revisar",JOptionPane.WARNING_MESSAGE);
                } else if (!apply) {
                    r.leagues=ids;
                    b22Preview=r;
                    for(String[] row:r.rows) {
                        String state=row[12];
                        boolean automatic="LISTO_PARA_ENLAZAR".equals(state);
                        int seconds = Integer.parseInt(row[9]);
                        String diff=String.format(Locale.ROOT,"%.1f",seconds/60.0);
                        b22Model.addRow(new Object[] {Boolean.valueOf(automatic), state, row[0],row[1],row[2],
                            row[3]+" - "+row[4],row[5]+" - "+row[6],row[7].replace('_',' '),row[8].replace('_',' '),
                            diff,row[10]+" / "+row[11]});
                    }
                    b22Guidance.setText("Simulacion OK: " + r.safe + " seguros marcados, " + (r.count-r.safe)
                        + " para seleccion manual (desmarcados). Todos los estados son seleccionables. "
                        + (r.count == 0 ? "Sin candidatos; no es necesario aplicar." : "Despues confirma el numero exacto para insertar."));
                    status.setText("B2.2 simulado: " + r.count + " parejas, " + r.safe + " seguras, "
                            + r.extended + " ampliadas y " + r.missingTc + " sin marcador TC. 0 escrituras.");
                    if(r.count==0)markProgress("B2.2",WorkflowProgress.SKIP,"Simulacion sin candidatos: no aplicar enlaces");
                } else {
                    invalidateB22();
                    b22Guidance.setText("B2.2 confirmado: " + r.applied + " enlaces VERIFIED, de ellos "
                        + r.appliedManual + " manuales. Siguiente: repetir B2.1 y continuar con B2.3.");
                    status.setText("B2.2 finalizado: " +r.applied+" nuevos enlaces.");
                    markProgress("B2.2",WorkflowProgress.DONE,"Enlaces insertados y comprobados: "+r.applied+"; repetir B2.1");
                    b21Model.setRowCount(0);b21DetailModel.setRowCount(0);
                    JOptionPane.showMessageDialog(TotalCornerManager.this,"B2.2: " +r.applied+" enlaces insertados. Vuelve a consultar B2.1.",
                        "B2.2 confirmado",JOptionPane.INFORMATION_MESSAGE);
                }
                setBusy(false);
            }
        };
        worker.execute();
    }

    private void buildB23Tab() {
        JPanel panel=new JPanel(new BorderLayout(5,5));panel.setBorder(new EmptyBorder(8,4,6,4));
        JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
        JLabel h=new JLabel("B2.3 - Informe unificado multiliga de partidos sin enlazar");
        h.setFont(h.getFont().deriveFont(Font.BOLD,16f));top.add(h);
        top.add(new JLabel("Diagnostico, NO aplica enlaces. Margen de candidatos por defecto +/-48 horas; admite hasta 10 dias."));
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,6,5));
        actions.add(new JLabel("Periodo:")); actions.add(b23Period); actions.add(new JLabel("Margen (horas):"));
        b23Margin.setPreferredSize(new Dimension(70,26));actions.add(b23Margin); actions.add(b23IncludeTc);
        b23Query.addActionListener(e->runB23()); b23Export.addActionListener(e->exportB23());
        b23Export.setEnabled(false);actions.add(b23Query);actions.add(b23Export);
        JButton next=new JButton("Ir a B3");next.addActionListener(e->tabs.setSelectedIndex(5));actions.add(next);
        top.add(actions);panel.add(top,BorderLayout.NORTH);
        b23SummaryTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        b23SummaryTable.setRowHeight(24);
        for(int i=0;i<b23SummaryTable.getColumnCount();i++)b23SummaryTable.getColumnModel().getColumn(i).setPreferredWidth(i==3?200:130);
        b23DetailTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);b23DetailTable.setRowHeight(23);
        for(int i=0;i<b23DetailTable.getColumnCount();i++)b23DetailTable.getColumnModel().getColumn(i).setPreferredWidth(i==3||i==13||i==14||i==16||i==17?200:130);
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,ManagerUiSupport.withTotals(b23SummaryTable),ManagerUiSupport.withTotals(b23DetailTable));
        split.setResizeWeight(.33);panel.add(split,BorderLayout.CENTER);
        b23Guidance.setBorder(new EmptyBorder(7,2,7,2));panel.add(b23Guidance,BorderLayout.SOUTH);
        tabs.addTab("5. B2.3 Informe",panel);
    }
    private void buildB3Tab() {
        JPanel panel=new JPanel(new BorderLayout(5,5));panel.setBorder(new EmptyBorder(8,4,6,4));
        JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
        JLabel title=new JLabel("B3 - Cuotas de partidos finalizados en Footiqo con enlace VERIFIED");
        title.setFont(title.getFont().deriveFont(Font.BOLD,16f));top.add(title);
        top.add(new JLabel("Primero consultar pendientes y simular. Reutiliza los dos endpoints y conserva sus JSON; NO altera tablas ls_."));
        JPanel settings=new JPanel(new FlowLayout(FlowLayout.LEFT,7,4));
        settings.add(new JLabel("Desde temporada:"));settings.add(b3Newest);
        settings.add(new JLabel("Hasta:"));settings.add(b3Oldest);
        settings.add(new JLabel("Lote:"));settings.add(b3Batch);
        settings.add(new JLabel("Max. peticiones:"));settings.add(b3Requests);
        settings.add(new JLabel("Max. horas:"));settings.add(b3Hours);
        top.add(settings);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,7,4));
        b3Config.addActionListener(e->runB3("config")); b3Control.addActionListener(e->runB3Control());
        b3Simulate.addActionListener(e->runB3("simulate"));b3Execute.addActionListener(e->runB3("execute"));
        b3Diagnostic.addActionListener(e->runB3Diagnostic());
        b3Execute.setEnabled(false);b3Stop.addActionListener(e->stopChild());b3Stop.setEnabled(false);
        actions.add(b3Config);actions.add(b3Control);actions.add(b3Simulate);actions.add(b3Execute);actions.add(b3Diagnostic);actions.add(b3Stop);
        JButton nextB4=new JButton("Ir a B4");nextB4.addActionListener(e->tabs.setSelectedIndex(6));actions.add(nextB4);
        top.add(actions);panel.add(top,BorderLayout.NORTH);
        b3Table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);b3Table.setRowHeight(23);
        for(int i=0;i<b3Table.getColumnCount();i++)b3Table.getColumnModel().getColumn(i).setPreferredWidth(i==1?215:142);
        b3Output.setEditable(false);b3Output.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,ManagerUiSupport.withTotals(b3Table),new JScrollPane(b3Output));
        split.setResizeWeight(.33);panel.add(split,BorderLayout.CENTER);
        b3Guidance.setBorder(new EmptyBorder(6,2,6,2));panel.add(b3Guidance,BorderLayout.SOUTH);
        tabs.addTab("6. B3 Cuotas",panel);
    }
    private void buildB4Tab() {
        JPanel panel=new JPanel(new BorderLayout(5,5));panel.setBorder(new EmptyBorder(8,4,6,4));
        JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
        JLabel heading=new JLabel("B4 - Procesar cuotas descargadas (MySQL)");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD,16f));top.add(heading);
        top.add(new JLabel("Lee los JSON descargados en B3 y genera datos de cuotas/cobertura en MySQL. Sin API; solo escribe tc_cov_*."));
        JPanel options=new JPanel(new FlowLayout(FlowLayout.LEFT,8,4));
        options.add(new JLabel("Partidos por lote (1-500):"));options.add(b4Limit);
        options.add(new JLabel("Max. minutos (1-240):"));options.add(b4Minutes);
        JButton goBack=new JButton("Volver a B3");goBack.addActionListener(e->tabs.setSelectedIndex(5));
        options.add(goBack);
        JButton nextB5=new JButton("Ir a B5 ZIP");nextB5.addActionListener(e->tabs.setSelectedIndex(7));options.add(nextB5);
        top.add(options);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,7,4));
        b4Control.addActionListener(e->runB4Control());
        b4Config.addActionListener(e->runB4("config"));
        b4Simulate.addActionListener(e->runB4("simulate"));
        b4Execute.addActionListener(e->runB4("execute"));
        b4Stop.addActionListener(e->stopChild());
        b4Diagnose.addActionListener(e->runB4Diagnostics());
        b4Execute.setEnabled(false);b4Stop.setEnabled(false);
        actions.add(b4Control);actions.add(b4Config);actions.add(b4Simulate);actions.add(b4Execute);
        actions.add(b4Diagnose);actions.add(b4Stop);
        top.add(actions);panel.add(top,BorderLayout.NORTH);
        b4Table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);b4Table.setRowHeight(23);
        for(int i=0;i<b4Table.getColumnCount();i++)b4Table.getColumnModel().getColumn(i).setPreferredWidth(i==1?245:175);
        b4Output.setEditable(false);b4Output.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,ManagerUiSupport.withTotals(b4Table),new JScrollPane(b4Output));
        split.setResizeWeight(.35);panel.add(split,BorderLayout.CENTER);
        b4Guidance.setBorder(new EmptyBorder(6,2,6,2));panel.add(b4Guidance,BorderLayout.SOUTH);
        tabs.addTab("7. B4 Procesar cuotas",panel);
    }
    private void invalidateB4() {
        b4ControlKey="";b4Pending=-1;b4Ready=-1;b4WaitingB3=-1;b4Blocked=-1;b4SimulatedKey="";
        b4Model.setRowCount(0);b4Execute.setEnabled(false);
        b4Guidance.setText("Consulta de nuevo B4 despues de cambiar la seleccion o completar B3.");
    }
    private String b4Key() {
        String filename=config.getText().trim();String configStamp="?",builderStamp="?";
        try {Path p=Paths.get(filename);configStamp=Files.getLastModifiedTime(p).toMillis()+":"+Files.size(p);}catch(Exception ignored){}
        try {Path p=Paths.get(project.getText().trim()).resolve("target/classes/com/footiqo/totalcorner/OddsCoverageBuilder.class");
            builderStamp=Files.getLastModifiedTime(p).toMillis()+":"+Files.size(p);}catch(Exception ignored){}
        return chosenIds()+"|"+filename+"|"+configStamp+"|"+builderStamp+"|"+b4Limit.getValue()+"|"+b4Minutes.getValue();
    }
    private Path selectedB4Properties(Path dir)throws IOException {
        Path from=Paths.get(config.getText().trim());
        if(!Files.isRegularFile(from))throw new IOException("No existe config de ligas: "+from);
        Properties props=new Properties();try(InputStream in=Files.newInputStream(from)){props.load(in);}
        String csv=chosenIds();if(csv.isEmpty())throw new IOException("Selecciona ligas para B4");
        Set<String> configured=new HashSet<String>();for(String raw:props.getProperty("league.ids","").split(","))configured.add(raw.trim());
        for(String id:csv.split(","))if(!configured.contains(id))throw new IOException("Liga "+id+" ausente del properties original");
        props.setProperty("league.ids",csv);
        Path folder=dir.resolve("target").resolve("totalcorner-manager");Files.createDirectories(folder);
        Path path=folder.resolve("b4-selected-"+csv.replace(',','-')+".properties");
        try(OutputStream out=Files.newOutputStream(path)){props.store(out,"Seleccion de ligas B4 por TotalCorner Manager");}
        return path;
    }
    private static final class B4ControlResponse {
        final List<String[]> rows=new ArrayList<String[]>();int count=-1;
        long pending=-1,up=0,total=0,ready=0,waiting=0,blocked=0;String error;
    }
    private void runB4Control() {
        if(busy)return;
        final List<League> selected=leagues.getSelectedValuesList();if(selected.isEmpty()){warn("Selecciona ligas para B4");return;}
        final List<String> command;final Path dir;final String key=b4Key();
        try {command=managerCommand(B4_CONTROL_MAIN);dir=Paths.get(project.getText().trim()).toAbsolutePath().normalize();
            command.add("--league-ids="+chosenIds());}
        catch(Exception ex){warn(ex.getMessage());return;}
        b4Model.setRowCount(0);b4ControlKey="";b4Pending=-1;b4Ready=-1;b4WaitingB3=-1;b4Blocked=-1;
        b4SimulatedKey="";b4Execute.setEnabled(false);
        b4Guidance.setText("Consultando cobertura B4 de las ligas seleccionadas (solo lectura MySQL)...");
        setBusy(true);cancelledByUser=false;
        new SwingWorker<B4ControlResponse,Void>() {
            @Override protected B4ControlResponse doInBackground() {
                B4ControlResponse r=new B4ControlResponse();
                try {
                    ProcessBuilder pb=new ProcessBuilder(command);pb.directory(dir.toFile());pb.redirectErrorStream(true);
                    pb.environment().remove("TOTALCORNER_API_TOKEN");Process proc=pb.start();child=proc;
                    try(BufferedReader reader=new BufferedReader(new InputStreamReader(proc.getInputStream(),StandardCharsets.UTF_8))) {
                        String line;while((line=reader.readLine())!=null){
                            if(line.startsWith("B4_ROW\t")){String[] parts=line.split("\t",-1);
                                if(parts.length==10)r.rows.add(Arrays.copyOfRange(parts,1,parts.length));else r.error="Formato B4_ROW incorrecto; recompilar Manager";}
                            else if(line.startsWith("B4_DONE\t")){String[] parts=line.split("\t",-1);
                                if(parts.length==8){r.count=Integer.parseInt(parts[1]);r.pending=Long.parseLong(parts[2]);
                                    r.up=Long.parseLong(parts[3]);r.total=Long.parseLong(parts[4]);
                                    r.ready=Long.parseLong(parts[5]);r.waiting=Long.parseLong(parts[6]);
                                    r.blocked=Long.parseLong(parts[7]);}else r.error="Formato B4_DONE incorrecto";}
                            else if(line.startsWith("B4_ERROR\t"))r.error=line.substring(9);
                        }
                    }
                    int code=proc.waitFor();if(cancelledByUser)r.error="Consulta B4 interrumpida";
                    else if(code!=0&&r.error==null)r.error="Error ejecutando control B4: codigo "+code;
                    if(r.error==null&&(r.count!=selected.size()||r.rows.size()!=selected.size()))r.error="Control B4 incompleto: "+r.rows.size()+"/"+selected.size();
                    if(r.error==null&&r.total!=r.up+r.pending)r.error="Control B4 incoherente: total != al dia + pendiente. Revisar SQL.";
                    if(r.error==null&&r.pending!=r.ready+r.waiting+r.blocked)
                        r.error="Control B4 incoherente: pendientes != procesables + esperando B3 + bloqueados";
                }catch(Exception ex){r.error="Error de control B4: "+ex.getMessage();}finally{child=null;}
                return r;
            }
            @Override protected void done() {
                B4ControlResponse r;try{r=get();}catch(Exception e){r=new B4ControlResponse();r.error=e.getMessage();}
                if(r.error!=null){b4Guidance.setText("B4 sin confirmar: "+r.error);warn(r.error);}
                else {
                    for(String[] row:r.rows)b4Model.addRow(row);
                    b4Pending=r.pending;b4Ready=r.ready;b4WaitingB3=r.waiting;b4Blocked=r.blocked;b4ControlKey=key;
                    if(r.pending==0)b4Guidance.setText("Cobertura B4 al dia para las ligas seleccionadas. No ejecutar Builder. Siguiente: B5 (ZIP comerciales).");
                    else if(r.ready==0)b4Guidance.setText("B4 SIN PROCESABLES: "+r.waiting+
                        " esperan JSON B3 y "+r.blocked+" estan bloqueados. No ejecutar Builder; diagnosticar pendientes + CSV.");
                    else b4Guidance.setText("B4 pendientes="+r.pending+", procesables="+r.ready+
                        ", sin fuentes B3="+r.waiting+", bloqueados="+r.blocked+". Simular solo los procesables.");
                    status.setText("B4: "+r.ready+" procesables / "+r.pending+" pendientes.");
                    boolean b4NowComplete = r.pending==0;
                    boolean b4Executed = progress.detail("B4").startsWith("Ejecucion real B4") ||
                            WorkflowProgress.DONE.equals(progress.state("B4"));
                    markProgress("B4",b4NowComplete?(b4Executed?WorkflowProgress.DONE:WorkflowProgress.SKIP):WorkflowProgress.REVIEW,
                        "Control cobertura: pendientes="+r.pending+", procesables="+r.ready+
                            ", esperando B3="+r.waiting+", bloqueados="+r.blocked+", al dia="+r.up+"/"+r.total+
                            (b4NowComplete&&b4Executed?"; procesamiento real verificado":""));
                }
                setBusy(false);
            }
        }.execute();
    }
    /** Explica por que el control B4 ve pendientes y el Builder no los selecciona. API=0, MySQL solo lectura. */
    private void runB4Diagnostics() {
        if(busy)return;
        if(leagues.getSelectedValuesList().isEmpty()){warn("Selecciona al menos una liga para B4");return;}
        final List<String> command;final Path dir;final Path report;
        try {
            command=managerCommand(B4_DIAG_MAIN);
            dir=Paths.get(project.getText().trim()).toAbsolutePath().normalize();
            report=dir.resolve("reports/totalcorner/b4_diagnostico_"+
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS"))+".csv");
            command.add("--league-ids="+chosenIds());command.add("--output="+report.toString());
        }catch(Exception ex){warn(ex.getMessage());return;}
        b4Output.setText("=== B4 DIAGNOSTICO DE PENDIENTES | API=0 | MYSQL SOLO LECTURA ===\n"+
            "Ligas TC="+chosenIds()+"\n");
        b4Guidance.setText("Analizando por que B4 no selecciona los partidos pendientes...");
        setBusy(true);cancelledByUser=false;
        new SwingWorker<String,String>() {
            @Override protected String doInBackground() {
                try {
                    ProcessBuilder pb=new ProcessBuilder(command);pb.directory(dir.toFile());pb.redirectErrorStream(true);
                    pb.environment().remove("TOTALCORNER_API_TOKEN");
                    Process proc=pb.start();child=proc;
                    boolean finished=false,error=false;
                    try(BufferedReader br=new BufferedReader(new InputStreamReader(proc.getInputStream(),StandardCharsets.UTF_8))) {
                        String l;while((l=br.readLine())!=null){publish(l);
                            if(l.startsWith("B4_DIAG_DONE\t"))finished=true;
                            if(l.startsWith("B4_DIAG_ERROR\t"))error=true;
                        }
                    }
                    int code=proc.waitFor();
                    if(cancelledByUser)return "DIAGNOSTICO INTERRUMPIDO";
                    if(code!=0||error||!finished)return "ERROR B4 DIAGNOSTICO: revisa el detalle de la consulta";
                    return "B4 DIAGNOSTICO COMPLETADO: CSV: "+report;
                }catch(Exception ex){return "ERROR B4 DIAGNOSTICO: "+ex.getMessage();}
                finally {child=null;}
            }
            @Override protected void process(List<String> lines) {
                for(String l:lines)b4Output.append(l+"\n");
                b4Output.setCaretPosition(b4Output.getDocument().getLength());
            }
            @Override protected void done() {
                String result;try{result=get();}catch(Exception ex){result="ERROR B4 DIAGNOSTICO: "+ex.getMessage();}
                b4Output.append("\n== "+result+" ==\n");
                b4Guidance.setText(result);status.setText(result);
                if(result.startsWith("B4 DIAGNOSTICO COMPLETADO"))
                    markProgress("B4",WorkflowProgress.REVIEW,"Pendientes diagnosticados; revisar CSV antes de B5");
                setBusy(false);
            }
        }.execute();
    }

    private void runB4(final String mode) {
        if(busy)return;
        if(leagues.getSelectedValuesList().isEmpty()){warn("Selecciona ligas para B4");return;}
        final String key=b4Key();
        if("execute".equals(mode)) {
            if(!key.equals(b4SimulatedKey)||!key.equals(b4ControlKey)){
                warn("Primero consulta B4 y simula con exactamente la misma seleccion, lote y minutos.");return;}
            if(b4Pending<=0){warn("B4 ya no tiene pendientes segun el ultimo control. Siguiente: B5.");return;}
            if(b4Ready<=0){warn("B4 no tiene partidos procesables. Revisa Esperando JSON B3 / Bloqueados y Diagnosticar pendientes + CSV.");return;}
            int yes=JOptionPane.showConfirmDialog(this,
                "B4 REAL: se actualizaran SOLO tablas tc_cov_* de las ligas seleccionadas.\n"
                +"Pendientes estimados: "+b4Pending+" | lote: "+b4Limit.getValue()+" | max. minutos: "+b4Minutes.getValue()+".\n"
                +"No se consultara la API ni se modificaran tablas ls_*.\n¿Ejecutar?",
                "Confirmar procesamiento B4",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE);
            if(yes!=JOptionPane.YES_OPTION)return;
        }
        final List<String> command;final Path dir;final Path log;
        try {
            command=managerCommand(B4_RUNNER);dir=Paths.get(project.getText().trim()).toAbsolutePath().normalize();
            Path variant=selectedB4Properties(dir);
            command.add("--config="+variant.toString());
            if(!"config".equals(mode)){command.add("--limit="+b4Limit.getValue());command.add("--max-minutes="+b4Minutes.getValue());}
            if("config".equals(mode))command.add("--check-config");
            if("execute".equals(mode))command.add("--execute");
            Path logs=dir.resolve("target/totalcorner-manager/logs");Files.createDirectories(logs);
            log=logs.resolve("B4-"+mode+"-"+java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))+".log");
        }catch(Exception ex){warn(ex.getMessage());return;}
        if(!"execute".equals(mode)){b4SimulatedKey="";b4Execute.setEnabled(false);}
        b4Output.setText("=== B4 "+mode.toUpperCase(Locale.ROOT)+" ===\nTC ligas "+chosenIds()+
            " | lote "+b4Limit.getValue()+" | max minutos "+b4Minutes.getValue()+"\n");
        b4Guidance.setText("Ejecutando B4 "+mode+". Log: "+log);
        setBusy(true);cancelledByUser=false;
        new SwingWorker<String,String>() {
            @Override protected String doInBackground() {
                boolean goodConfig=false,goodPreview=false,goodFinish=false,error=false,skippedInvalid=false;
                int selectedInBuilder=-1;
                try(BufferedWriter writer=Files.newBufferedWriter(log,StandardCharsets.UTF_8)) {
                    ProcessBuilder pb=new ProcessBuilder(command);pb.directory(dir.toFile());pb.redirectErrorStream(true);
                    pb.environment().remove("TOTALCORNER_API_TOKEN");
                    Process proc=pb.start();child=proc;
                    try(BufferedReader reader=new BufferedReader(new InputStreamReader(proc.getInputStream(),StandardCharsets.UTF_8))) {
                        String line;while((line=reader.readLine())!=null){
                            writer.write(line);writer.newLine();writer.flush();publish(line);
                            if(line.contains("CONFIGURACION VALIDA |")&&line.contains("SQL=0 | API=0"))goodConfig=true;
                            if(line.contains("TC COVERAGE v2downloaded")&&line.contains("SIMULACION SOLO LECTURA"))goodPreview=true;
                            if(line.startsWith("TC COVERAGE v2downloaded")) {
                                Matcher m=Pattern.compile("partidos=(\\d+)").matcher(line);
                                if(m.find())selectedInBuilder=Integer.parseInt(m.group(1));
                            }
                            if(line.startsWith("FIN LOTE |")) {
                                goodFinish=true;
                                Matcher m=Pattern.compile("seleccionados=(\\d+)").matcher(line);
                                if(m.find())selectedInBuilder=Integer.parseInt(m.group(1));
                            }
                            if(line.startsWith("ERROR ")||line.startsWith("ERROR:")||line.startsWith("ARGUMENTOS:")||line.startsWith("STOP:")||
                                line.startsWith("Falta TOTALCORNER")||line.startsWith("Exception in thread"))error=true;
                            if(line.contains("OMITIDO (fuente no completa"))skippedInvalid=true;
                        }
                    }
                    int code=proc.waitFor();if(cancelledByUser)return "INTERRUMPIDO B4: revisar y reanudar pendientes.";
                    if(code!=0||error)return "B4 NO CONFIRMADO: fallo o bloqueo; revisa "+log;
                    if("config".equals(mode))return goodConfig?"CONFIGURACION B4 VALIDADA (0 API / SQL)":"B4 NO CONFIRMADO: falta resumen de configuracion";
                    if("simulate".equals(mode)) {
                        if(goodPreview&&goodFinish&&selectedInBuilder==0&&b4Ready>0)
                            return "B4 INCONSISTENTE: control indica "+b4Ready+" procesables, Builder selecciona 0. Diagnosticar + CSV.";
                        if(goodPreview&&goodFinish&&selectedInBuilder==0)
                            return "B4 SIN PROCESABLES: "+b4WaitingB3+" pendientes esperan B3; "+b4Blocked+" bloqueados.";
                        return goodPreview&&goodFinish&&selectedInBuilder>0?"SIMULACION B4 VALIDADA ("+selectedInBuilder+" seleccionados; 0 escrituras)":"B4 NO CONFIRMADO: falta resumen de simulacion";
                    }
                    if(goodFinish&&selectedInBuilder==0&&b4Pending>0)
                        return "B4 INCONSISTENTE: 0 seleccionados pese a "+b4Ready+" procesables en control. Diagnosticar + CSV.";
                    return goodFinish?(skippedInvalid?"B4 EJECUTADO CON OMISIONES: volver a consultar y revisar JSON B3":"B4 EJECUTADO: volver a consultar cobertura B4"):
                        "B4 NO CONFIRMADO: falta FIN LOTE";
                }catch(Exception ex){return "ERROR B4: "+ex.getMessage();}finally{child=null;}
            }
            @Override protected void process(List<String> lines) {
                for(String line:lines)b4Output.append(line+"\n");
                if(b4Output.getDocument().getLength()>240000)b4Output.replaceRange("[Consola recortada. Ver log B4]\n",0,80000);
                b4Output.setCaretPosition(b4Output.getDocument().getLength());
            }
            @Override protected void done() {
                String result;try{result=get();}catch(Exception ex){result="ERROR B4: "+ex.getMessage();}
                b4Output.append("\n== "+result+" ==\n");b4Guidance.setText(result);status.setText(result);
                if("simulate".equals(mode)&&result.startsWith("SIMULACION B4 VALIDADA"))b4SimulatedKey=key;
                if(result.startsWith("B4 INCONSISTENTE")){
                    markProgress("B4",WorkflowProgress.REVIEW,result);
                    warn(result);
                }
                if("execute".equals(mode))b4SimulatedKey="";
                setBusy(false);
                if("execute".equals(mode)&&result.startsWith("B4 EJECUTADO")){
                    markProgress("B4",WorkflowProgress.REVIEW,"Ejecucion real B4 confirmada; control de cobertura posterior necesario");
                    runB4Control();
                }
            }
        }.execute();
    }

    private static final class B23Response {
        final List<String[]> summary=new ArrayList<String[]>(),detail=new ArrayList<String[]>();
        String error;int expected=-1,confirmed=-1,reportedDetail=-1;boolean preflight;
    }
    private static final class B3ControlResponse {
        final List<String[]> rows=new ArrayList<String[]>();String error;int count=-1;long pending,missingJson;
    }
    /** Prepara conexion y Java externo sin compartir credenciales en parametros. */
    private List<String> managerCommand(String main) throws Exception {
        Path dir=Paths.get(project.getText().trim()).toAbsolutePath().normalize();
        Path javaPath=Paths.get(java8.getText().trim()).toAbsolutePath().normalize();
        if(!Files.isDirectory(dir)||!Files.isRegularFile(dir.resolve("pom.xml")))throw new IOException("Revisa la carpeta totalcorner-downloader");
        if(!Files.isRegularFile(javaPath))throw new IOException("Java 8 no encontrado");
        verifyJava8(javaPath);
        if(blank(System.getenv("TOTALCORNER_DB_PASSWORD")))throw new IOException("Falta TOTALCORNER_DB_PASSWORD (reinicia Eclipse tras configurarlo)");
        return new ArrayList<String>(Arrays.asList(javaPath.toString(),"-Dfile.encoding=UTF-8","-cp",managerCodeLocation()+File.pathSeparator+buildClasspath(dir),main));
    }
    private String chosenIds() {
        StringJoiner j=new StringJoiner(",");for(League l:leagues.getSelectedValuesList())j.add(l.id);
        return j.toString();
    }
    private void runB23() {
        if(busy)return;
        final List<League> chosen=leagues.getSelectedValuesList();if(chosen.isEmpty()){warn("Selecciona al menos una liga");return;}
        final List<String> args; final Path dir;
        try {
            args=managerCommand(B23_MAIN);dir=Paths.get(project.getText().trim());
            args.add("--league-ids="+chosenIds());
            args.add("--period="+(b23Period.getSelectedIndex()==0?"60d":"historico"));
            args.add("--margin-hours="+b23Margin.getValue());
            args.add("--include-solo-tc="+b23IncludeTc.isSelected());
        } catch(Exception ex){warn(ex.getMessage());return;}
        b23SummaryModel.setRowCount(0);b23DetailModel.setRowCount(0);b23Export.setEnabled(false);
        b23Guidance.setText("Consultando B2.3... No se escriben tablas permanentes ni se consume API.");
        setBusy(true);cancelledByUser=false;
        new SwingWorker<B23Response,Void>(){
            @Override protected B23Response doInBackground() {
                B23Response r=new B23Response();r.expected=chosen.size();
                try {
                    ProcessBuilder pb=new ProcessBuilder(args);pb.directory(dir.toFile());pb.redirectErrorStream(true);
                    pb.environment().remove("TOTALCORNER_API_TOKEN");
                    Process proc=pb.start();child=proc;
                    try(BufferedReader read=new BufferedReader(new InputStreamReader(proc.getInputStream(),StandardCharsets.UTF_8))){
                        String line;while((line=read.readLine())!=null){
                            if(line.startsWith("B23_ROW\t")){String[] f=line.split("\t",-1);if(f.length==11)r.summary.add(Arrays.copyOfRange(f,1,f.length));else r.error="Resumen B2.3 mal formado";}
                            else if(line.startsWith("B23_DETAIL\t")){String[] f=line.split("\t",-1);if(f.length==B23_COLUMNS.length+1)r.detail.add(Arrays.copyOfRange(f,1,f.length));else r.error="Detalle B2.3 mal formado: "+f.length;}
                            else if(line.startsWith("B23_PREFLIGHT\tOK"))r.preflight=true;
                            else if(line.startsWith("B23_DONE\t")){String[] f=line.split("\t",-1);r.confirmed=Integer.parseInt(f[1]);r.reportedDetail=Integer.parseInt(f[2]);}
                            else if(line.startsWith("B23_ERROR\t"))r.error=line.substring(10);
                        }
                    }
                    int exit=proc.waitFor();
                    if(cancelledByUser)r.error="Consulta interrumpida";
                    else if(exit!=0 && r.error==null)r.error="B2.3 finalizo con codigo "+exit;
                    if(r.error==null&&(!r.preflight||r.confirmed!=r.expected||r.summary.size()!=r.expected||r.reportedDetail!=r.detail.size()))
                        r.error="Resultados B2.3 incompletos; revisa conexion/SQL.";
                }catch(Exception ex){r.error="Fallo B2.3: "+ex.getMessage();}finally{child=null;}
                return r;
            }
            @Override protected void done(){
                B23Response r;try{r=get();}catch(Exception ex){r=new B23Response();r.error=ex.getMessage();}
                if(r.error!=null){b23Guidance.setText("B2.3 sin confirmar: "+r.error);warn(r.error);}
                else{
                    for(String[] f:r.summary)b23SummaryModel.addRow(f);
                    for(String[] f:r.detail)b23DetailModel.addRow(f);
                    long missingLs=0, missingTc=0; for(String[] f:r.summary){missingLs+=safeCount(f[4]);missingTc+=safeCount(f[6]);}
                    b23Guidance.setText("B2.3: "+r.summary.size()+" ligas, "+r.detail.size()+" filas. "+
                        (missingLs>0||missingTc>0?"Sin enlace VERIFIED: "+missingLs+" Footiqo y "+missingTc+" TC con marcador. Revisar candidatos / B2.4; B3 solo usa VERIFIED.":
                        "Sin partidos sin enlace verificado en este periodo. Siguiente: B3 (pestana 6)."));
                    status.setText("B2.3 terminado: "+r.detail.size()+" incidencias/candidatos (sin escrituras permanentes)");
                    markProgress("B2.3",missingLs>0||missingTc>0?WorkflowProgress.REVIEW:WorkflowProgress.DONE,
                        "Informe consultado, filas="+r.detail.size()+", sin verificar LS="+missingLs+", TC="+missingTc);
                }
                setBusy(false);
            }
        }.execute();
    }
    private void exportB23() {
        if(b23DetailModel.getRowCount()==0){warn("No hay detalle B2.3 que exportar");return;}
        JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new File("TotalCorner_B23_Informe_"+LocalDate.now()+".csv"));
        if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;
        File f=chooser.getSelectedFile();
        if(f.exists()&&JOptionPane.showConfirmDialog(this,"El archivo existe. ¿Sobrescribir?","Exportar",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
        try(Writer w=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f),StandardCharsets.UTF_8))){
            w.write('\ufeff');writeCsv(w,B23_COLUMNS);
            for(int r=0;r<b23DetailModel.getRowCount();r++){
                String[] values=new String[B23_COLUMNS.length];for(int c=0;c<values.length;c++)values[c]=String.valueOf(b23DetailModel.getValueAt(r,c));
                writeCsv(w,values);
            }
            status.setText("B2.3 exportado: "+f.getAbsolutePath());
        }catch(IOException e){warn("No se pudo exportar: "+e.getMessage());}
    }
    private static void writeCsv(Writer w,String[] fields)throws IOException {
        for(int i=0;i<fields.length;i++){
            if(i>0)w.write(';');
            String v=fields[i]==null?"":fields[i]; w.write('"');w.write(v.replace("\"","\"\""));w.write('"');
        }w.write("\r\n");
    }
    private void warn(String message) {JOptionPane.showMessageDialog(this,message,"TotalCorner Manager",JOptionPane.WARNING_MESSAGE);}
    private String b3Key(){
        String source=config.getText().trim();String stamp="?";
        try{Path p=Paths.get(source);stamp=Files.getLastModifiedTime(p).toMillis()+":"+Files.size(p);}catch(Exception ignored){}
        String runnerStamp="?",batchStamp="?";
        try {Path f=Paths.get(project.getText().trim()).resolve("target/classes/com/footiqo/totalcorner/HistoricalDownloadRunner.class");
            runnerStamp=Files.getLastModifiedTime(f).toMillis()+":"+Files.size(f);}catch(Exception ignored){}
        try {Path f=Paths.get(project.getText().trim()).resolve("target/classes/com/footiqo/totalcorner/LeagueOddsBatchDownloader.class");
            batchStamp=Files.getLastModifiedTime(f).toMillis()+":"+Files.size(f);}catch(Exception ignored){}
        return chosenIds()+"|"+source+"|"+stamp+"|"+runnerStamp+"|"+batchStamp+"|"+
            b3Newest.getValue()+"|"+b3Oldest.getValue()+"|"+b3Batch.getValue()+"|"+b3Requests.getValue()+"|"+b3Hours.getValue();
    }
    /** Preserva properties original y escribe variante solo con las ligas seleccionadas. */
    private Path selectedB3Properties(Path dir)throws IOException {
        Path from=Paths.get(config.getText().trim());if(!Files.isRegularFile(from))throw new IOException("No existe config de ligas: "+from);
        Properties props=new Properties();try(InputStream in=Files.newInputStream(from)){props.load(in);}
        String csv=chosenIds();if(csv.isEmpty())throw new IOException("Selecciona ligas para B3");
        Set<String> configured=new HashSet<String>();for(String raw:props.getProperty("league.ids", "").split(","))configured.add(raw.trim());
        for(String id:csv.split(","))if(!configured.contains(id))throw new IOException("Liga "+id+" ausente del properties original");
        props.setProperty("league.ids",csv);
        Path folder=dir.resolve("target").resolve("totalcorner-manager");Files.createDirectories(folder);
        // Nombre estable = permite ver la seleccion sin reescribir el original.
        Path dest=folder.resolve("b3-selected-"+csv.replace(',','-')+".properties");
        try(OutputStream out=Files.newOutputStream(dest)){props.store(out,"Configuracion derivada por TotalCorner Manager - no editar original");}
        return dest;
    }
    private void runB3Diagnostic() {
        if(busy)return;
        if(leagues.getSelectedValuesList().isEmpty()){warn("Selecciona alguna liga para el diagnostico B3");return;}
        final List<String> cmd; final Path dir;
        try {
            cmd=managerCommand("com.footiqo.manager.B3PendingDiagnosticRunner");
            dir=Paths.get(project.getText().trim()).toAbsolutePath().normalize();
            cmd.add("--league-ids="+chosenIds());cmd.add("--newest-year="+b3Newest.getValue());cmd.add("--oldest-year="+b3Oldest.getValue());
        }catch(Exception ex){warn(ex.getMessage());return;}
        b3Output.setText("=== B3 DIAGNOSTICO SOLO LECTURA | API=0 ===\n");
        b3Guidance.setText("Diagnosticando pendientes reales y filtros del descargador...");
        setBusy(true);cancelledByUser=false;
        new SwingWorker<String,String>() {
            @Override protected String doInBackground() {
                try {
                    ProcessBuilder pb=new ProcessBuilder(cmd);pb.directory(dir.toFile());pb.redirectErrorStream(true);
                    Process process=pb.start();child=process;String csv="";boolean ok=false;
                    try(BufferedReader br=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){
                        String line;while((line=br.readLine())!=null){publish(line);
                            if(line.startsWith("B3_DIAG_CSV\t"))csv=line.substring(12);
                            if(line.startsWith("B3_DIAG_RESULT\t"))ok=true;
                        }
                    }
                    int exit=process.waitFor();if(exit!=0||!ok)return "B3: diagnostico fallido; revisa las lineas anteriores";
                    return "B3: CSV creado en "+csv;
                }catch(Exception ex){return "B3: error diagnostico: "+ex.getMessage();}
                finally{child=null;}
            }
            @Override protected void process(List<String> lines){for(String line:lines)b3Output.append(line+"\n");}
            @Override protected void done(){String result;try{result=get();}catch(Exception ex){result=ex.getMessage();}
                b3Output.append("\n"+result+"\n");b3Guidance.setText(result);status.setText(result);setBusy(false);
            }
        }.execute();
    }
    private void runB3Control() {
        if(busy)return;
        final List<League> selected=leagues.getSelectedValuesList();if(selected.isEmpty()){warn("Selecciona las ligas");return;}
        final List<String> cmd;final Path dir;
        try{
            cmd=managerCommand(B3_CONTROL_MAIN);dir=Paths.get(project.getText().trim());
            if(((Number)b3Newest.getValue()).intValue()<((Number)b3Oldest.getValue()).intValue())throw new IOException("Temporadas B3 invertidas");
            cmd.add("--league-ids="+chosenIds());cmd.add("--newest-year="+b3Newest.getValue());cmd.add("--oldest-year="+b3Oldest.getValue());
        }catch(Exception ex){warn(ex.getMessage());return;}
        b3Model.setRowCount(0);b3SimulatedKey="";b3SimulatedPending=-1;
        b3ControlSnapshotKey="";b3ControlSnapshotPending=-1;b3ControlSnapshotMissingJson=-1;b3ControlByLeague.clear();
        b3Guidance.setText("Consultando pendientes B3 en MySQL... sin escrituras ni API.");
        setBusy(true);cancelledByUser=false;
        new SwingWorker<B3ControlResponse,Void>() {
            @Override protected B3ControlResponse doInBackground(){
                B3ControlResponse r=new B3ControlResponse();
                try{
                    ProcessBuilder pb=new ProcessBuilder(cmd);pb.directory(dir.toFile());pb.redirectErrorStream(true);
                    pb.environment().remove("TOTALCORNER_API_TOKEN");Process process=pb.start();child=process;
                    try(BufferedReader br=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){
                        String line;while((line=br.readLine())!=null){
                            if(line.startsWith("B3_ROW\t")){String[] f=line.split("\t",-1);if(f.length==10)r.rows.add(Arrays.copyOfRange(f,1,f.length));else r.error="Fila B3 incorrecta";}
                            else if(line.startsWith("B3_DONE\t")){String[] f=line.split("\t",-1);r.count=Integer.parseInt(f[1]);r.pending=Long.parseLong(f[2]);r.missingJson=Long.parseLong(f[3]);}
                            else if(line.startsWith("B3_ERROR\t"))r.error=line.substring(9);
                        }
                    }
                    int exit=process.waitFor();if(cancelledByUser)r.error="Consulta B3 interrumpida";
                    else if(exit!=0&&r.error==null)r.error="Codigo de salida B3: "+exit;
                    if(r.error==null&&(r.count!=selected.size()||r.rows.size()!=selected.size()))r.error="Control incompleto: "+r.rows.size()+" ligas";
                }catch(Exception ex){r.error="Error B3 control: "+ex.getMessage();}finally{child=null;}return r;
            }
            @Override protected void done(){
                B3ControlResponse r;try{r=get();}catch(Exception ex){r=new B3ControlResponse();r.error=ex.getMessage();}
                if(r.error!=null){b3ControlSnapshotKey="";b3ControlSnapshotPending=-1; b3Guidance.setText("Control B3 no confirmado: "+r.error);warn(r.error);}
                else{
                    for(String[] row:r.rows) {
                        b3Model.addRow(row);
                        b3ControlByLeague.put(row[0],new long[]{Long.parseLong(row[2]),
                            Long.parseLong(row[3]),Long.parseLong(row[4])});
                    }
                    b3ControlSnapshotKey=b3Key();b3ControlSnapshotPending=r.pending;
                    b3ControlSnapshotMissingJson=r.missingJson;
                    if(r.pending==0&&r.missingJson==0)
                        b3Guidance.setText("Sin trabajos pendientes y ambos JSON presentes en metadatos para elegibles. Siguiente paso: B4.");
                    else b3Guidance.setText("Pendientes: "+r.pending+" partidos; discrepancias JSON: "+r.missingJson+
                        ". Simula B3. DONE no garantiza integridad fisica del fichero.");
                    status.setText("B3 control consultado: "+selected.size()+" ligas; "+r.pending+" partidos pendientes.");
                    boolean b3NowComplete = r.pending==0 && r.missingJson==0;
                    boolean b3Executed = progress.detail("B3").startsWith("Ejecucion real B3") ||
                            WorkflowProgress.DONE.equals(progress.state("B3"));
                    markProgress("B3",b3NowComplete?(b3Executed?WorkflowProgress.DONE:WorkflowProgress.SKIP):WorkflowProgress.REVIEW,
                        "Control: pendientes="+r.pending+", JSON sin confirmar="+r.missingJson+
                            (b3NowComplete&&b3Executed?"; descarga real verificada":""));
                }
                setBusy(false);
            }
        }.execute();
    }
    private void runB3(final String mode) {
        if(busy)return;
        final String key=b3Key();
        if(leagues.getSelectedValuesList().isEmpty()){warn("Selecciona ligas para B3");return;}
        final int latest=((Number)b3Newest.getValue()).intValue(),oldest=((Number)b3Oldest.getValue()).intValue();
        if(oldest>latest){warn("La temporada mas antigua no puede ser posterior a la reciente");return;}
        if("execute".equals(mode)) {
            if(!key.equals(b3SimulatedKey)||!key.equals(b3ControlSnapshotKey)||b3SimulatedPending<=0){
                warn("B3 requiere control y simulacion coincidentes con pendientes >0. Repite ambos controles.");return;}
            if(blank(System.getenv("TOTALCORNER_API_TOKEN"))){warn("Falta TOTALCORNER_API_TOKEN en el entorno de Eclipse");return;}
            int ok=JOptionPane.showConfirmDialog(this,"B3 REAL: se consultara la API y se escribiran cuotas/JSON en MySQL.\n"
                +"Ligas: "+leagues.getSelectedValuesList().size()+"; temporadas: "+latest+" a "+oldest
                +"; presupuesto: "+b3Requests.getValue()+" peticiones / "+b3Hours.getValue()+" horas.\n"
                +"Solo finalizados en Footiqo con enlace VERIFIED; TC puede no tener marcador. Recomendable copia reciente.\n¿Ejecutar?",
                "Confirmar descargas B3",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE);
            if(ok!=JOptionPane.YES_OPTION)return;
        }
        final Path dir;final List<String> cmd; final Path log;
        try{
            cmd=managerCommand(B3_RUNNER);dir=Paths.get(project.getText().trim()).toAbsolutePath().normalize();
            Path derived=selectedB3Properties(dir);
            cmd.add("--config="+derived.toString());cmd.add("--phase=odds");cmd.add("--verified-final-only");
            cmd.add("--newest-year="+latest);cmd.add("--oldest-year="+oldest);
            cmd.add("--odds-batch="+b3Batch.getValue());cmd.add("--max-requests="+b3Requests.getValue());
            cmd.add("--max-hours="+b3Hours.getValue());
            if("config".equals(mode))cmd.add("--check-config");
            if("execute".equals(mode))cmd.add("--execute");
            Path logs=dir.resolve("target").resolve("totalcorner-manager").resolve("logs");Files.createDirectories(logs);
            log=logs.resolve("B3-"+mode+"-"+java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))+".log");
        }catch(Exception ex){warn(ex.getMessage());return;}
        if(!"execute".equals(mode)) {b3SimulatedKey="";b3SimulatedPending=-1;b3Execute.setEnabled(false);}
        b3Output.setText("=== B3 "+mode.toUpperCase(Locale.ROOT)+" ===\nLigas "+chosenIds()+" | temporadas "+latest+".."+oldest+"\n");
        b3Guidance.setText("B3 "+mode+" en curso. Log: "+log.toString());
        setBusy(true);cancelledByUser=false;
        new SwingWorker<String,String>() {
            @Override protected String doInBackground() {
                boolean goodConfig=false,goodPreview=false,goodFinish=false,failed=false;
                long previewPending=0;int previewRows=0;
                Map<String,long[]> summaryByLeague=new LinkedHashMap<String,long[]>();
                Set<String> seasonKeys=new HashSet<String>();
                try(BufferedWriter writer=Files.newBufferedWriter(log,StandardCharsets.UTF_8)) {
                    ProcessBuilder pb=new ProcessBuilder(cmd);pb.directory(dir.toFile());pb.redirectErrorStream(true);
                    Process proc=pb.start();child=proc;
                    try(BufferedReader reader=new BufferedReader(new InputStreamReader(proc.getInputStream(),StandardCharsets.UTF_8))){
                        String line;while((line=reader.readLine())!=null){
                            writer.write(line);writer.newLine();writer.flush();publish(line);
                            if(line.contains("CONFIGURACION VALIDA | SQL=0 | API=0"))goodConfig=true;
                            if(line.contains("SIMULACION COMPLETADA | HTTP=0 | SQL=SOLO LECTURA"))goodPreview=true;
                            if("simulate".equals(mode) && line.startsWith("B3_SEASON\t")) {
                                String[] fields=line.split("\t",-1);
                                try {
                                    if(fields.length!=6 || !seasonKeys.add(fields[1]+"/"+fields[2]))throw new IllegalArgumentException("B3_SEASON duplicado o incompleto");
                                    long[] vals=summaryByLeague.get(fields[1]);
                                    if(vals==null){vals=new long[3];summaryByLeague.put(fields[1],vals);}
                                    for(int z=0;z<3;z++)vals[z]+=Long.parseLong(fields[3+z]);
                                    previewRows++;
                                    previewPending+=Long.parseLong(fields[5]);
                                }catch(RuntimeException ex){failed=true;}
                            }
                            if(line.contains("FIN HISTORICAL |"))goodFinish=true;
                            if(line.startsWith("ERROR ")||line.startsWith("ERROR:")||line.startsWith("Faltan TOTALCORNER")||
                               line.contains("OTRO HISTORICAL RUNNER ACTIVO")||line.contains("BLOQUEO"))failed=true;
                        }
                    }
                    int exit=proc.waitFor();if(cancelledByUser)return "INTERRUMPIDO: revisa el control B3 y reanuda pendientes.";
                    if(exit!=0||failed)return "ERROR O BLOQUEO: revisa el log B3: "+log;
                    if("config".equals(mode))return goodConfig?"CONFIGURACION B3 VALIDA (0 API / SQL)":"B3 NO CONFIRMADO: no se recibio validacion";
                    if("simulate".equals(mode)){
                        int expectedRows=(latest-oldest+1)*leagues.getSelectedValuesList().size();
                        if(!b3ControlSnapshotKey.equals(key))
                            return "B3 SIN CONTROL ACTUAL: primero Consultar pendientes B3; despues Simular B3.";
                        if(previewRows!=expectedRows || summaryByLeague.size()!=b3ControlByLeague.size())
                            return "B3 VERSION/RESUMEN INCOMPATIBLE: se esperaban "+expectedRows+
                                " registros B3_SEASON, llegaron "+previewRows+". Recompila totalcorner-downloader.";
                        for(Map.Entry<String,long[]> entry:b3ControlByLeague.entrySet()) {
                            long[] simulated=summaryByLeague.get(entry.getKey()),checked=entry.getValue();
                            if(simulated==null || !Arrays.equals(simulated,checked)) {
                                return "B3 INCONSISTENTE liga TC="+entry.getKey()+
                                    ": control="+Arrays.toString(checked)+
                                    ", simulacion="+Arrays.toString(simulated)+
                                    " [elegibles,DONE,pendientes]. No ejecutar. Generar CSV diagnostico B3.";
                            }
                        }
                        if(previewPending!=b3ControlSnapshotPending)
                            return "B3 INCONSISTENTE total: control="+b3ControlSnapshotPending+
                                " simulacion="+previewPending+". No ejecutar.";
                        if(!goodPreview)return "B3 NO CONFIRMADO: falta resumen de simulacion";
                        if(previewPending==0 && b3ControlSnapshotMissingJson>0)
                            return "B3 JOBS DONE PERO JSON NO CONFIRMADOS: "+b3ControlSnapshotMissingJson+
                                " partidos. No repetir cuotas sin diagnosticar las fuentes.";
                        if(previewPending==0)return "B3 SIN PENDIENTES: 0 trabajos por descargar. Continuar B4.";
                        return "SIMULACION B3 CONFIRMADA ("+previewPending+" pendientes en "+previewRows+" temporadas/ligas)";
                    }
                    return goodFinish?"B3 EJECUTADO: verificar pendientes y JSON; el final NO implica cobertura completa":"B3 SIN RESUMEN FINAL: revisar log";
                }catch(Exception ex){return "ERROR B3: "+ex.getMessage();}finally{child=null;}
            }
            @Override protected void process(List<String> lines){
                for(String l:lines)b3Output.append(l+"\n");
                if(b3Output.getDocument().getLength()>240000)b3Output.replaceRange("[Consola recortada; salida completa en log B3]\n",0,80000);
                b3Output.setCaretPosition(b3Output.getDocument().getLength());
            }
            @Override protected void done(){
                String result;try{result=get();}catch(Exception ex){result="ERROR B3: "+ex.getMessage();}
                b3Output.append("\n== "+result+" ==\n"); b3Guidance.setText(result);status.setText(result);
                if("simulate".equals(mode)&&result.startsWith("SIMULACION B3 CONFIRMADA")){
                    b3SimulatedKey=key;b3SimulatedPending=b3ControlSnapshotPending;
                }
                if(result.startsWith("B3 INCONSISTENTE")||result.startsWith("B3 VERSION/RESUMEN")){
                    markProgress("B3",WorkflowProgress.REVIEW,"Control B3 discrepante: "+result);
                    warn(result);
                }
                if("execute".equals(mode)){b3SimulatedKey="";b3SimulatedPending=-1;}
                setBusy(false);
                if("execute".equals(mode)&&result.startsWith("B3 EJECUTADO")){
                    markProgress("B3",WorkflowProgress.REVIEW,"Ejecucion real B3 confirmada; control de pendientes posterior necesario");
                    invalidateB4();runB3Control();
                }
            }
        }.execute();
    }

    private static final class B21Response {
        final List<String[]> rows = new ArrayList<String[]>();
        final List<String[]> details = new ArrayList<String[]>();
        int reportedRows = -1, reportedLeagues = -1, reportedDetails = -1;
        int safe, review, unmatched;
        String error;
    }

    private static final class ControlResponse {
        final List<String[]> rows = new ArrayList<String[]>();
        int received = -1;
        int expected = -1;
        String error;
    }

    /** Clave local del trabajo B1.2 validado: no confunde una seleccion parcial con el ciclo entero. */
    private String currentCycleKey() {
        StringJoiner ids = new StringJoiner(",");
        for (League league : leagues.getSelectedValuesList()) ids.add(league.id);
        return runId.getText().trim() + "|" + fromPage.getValue() + "|"
                + maxPages.getValue() + "|" + ids;
    }

    private boolean isB12ConfirmedForCurrentCycle() {
        return !lastConfirmedB12Key.isEmpty() && lastConfirmedB12Key.equals(currentCycleKey());
    }

    /** Siempre exige B1.2 tras el control inicial, incluso cuando MySQL indica cero pendientes. */
    private static String guidedNextStep(List<String[]> rows, boolean refreshedThisCycle) {
        if (!refreshedThisCycle) {
            return "B1.1 consultado. PASO SIGUIENTE OBLIGATORIO: ejecutar B1.2 (refrescar calendarios y marcadores). "
                    + "El resultado 'SIN PENDIENTES LOCALES' solo describe el estado conocido en MySQL; "
                    + "no sustituye el refresco de TotalCorner. Despues, volver a consultar B1.1.";
        }
        String suggested = nextStep(rows);
        if (suggested.startsWith("Siguiente paso: B1.2") ) {
            return "B1.2 verificado en este ciclo, pero B1.1 sigue indicando revisiones de pagina 1 pendientes: "
                    + suggested + " Comprueba la configuracion antes de avanzar.";
        }
        if (suggested.startsWith("B1.1 sin pendientes locales")) {
            return "B1.2 ejecutado y B1.1 consultado de nuevo. Sin pendientes locales conocidos en 60 dias. "
                    + "SIGUIENTE PASO: B2.1 - consultar candidatos de enlace (pestana 3). "
                    + "Despues, ejecutar B2.2 (si hay candidatos), B2.3 y B3; no declarar completo todo el ciclo.";
        }
        return "B1.2 ejecutado y B1.1 revisado. " + suggested
                + " SIGUIENTE PASO: B2.1 (pestana 3) antes de enlazar o descargar cuotas.";
    }

    private static void testGuidedFlow() {
        List<String[]> zeroRows = new ArrayList<String[]>();
        zeroRows.add(new String[]{"1", "England Premier League", "", "", "", "0", "0", "0", "SIN PENDIENTES LOCALES"});
        if (!guidedNextStep(zeroRows, false).contains("ejecutar B1.2"))
            throw new AssertionError("Debe recomendar B1.2 antes de refrescar, incluso con cero pendientes");
        if (!guidedNextStep(zeroRows, true).contains("SIGUIENTE PASO: B2"))
            throw new AssertionError("Debe indicar B2 tras refresco y nueva consulta");
        System.out.println("OK flujo guiado: pre-refresco B1.2 obligatorio; post-refresco B2");
    }

    private static String nextStep(List<String[]> rows) {
        int missing = 0, stale = 0, unlinked = 0, odds = 0;
        for (String[] row : rows) {
            if ("REVISAR: sin pagina 1".equals(row[8])) missing++;
            else if ("REFRESCAR CALENDARIO".equals(row[8])) stale++;
            unlinked += safeCount(row[5]);
            odds += safeCount(row[6]);
        }
        if (missing > 0)
            return "Siguiente paso: B1.2 / revisar pagina 1. " + missing
                    + " ligas no tienen revision exitosa registrada de la pagina 1. No seguir a B2 todavia.";
        if (stale > 0)
            return "Siguiente paso: B1.2. " + stale
                    + " ligas tienen la pagina 1 sin revisar en los ultimos cuatro dias. Comprueba despues los marcadores/conflictos.";
        if (unlinked > 0)
            return "Siguiente paso: B2.1 (pestana 3). Hay " + unlinked
                    + " partidos finalizados recientes sin enlace VERIFIED. Revisar candidatos y ambiguos; no enlazar solo por nombre.";
        if (odds > 0)
            return "Siguiente paso: B3 (pestana 6). Hay " + odds
                    + " partidos enlazados recientes sin ambos JSON de cuotas confirmados.";
        return "B1.1 sin pendientes locales conocidos (60 dias). Esto NO certifica B4 ni publicaciones: "
                + "aun hay que comprobar cobertura, ZIP, Coverage y WinSCP por separado.";
    }

    private static int safeCount(String count) {
        try { return Integer.parseInt(count); }
        catch (Exception e) { return 0; }
    }
    private static String htmlEscape(String input) {
        return input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
    static String managerCodeLocation() throws Exception {
        return Paths.get(TotalCornerManager.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
    }

    private void stopChild() {
        Process p = child;
        if (p == null) return;
        int answer = JOptionPane.showConfirmDialog(this,
                "Se interrumpira el proceso activo. Los trabajos ya confirmados permanecen en MySQL.\n"
                + "Para reanudar B1.2 conserva el MISMO ID de ciclo. B3 retoma trabajos pendientes. B4 reanuda por lotes.\n¿Detener?",
                "Detener proceso", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.YES_OPTION) {
            cancelledByUser = true;
            p.destroy();
            status.setText("Deteniendo proceso...");
        }
    }

    private Path settingsPath() { return Paths.get(System.getProperty("user.home"), SETTINGS); }
    private void loadSettings() {
        try {
            Path settingsFile = settingsPath();
            if (!Files.isRegularFile(settingsFile)) return;
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(settingsFile)) { p.load(in); }
            project.setText(p.getProperty("project", ""));
            java8.setText(p.getProperty("java8", ""));
            config.setText(p.getProperty("config", ""));
            runId.setText(p.getProperty("runId", ""));
            lastConfirmedB12Key = p.getProperty("b12ConfirmedKey", "");
            lastConfirmedB12At = p.getProperty("b12ConfirmedAt", "");
            if (dashboardPanel != null) dashboardPanel.refreshState();
            fromPage.setValue(Integer.parseInt(p.getProperty("fromPage", "1")));
            maxPages.setValue(Integer.parseInt(p.getProperty("maxPages", "3")));
            b21Tolerance.setValue(Integer.parseInt(p.getProperty("b21ToleranceMinutes", "5")));
            b22Tolerance.setValue(Integer.parseInt(p.getProperty("b22ToleranceMinutes", "5")));
            b4Limit.setValue(Integer.parseInt(p.getProperty("b4Limit", "5")));
            b4Minutes.setValue(Integer.parseInt(p.getProperty("b4Minutes", "15")));
        } catch (Exception ignore) { status.setText("No se pudo cargar la configuracion guardada; revisa los campos."); }
    }
    private void saveSettings() {
        Properties p = new Properties();
        p.setProperty("project", project.getText().trim());
        p.setProperty("java8", java8.getText().trim());
        p.setProperty("config", config.getText().trim());
        p.setProperty("runId", runId.getText().trim());
        p.setProperty("b12ConfirmedKey", lastConfirmedB12Key);
        p.setProperty("b12ConfirmedAt", lastConfirmedB12At);
        p.setProperty("fromPage", String.valueOf(fromPage.getValue()));
        p.setProperty("maxPages", String.valueOf(maxPages.getValue()));
        p.setProperty("b21ToleranceMinutes", String.valueOf(b21Tolerance.getValue()));
        p.setProperty("b22ToleranceMinutes", String.valueOf(b22Tolerance.getValue()));
        p.setProperty("b4Limit", String.valueOf(b4Limit.getValue()));
        p.setProperty("b4Minutes", String.valueOf(b4Minutes.getValue()));
        try (OutputStream out = Files.newOutputStream(settingsPath())) {
            p.store(out, "TotalCorner Manager: rutas y preferencias. NO guarda credenciales.");
        } catch (IOException e) { status.setText("Aviso: no se pudieron guardar preferencias (" + e.getMessage() + ")"); }
    }
}
