/* A5.2: read-only team suggestions based on unique fixtures.
   Parameter order: tc ID, from, to, ls ID, from, to, tolerance, tolerance.
   Source: A5.2 operating guide. No permanent writes. */
WITH
 tc_scope AS (
   SELECT m.id, m.home_team_id, m.away_team_id, m.kickoff_utc,
          m.home_goals, m.away_goals, m.kickoff_timezone_status
   FROM freefootstats.tc_match m
   JOIN freefootstats.tc_league l ON l.id=m.league_id
   WHERE l.tc_league_id=?
     AND m.kickoff_utc>=CONVERT_TZ(?,'Europe/Madrid','+00:00')
     AND m.kickoff_utc<CONVERT_TZ(?,'Europe/Madrid','+00:00')
 ),
 tc_teams AS (
   SELECT home_team_id AS tc_team_pk FROM tc_scope WHERE home_team_id IS NOT NULL
   UNION
   SELECT away_team_id FROM tc_scope WHERE away_team_id IS NOT NULL
 ),
 ls_finals AS (
   SELECT lm.id,lm.matchDate,lm.lsHomeTeam_id,lm.lsAwayTeam_id,
          lm.ftHomeTeamGoals,lm.ftAwayTeamGoals
   FROM freefootstats.ls_match lm
   JOIN freefootstats.ls_league_season s ON s.id=lm.lsLeague_season_id
   WHERE s.league_id=? AND lm.matchDate>=? AND lm.matchDate<?
     AND lm.pendingFinalResult=b'0'
     AND lm.ftHomeTeamGoals IS NOT NULL AND lm.ftAwayTeamGoals IS NOT NULL
 ),
 ls_teams AS (
   SELECT lsHomeTeam_id AS ls_team_id FROM ls_finals WHERE lsHomeTeam_id IS NOT NULL
   UNION
   SELECT lsAwayTeam_id FROM ls_finals WHERE lsAwayTeam_id IS NOT NULL
 ),
 candidate_fixture AS (
   SELECT t.id AS tc_match_pk,f.id AS ls_match_id,
          t.home_team_id AS tc_home_id,t.away_team_id AS tc_away_id,
          f.lsHomeTeam_id AS ls_home_id,f.lsAwayTeam_id AS ls_away_id
   FROM tc_scope t
   JOIN ls_finals f ON f.matchDate BETWEEN
       CONVERT_TZ(t.kickoff_utc,'+00:00','Europe/Madrid') - INTERVAL ? MINUTE
       AND CONVERT_TZ(t.kickoff_utc,'+00:00','Europe/Madrid') + INTERVAL ? MINUTE
     AND f.ftHomeTeamGoals=t.home_goals AND f.ftAwayTeamGoals=t.away_goals
   WHERE t.home_team_id IS NOT NULL AND t.away_team_id IS NOT NULL
     AND t.home_goals IS NOT NULL AND t.away_goals IS NOT NULL
     AND t.kickoff_timezone_status='PROFILE_VERIFIED'
 ),
 tc_fixture_counts AS (
   SELECT tc_match_pk,COUNT(*) AS n FROM candidate_fixture GROUP BY tc_match_pk
 ),
 ls_fixture_counts AS (
   SELECT ls_match_id,COUNT(*) AS n FROM candidate_fixture GROUP BY ls_match_id
 ),
 unique_fixture AS (
   SELECT f.* FROM candidate_fixture f
   JOIN tc_fixture_counts x ON x.tc_match_pk=f.tc_match_pk AND x.n=1
   JOIN ls_fixture_counts y ON y.ls_match_id=f.ls_match_id AND y.n=1
   LEFT JOIN freefootstats.tc_match_link m_tc ON m_tc.tc_match_pk=f.tc_match_pk
   LEFT JOIN freefootstats.tc_match_link m_ls ON m_ls.ls_match_id=f.ls_match_id
   WHERE (m_tc.tc_match_pk IS NULL OR
          (m_tc.review_status='VERIFIED' AND m_tc.ls_match_id=f.ls_match_id))
     AND (m_ls.ls_match_id IS NULL OR
          (m_ls.review_status='VERIFIED' AND m_ls.tc_match_pk=f.tc_match_pk))
 ),
 team_evidence AS (
   SELECT tc_match_pk,ls_match_id,tc_home_id AS tc_team_pk,ls_home_id AS ls_team_id
   FROM unique_fixture WHERE tc_home_id IS NOT NULL AND ls_home_id IS NOT NULL
   UNION ALL
   SELECT tc_match_pk,ls_match_id,tc_away_id,ls_away_id
   FROM unique_fixture WHERE tc_away_id IS NOT NULL AND ls_away_id IS NOT NULL
 ),
 proposals AS (
   SELECT tc_team_pk,ls_team_id,COUNT(DISTINCT tc_match_pk) AS partidos_evidencia,
          SUBSTRING_INDEX(GROUP_CONCAT(DISTINCT CONCAT(tc_match_pk,':',ls_match_id)
                 ORDER BY tc_match_pk SEPARATOR '|'),'|',5) AS ejemplos_tc_ls
   FROM team_evidence GROUP BY tc_team_pk,ls_team_id
 ),
 rivals_tc AS (
   SELECT tc_team_pk,COUNT(*) AS candidatos_ls FROM proposals GROUP BY tc_team_pk
 ),
 rivals_ls AS (
   SELECT ls_team_id,COUNT(*) AS candidatos_tc FROM proposals GROUP BY ls_team_id
 )
SELECT t.id AS tc_team_pk,t.tc_team_id AS id_equipo_api,t.name AS equipo_tc,
       p.ls_team_id,ls.name AS equipo_footiqo,
       COALESCE(p.partidos_evidencia,0) AS partidos_evidencia,
       COALESCE(rt.candidatos_ls,0) AS candidatos_ls_para_tc,
       COALESCE(rl.candidatos_tc,0) AS candidatos_tc_para_ls,
       CASE WHEN p.ls_team_id IS NULL THEN 'SIN_CANDIDATO_POR_PARTIDOS'
            WHEN rt.candidatos_ls>1 THEN 'AMBIGUO_REVISAR'
            WHEN rl.candidatos_tc>1 THEN 'POSIBLES_ALIAS_TC_REVISAR'
            WHEN p.partidos_evidencia>=3 THEN 'CANDIDATO_CON_EVIDENCIA'
            ELSE 'EVIDENCIA_LIMITADA_REVISAR' END AS evaluacion,
       COALESCE(p.ejemplos_tc_ls,'') AS ejemplos_tc_ls
FROM tc_teams ts JOIN freefootstats.tc_team t ON t.id=ts.tc_team_pk
LEFT JOIN proposals p ON p.tc_team_pk=t.id
LEFT JOIN freefootstats.ls_team ls ON ls.id=p.ls_team_id
LEFT JOIN rivals_tc rt ON rt.tc_team_pk=t.id
LEFT JOIN rivals_ls rl ON rl.ls_team_id=p.ls_team_id
WHERE NOT EXISTS (
 SELECT 1 FROM freefootstats.tc_team_link tl
 JOIN ls_teams sl ON sl.ls_team_id=tl.ls_team_id
 WHERE tl.tc_team_pk=t.id AND tl.review_status='VERIFIED'
)
ORDER BY t.name,p.partidos_evidencia DESC,ls.name,t.id
