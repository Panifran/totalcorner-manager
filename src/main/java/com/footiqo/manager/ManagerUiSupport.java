package com.footiqo.manager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.TableModel;
import java.awt.*;
import java.text.NumberFormat;
import java.util.*;

/** Componentes comunes de estado y totales. Java 8. Sin alterar los modelos de datos. */
public final class ManagerUiSupport {
    private ManagerUiSupport() {}
    private static final Set<String> SUMMABLE = new HashSet<String>(Arrays.asList(
            "sin enlace (60d)","sin cuotas (60d)","total", "sin enlace", "listos", "revision", "sin candidato",
            "ls sin verificar", "con excepcion", "tc con goles sin verificar", "candidatos", "ls sin candidato", "tc sin candidato",
            "elegibles", "dos jobs done", "partidos pendientes", "peticiones pendientes", "dos json ok", "tc no final",
            "en alcance", "cobertura al dia", "pendiente / desactualizada", "procesables b4", "esperando json b3", "bloqueados b4",
            "partidos footiqo", "finalizados", "partidos", "numero de parejas / partidos"));

    /** Relleno visible debajo de la tabla, incluso al desplazar/ordenar las filas. */
    public static JPanel withTotals(final JTable table) {
        final JPanel result = new JPanel(new BorderLayout(0, 0));
        final JScrollPane scroll = new JScrollPane(table);
        final JTextArea footer = new JTextArea(2, 80);
        footer.setEditable(false);
        footer.setLineWrap(true);
        footer.setWrapStyleWord(true);
        footer.setFocusable(false);
        footer.setFont(footer.getFont().deriveFont(Font.BOLD, 12f));
        footer.setBackground(UIManager.getColor("Panel.background"));
        footer.setBorder(new EmptyBorder(4, 6, 4, 6));
        footer.setRows(2);
        final Runnable refresh = new Runnable() {
            @Override public void run() {
                TableModel model = table.getModel();
                int rows = table.getRowCount();
                StringBuilder totals = new StringBuilder("TOTAL: ").append(rows).append(rows == 1 ? " fila" : " filas");
                int selected = 0;
                int checkColumn = -1;
                for (int c = 0; c < model.getColumnCount(); c++) {
                    String header = String.valueOf(model.getColumnName(c)).trim().toLowerCase(Locale.ROOT);
                    if (header.equals("aplicar") || header.equals("enlazar") || header.equals("publicar")) {
                        checkColumn = c;
                    }
                }
                if (checkColumn >= 0) {
                    for (int r = 0; r < model.getRowCount(); r++)
                        if (Boolean.TRUE.equals(model.getValueAt(r, checkColumn))) selected++;
                    totals.append(" | Marcados: ").append(selected);
                }
                NumberFormat fmt = NumberFormat.getIntegerInstance(new Locale("es", "ES"));
                for (int c = 0; c < model.getColumnCount(); c++) {
                    String header = String.valueOf(model.getColumnName(c)).trim();
                    if (!SUMMABLE.contains(header.toLowerCase(Locale.ROOT))) continue;
                    long sum = 0;
                    boolean seen = false;
                    for (int r = 0; r < model.getRowCount(); r++) {
                        Object cell = model.getValueAt(r, c);
                        if (cell == null) continue;
                        String val = cell.toString().trim();
                        if (!val.matches("-?[0-9]+")) continue;
                        try {sum = Math.addExact(sum, Long.parseLong(val)); seen = true;}
                        catch (ArithmeticException ignored) {seen = false; break;}
                    }
                    if (seen) totals.append(" | ").append(header).append(": ").append(fmt.format(sum));
                }
                footer.setText(totals.toString());
                footer.setCaretPosition(0);
            }
        };
        table.getModel().addTableModelListener(new TableModelListener() {
            @Override public void tableChanged(TableModelEvent e) {refresh.run();}
        });
        table.getRowSorter();
        table.addPropertyChangeListener("rowSorter", e -> refresh.run());
        if(table.getRowSorter()!=null) table.getRowSorter().addRowSorterListener(e -> refresh.run());
        result.add(scroll, BorderLayout.CENTER);
        result.add(footer, BorderLayout.SOUTH);
        refresh.run();
        return result;
    }

    /** Tooltip visible al pasar el cursor y estado consultable para lectores de pantalla. */
    public static void gate(JButton button, boolean enabled, String reason) {
        button.setEnabled(enabled);
        String tip = enabled ? "Listo para ejecutar" : reason;
        button.setToolTipText(tip);
        button.getAccessibleContext().setAccessibleDescription(tip);
    }
}
