package com.footiqo.manager;

import java.sql.*;
import java.util.*;

/** Inserta temporadas LS faltantes para las TC seleccionadas en B4.
 * No actualiza filas existentes, incluidas las deshabilitadas.
 * Requiere la conexion existente de B4; no realiza solicitudes API.
 */
public final class CovScopeSynchronizer {
    private CovScopeSynchronizer() { }

    private static final String INSERT_SQL =
        "INSERT INTO tc_cov_scope_season " +
        "(ls_league_season_id,enabled,priority,notes,created_at,updated_at) " +
        "SELECT ss.id,1,1000+ss.id,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) " +
        "FROM ls_league_season ss " +
        "WHERE ss.league_id=? AND NOT EXISTS (" +
        "SELECT 1 FROM tc_cov_scope_season c WHERE c.ls_league_season_id=ss.id)";

    public static int synchronize(Connection db,List<String> selectedTcIds) throws SQLException {
        if(selectedTcIds==null||selectedTcIds.isEmpty())
            throw new IllegalArgumentException("No hay ligas TC seleccionadas");
        // Se consulta el registro del gestor, sin depender de nombres ni deducir asociaciones.
        Map<String,LeagueMappingRegistry.Association> registered=LeagueMappingRegistry.load();
        Set<Integer> lsIds=new LinkedHashSet<Integer>();
        for(String tc:selectedTcIds) {
            LeagueMappingRegistry.Association a=registered.get(tc);
            if(a==null||!a.updates)
                throw new IllegalStateException("Liga TC "+tc+" sin asociacion LS activa en Bloque 2");
            lsIds.add(Integer.valueOf(a.lsId));
        }
        boolean originalAutoCommit=db.getAutoCommit();
        if(!originalAutoCommit)
            throw new SQLException("La sincronizacion necesita una conexion con autocommit habilitado");
        db.setAutoCommit(false);
        try {
            int inserted=0;
            try(PreparedStatement ps=db.prepareStatement(INSERT_SQL)) {
                ps.setQueryTimeout(30);
                for(Integer ls:lsIds) {
                    ps.setString(1,"Auto B4: liga LS "+ls+" (actualizacion activa)");
                    ps.setInt(2,ls);
                    inserted+=ps.executeUpdate();
                }
            }
            db.commit();
            return inserted;
        } catch(SQLException|RuntimeException ex) {
            try { db.rollback(); } catch(SQLException rollback) { ex.addSuppressed(rollback); }
            throw ex;
        } finally {
            db.setAutoCommit(true);
        }
    }

    public static void main(String[] args) {
        if(args.length!=1||!"--self-test".equals(args[0]))throw new IllegalArgumentException("--self-test");
        if(!INSERT_SQL.contains("NOT EXISTS")||INSERT_SQL.contains("ON DUPLICATE KEY UPDATE")||
                !INSERT_SQL.contains("ss.league_id=?"))throw new AssertionError("SQL inseguro");
        System.out.println("B4_SCOPE_SELF_TEST_OK");
    }
}
