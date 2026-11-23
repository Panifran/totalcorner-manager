package com.footiqo.manager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** B2.2: simulacion obligatoria y aplicacion transaccional de enlaces 1:1.
 * Todos los pares detectados pueden aprobarse manualmente; los no seguros requieren confirmacion.
 * La escritura sigue bloqueando conflictos de enlace, duplicados e identidades de equipos distintas.
 * No altera ls_match ni tc_match: solo INSERT en tc_match_link.
 */
public final class B22LinkRunner {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final String[] LEAGUES = {"1", "14", "12", "16", "25", "137", "138", "2"};
    private B22LinkRunner() {}

    private static class Candidate {
        String tcId, liga, ls, tc, lsHome, lsAway, tcHome, tcAway, fechaLs, fechaTc, seconds, scoreLs, scoreTc, state;
        String key() { return ls + ":" + tc; }
        boolean safe() { return "LISTO_PARA_ENLAZAR".equals(state); }
        boolean extended() { return "REVISAR_TOLERANCIA_AMPLIADA".equals(state); }
        boolean missingTcScore() { return "TC_SIN_MARCADOR".equals(state); }
        boolean manual() { return !safe(); }
        String canonical() { return String.join("\u001f", Arrays.asList(tcId, liga, ls, tc, lsHome, lsAway, tcHome, tcAway, fechaLs, fechaTc, seconds, scoreLs, scoreTc, state)); }
        String printRow() { return "B22_ROW\t" + String.join("\t", Arrays.asList(liga, ls, tc, lsHome, lsAway, tcHome, tcAway, fechaLs, fechaTc, seconds, scoreLs, scoreTc, state)); }
    }
    public static void main(String[] args) {
        try {
            if (args.length == 1 && "--self-test".equals(args[0])) {
                if (B21PreviewRunner.parseTolerance(new String[] {"--league-ids=1", "--tolerance-minutes=14400"}) != 14400)
                    throw new IllegalStateException("Limite 14400 no aceptado");
                try { B21PreviewRunner.parseTolerance(new String[] {"--league-ids=1", "--tolerance-minutes=14401"});
                    throw new IllegalStateException("Acepto mas de 14400"); } catch (IllegalArgumentException ok) { }
                if (!query(1).contains("REVISAR_TOLERANCIA_AMPLIADA")
                        || !query(1).contains("TC_SIN_MARCADOR")
                        || !revalidateSql(false).contains("FOR UPDATE")
                        || !revalidateSql(true).contains("FOR UPDATE")
                        || revalidateSql(true).contains("tm.home_goals=lm.ftHomeTeamGoals")
                        || !revalidateSql(true).contains("<=60*?"))
                    throw new IllegalStateException("Protecciones SQL ausentes");
                if (!manualSelectionValid(Arrays.asList("15:99", "16:100")) ||
                        manualSelectionValid(Arrays.asList("15:99", "15:100")) ||
                        manualSelectionValid(Arrays.asList("15:99", "16:99")))
                    throw new IllegalStateException("Unicidad 1:1 manual no comprobada");
                System.out.println("B22_SELF_TEST_OK - todas las evaluaciones seleccionables, control 1:1 y validacion transaccional");
                return;
            }
            Map<String,String> options = new LinkedHashMap<String,String>();
            for (String arg : args) {
                int split = arg.indexOf('=');
                if (!arg.startsWith("--") || split < 3) throw new IllegalArgumentException("Parametro incorrecto");
                String key = arg.substring(2, split);
                if (options.put(key, arg.substring(split+1)) != null) throw new IllegalArgumentException("Parametro duplicado: " + key);
            }
            String mode = required(options, "mode");
            if (!"simulate".equals(mode) && !"apply".equals(mode)) throw new IllegalArgumentException("mode debe ser simulate o apply");
            String idsRaw = required(options, "league-ids");
            List<String> ids = B21PreviewRunner.parseIds(new String[] {"--league-ids="+idsRaw, "--tolerance-minutes=5"});
            int tolerance = B21PreviewRunner.parseTolerance(new String[] {"--league-ids="+idsRaw,
                    "--tolerance-minutes="+required(options,"tolerance-minutes")});
            String desde = required(options,"desde").replace('_',' '), hasta = required(options,"hasta").replace('_',' ');
            LocalDateTime d = LocalDateTime.parse(desde, FORMAT), h = LocalDateTime.parse(hasta, FORMAT);
            if (!d.isBefore(h) || h.isAfter(LocalDateTime.now(ZoneId.of("Europe/Madrid")).plusMinutes(10)))
                throw new IllegalArgumentException("Ventana de fechas invalida");
            if (d.isBefore(h.minusDays(61))) throw new IllegalArgumentException("B2.2 opera solo en ventana de hasta 60 dias");
            String password = System.getenv("TOTALCORNER_DB_PASSWORD");
            if (password == null || password.trim().isEmpty()) throw new IllegalArgumentException("Falta TOTALCORNER_DB_PASSWORD");
            String url = env("TOTALCORNER_DB_URL", "jdbc:mysql://localhost:3307/freefootstats?serverTimezone=UTC&useUnicode=true");
            String user = env("TOTALCORNER_DB_USER", "root");
            Class.forName("com.mysql.cj.jdbc.Driver");
            DriverManager.setLoginTimeout(12);
            try (Connection db = DriverManager.getConnection(url,user,password)) {
                checkTimezone(db,desde,hasta);
                if ("simulate".equals(mode)) {
                    db.setReadOnly(true);
                    List<Candidate> candidates = readCandidates(db,ids,desde,hasta,tolerance);
                    String hash = fingerprint(candidates);
                    int safe=0, extended=0, missingTc=0;
                    for (Candidate c : candidates) {
                        if (c.safe()) safe++;
                        if (c.extended()) extended++;
                        if (c.missingTcScore()) missingTc++;
                        System.out.println(c.printRow());
                    }
                    System.out.println("B22_DONE\t"+candidates.size()+"\t"+safe+"\t"+extended+"\t"+missingTc+"\t"+hash+"\t"+desde.replace(' ','_')+"\t"+hasta.replace(' ','_')+"\t"+tolerance);
                } else {
                    String expectedHash = required(options,"expected-hash");
                    if (!expectedHash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Hash de simulacion incorrecto");
                    int expectedCount = Integer.parseInt(required(options,"expected-count"));
                    List<String> selected = selected(required(options,"selected"));
                    if (expectedCount <= 0 || expectedCount != selected.size()) throw new IllegalArgumentException("Cantidad esperada distinta de seleccion");
                    boolean confirmedManual = "true".equals(options.get("confirmed-manual"));
                    apply(db,ids,desde,hasta,tolerance,expectedHash,selected,confirmedManual);
                }
            }
        } catch (Throwable error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            if (error instanceof SQLException) {
                SQLException sql = (SQLException)error;
                message = "MySQL SQLState=" + sql.getSQLState() + " codigo=" + sql.getErrorCode() + ": " + message;
            }
            System.err.println("B22_ERROR\t"+clean(message));
            System.exit(1);
        }
    }
    private static String required(Map<String,String> map, String name) {
        String s=map.get(name);
        if (s==null || s.trim().isEmpty()) throw new IllegalArgumentException("Falta --"+name);
        return s;
    }
    private static List<String> selected(String raw) {
        List<String> result = new ArrayList<String>();
        Set<String> dedup = new HashSet<String>();
        for (String key : raw.split(",",-1)) {
            if (!key.matches("[0-9]+:[0-9]+") || !dedup.add(key)) throw new IllegalArgumentException("Pareja duplicada o invalida");
            result.add(key);
        }
        return result;
    }
    private static boolean manualSelectionValid(List<String> keys) {
        Set<String> ls = new HashSet<String>(), tc = new HashSet<String>();
        for (String key : keys) {
            String[] both = key.split(":",-1);
            if (both.length != 2 || !ls.add(both[0]) || !tc.add(both[1])) return false;
        }
        return true;
    }
    private static String env(String name,String fallback) { String x=System.getenv(name);return x==null||x.trim().isEmpty()?fallback:x; }
    private static String clean(String s) { return s==null?"":s.replace('\t',' ').replace('\r',' ').replace('\n',' '); }

    private static void checkTimezone(Connection db,String desde,String hasta) throws SQLException {
        try (PreparedStatement ps=db.prepareStatement(
                "SELECT CONVERT_TZ('2026-11-01 12:00:00','+00:00','Europe/Madrid')," +
                "CONVERT_TZ(CAST(? AS DATETIME),'Europe/Madrid','+00:00')," +
                "CONVERT_TZ(CAST(? AS DATETIME),'Europe/Madrid','+00:00')")) {
            ps.setString(1,desde); ps.setString(2,hasta);
            try(ResultSet rs=ps.executeQuery()) {
                if(!rs.next()||rs.getString(1)==null||rs.getString(2)==null||rs.getString(3)==null)
                    throw new IllegalStateException("Zona horaria Europe/Madrid no validada, cancelado");
            }
        }
    }
    private static String query(int count) {
        return B21PreviewRunner.ctes(count) +
                "SELECT e.tc_id,e.liga,e.ls_match_id,e.tc_match_pk,"+
                " sh.name AS ls_home,sa.name AS ls_away,tm.home_team_name AS tc_home,tm.away_team_name AS tc_away,"+
                " CAST(e.fecha_footiqo AS CHAR) AS fecha_footiqo,CAST(e.fecha_totalcorner AS CHAR) AS fecha_totalcorner,"+
                " e.diferencia_segundos,e.ls_home_goals,e.ls_away_goals,e.tc_home_goals,e.tc_away_goals,e.estado"+
                " FROM evaluados e JOIN ls_match lm ON lm.id=e.ls_match_id"+
                " LEFT JOIN ls_team sh ON sh.id=lm.lsHomeTeam_id"+
                " LEFT JOIN ls_team sa ON sa.id=lm.lsAwayTeam_id"+
                " JOIN tc_match tm ON tm.id=e.tc_match_pk"+
                " ORDER BY e.liga,e.fecha_footiqo,e.ls_match_id,e.tc_match_pk";
    }
    private static List<Candidate> readCandidates(Connection db,List<String> ids,String desde,String hasta,int tolerance) throws SQLException {
        List<Candidate> list = new ArrayList<Candidate>();
        try (PreparedStatement ps = db.prepareStatement(query(ids.size()))) {
            ps.setQueryTimeout(180);
            int p=1;
            for (String id:ids) ps.setString(p++,id);
            ps.setString(p++,desde); ps.setString(p++,hasta); ps.setInt(p++,tolerance);
            try(ResultSet rs=ps.executeQuery()) {
                while(rs.next()) {
                    Candidate c=new Candidate();
                    c.tcId=clean(rs.getString(1)); c.liga=clean(rs.getString(2));
                    c.ls=clean(rs.getString(3)); c.tc=clean(rs.getString(4));
                    c.lsHome=clean(rs.getString(5)); c.lsAway=clean(rs.getString(6));
                    c.tcHome=clean(rs.getString(7)); c.tcAway=clean(rs.getString(8));
                    c.fechaLs=clean(rs.getString(9)); c.fechaTc=clean(rs.getString(10));
                    c.seconds=clean(rs.getString(11));
                    c.scoreLs=clean(rs.getString(12))+"-"+clean(rs.getString(13));
                    c.scoreTc=clean(rs.getString(14))+"-"+clean(rs.getString(15));
                    c.state=clean(rs.getString(16));
                    list.add(c);
                }
            }
        }
        return list;
    }
    private static String fingerprint(List<Candidate> candidates) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        for(Candidate c:candidates){ md.update(c.canonical().getBytes(StandardCharsets.UTF_8));md.update((byte)10); }
        StringBuilder hex=new StringBuilder(); for(byte b:md.digest())hex.append(String.format("%02x",b&0xff));
        return hex.toString();
    }
    // Para pares manuales solo exigimos identidad (liga y equipos), ventana temporal,
    // ausencia de enlaces preexistentes y ausencia de colisiones 1:1. No exigimos
    // que goles, unicidad de candidatos o etiqueta DST sean correctos: eso se revisa manualmente.
    private static String revalidateSql(boolean manual) {
        return "SELECT lm.id FROM ls_match lm"+
                " JOIN ls_league_season lls ON lls.id=lm.lsLeague_season_id"+
                " JOIN tc_match tm ON tm.id=?"+
                " JOIN tc_league tl ON tl.id=tm.league_id"+
                " WHERE lm.id=? AND lm.pendingFinalResult=b'0'"+
                " AND lm.matchDate>=CAST(? AS DATETIME) AND lm.matchDate<CAST(? AS DATETIME)"+
                " AND ABS(TIMESTAMPDIFF(SECOND,lm.matchDate,CONVERT_TZ(tm.kickoff_utc,'+00:00','Europe/Madrid')))<=60*?"+
                (manual ? "" :
                    " AND tm.kickoff_timezone_status='PROFILE_VERIFIED'"+
                    " AND lm.ftHomeTeamGoals IS NOT NULL AND lm.ftAwayTeamGoals IS NOT NULL"+
                    " AND tm.home_goals IS NOT NULL AND tm.away_goals IS NOT NULL"+
                    " AND lm.ftHomeTeamGoals=tm.home_goals AND lm.ftAwayTeamGoals=tm.away_goals")+
                " AND tl.tc_league_id COLLATE utf8mb4_unicode_ci="+
                " (CASE lls.league_id WHEN 2 THEN '1' WHEN 1 THEN '14' WHEN 3 THEN '12'"+
                " WHEN 4 THEN '16' WHEN 5 THEN '25' WHEN 18 THEN '137' WHEN 28 THEN '138'"+
                " WHEN 6 THEN '2' ELSE 'UNKNOWN' END) COLLATE utf8mb4_unicode_ci"+
                " AND EXISTS(SELECT 1 FROM tc_team_link ht WHERE ht.tc_team_pk=tm.home_team_id"+
                " AND ht.ls_team_id=lm.lsHomeTeam_id AND ht.review_status='VERIFIED')"+
                " AND EXISTS(SELECT 1 FROM tc_team_link at WHERE at.tc_team_pk=tm.away_team_id"+
                " AND at.ls_team_id=lm.lsAwayTeam_id AND at.review_status='VERIFIED')"+
                " AND NOT EXISTS(SELECT 1 FROM tc_match_link link1 WHERE link1.ls_match_id=lm.id OR link1.tc_match_pk=tm.id)"+
                " FOR UPDATE";
    }
    private static void apply(Connection db,List<String> ids,String desde,String hasta,int tolerance,String expectedHash,
                              List<String> selection,boolean confirmedManual) throws Exception {
        if (selection.size()>1000) throw new IllegalArgumentException("Por seguridad, maximo 1000 enlaces por ejecucion");
        boolean locked=false;
        try (Statement st=db.createStatement();ResultSet rs=st.executeQuery("SELECT GET_LOCK('footiqo_tc_b22_apply',0)")) {
            if (!rs.next() || rs.getInt(1)!=1) throw new IllegalStateException("Otro proceso B2.2 esta aplicando enlaces");
            locked=true;
        }
        if (!locked) throw new IllegalStateException("No se pudo bloquear B2.2");
        try {
            db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            db.setAutoCommit(false);
            try {
                // Recalcular la propuesta dentro de la transaccion; nada se aplica si cambio desde la simulacion.
                List<Candidate> current=readCandidates(db,ids,desde,hasta,tolerance);
                if (!expectedHash.equals(fingerprint(current))) throw new IllegalStateException("La propuesta ha cambiado desde la simulacion. Repite B2.2");
                Map<String,Candidate> found=new HashMap<String,Candidate>();
                for(Candidate c:current) if(found.put(c.key(),c)!=null) throw new IllegalStateException("Pares duplicados en simulacion");
                if (!manualSelectionValid(selection))
                    throw new IllegalStateException("Hay dos parejas seleccionadas para el mismo LS o TC; deja solo una por partido");
                int safe=0,extended=0,missingTc=0,manualCount=0;
                for(String key:selection) {
                    Candidate c=found.get(key);
                    if(c==null) throw new IllegalStateException("Pareja no encontrada en la nueva simulacion: "+key);
                    if(c.manual()) {
                        manualCount++;
                        if(!confirmedManual) throw new IllegalStateException("Falta aprobacion manual de "+manualCount+" parejas revisadas");
                        if(c.extended()) extended++;
                        if(c.missingTcScore()) missingTc++;
                    } else safe++;
                }
                try (PreparedStatement lockNormal=db.prepareStatement(revalidateSql(false));
                     PreparedStatement lockManual=db.prepareStatement(revalidateSql(true));
                     PreparedStatement insert=db.prepareStatement("INSERT INTO tc_match_link " +
                           "(tc_match_pk,ls_match_id,match_method,confidence,review_status,checked_at)"+
                           " VALUES (?,?,?,NULL,'VERIFIED',UTC_TIMESTAMP(3))")) {
                    lockNormal.setQueryTimeout(90); lockManual.setQueryTimeout(90); insert.setQueryTimeout(90);
                    // Revalidar todo antes de insertar; una pareja ya enlazada a otro partido se rechaza.
                    for(String key:selection) {
                        Candidate c=found.get(key);
                        PreparedStatement lock = c.manual()?lockManual:lockNormal;
                        lock.setLong(1,Long.parseLong(c.tc)); lock.setLong(2,Long.parseLong(c.ls));
                        lock.setString(3,desde);lock.setString(4,hasta);lock.setInt(5,tolerance);
                        try(ResultSet rs=lock.executeQuery()) {
                            if(!rs.next() || rs.next())
                                throw new IllegalStateException("Conflicto de enlace, equipos o fechas para LS:TC "+key+
                                        ". Comprueba si alguno ya esta enlazado");
                        }
                    }
                    int inserted=0;
                    for(String key:selection) {
                        Candidate c=found.get(key);
                        int secs=Math.abs(Integer.parseInt(c.seconds));
                        String method=c.manual()?"MATCH_MANUAL_REVIEW":
                            secs<=300?"MATCH_5MIN_SCORE":secs<=900?"MATCH_15MIN_SCORE":"MATCH_EXT_SCORE_REVIEW";
                        insert.setLong(1,Long.parseLong(c.tc));insert.setLong(2,Long.parseLong(c.ls));insert.setString(3,method);
                        inserted+=insert.executeUpdate();
                    }
                    if(inserted!=selection.size()) throw new IllegalStateException("Insertados diferentes de los esperados");
                    db.commit();
                    // Auditoria de parejas revisadas, emitida SOLO despues del COMMIT.
                    for (String key : selection) {
                        Candidate c = found.get(key);
                        if (c.manual()) System.out.println("B22_MANUAL_LINK\t" + c.ls + "\t" + c.tc + "\t"
                                + clean(c.state) + "\t" + clean(c.scoreLs) + "\t" + clean(c.scoreTc));
                    }
                    System.out.println("B22_APPLIED\t"+inserted+"\t"+safe+"\t"+extended+"\t"+missingTc+"\t"+manualCount+"\t"+expectedHash);
                }
            } catch(Exception e) { db.rollback(); throw e; }
            finally { db.setAutoCommit(true); }
        } finally {
            try(Statement st=db.createStatement()) { st.execute("SELECT RELEASE_LOCK('footiqo_tc_b22_apply')"); } catch(SQLException ignore) { }
        }
    }
}
