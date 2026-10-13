-- Phase 2 parity, in BigQuery: pipeline output for one run vs the WXSUMM reference report.
--
-- The reference report is loaded unchanged, one line per row, into reference_summary_raw and
-- reference_errors_raw. It is parsed here by column position, the way a copybook describes a
-- fixed-width record. 'N/A' fails SAFE_CAST and becomes NULL, which is how the pipeline writes
-- "no valid observations".
--
-- Rows are joined on (station, obs_date), never compared by position: BigQuery has no row order.
-- IS DISTINCT FROM treats NULL = NULL as a match, so N/A vs NULL compares correctly.
--
-- Returns one row per difference; an empty result means parity. ${DATASET} and ${RUN_ID} are
-- substituted by scripts/deploy_phase2.sh.

WITH reference AS (
  SELECT
    SUBSTR(line, 1, 6)                                    AS station,
    PARSE_DATE('%Y%m%d', SUBSTR(line, 8, 8))              AS obs_date,
    CAST(TRIM(SUBSTR(line, 17, 3)) AS INT64)              AS nobs,
    CAST(TRIM(SUBSTR(line, 21, 3)) AS INT64)              AS ntemp,
    SAFE_CAST(TRIM(SUBSTR(line, 25, 6)) AS NUMERIC)       AS tmin,
    SAFE_CAST(TRIM(SUBSTR(line, 32, 6)) AS NUMERIC)       AS tmax,
    SAFE_CAST(TRIM(SUBSTR(line, 39, 6)) AS NUMERIC)       AS tmean,
    SAFE_CAST(TRIM(SUBSTR(line, 46, 6)) AS NUMERIC)       AS prcp,
    SAFE_CAST(TRIM(SUBSTR(line, 53, 5)) AS NUMERIC)       AS wmax,
    SAFE_CAST(TRIM(SUBSTR(line, 59, 6)) AS NUMERIC)       AS pmean
  FROM `${DATASET}.reference_summary_raw`
  WHERE NOT STARTS_WITH(line, 'STATN') AND NOT STARTS_WITH(line, '*TOTAL')
),
pipeline AS (
  SELECT station, obs_date, nobs, ntemp, tmin, tmax, tmean, prcp, wmax, pmean
  FROM `${DATASET}.summary`
  WHERE run_id = '${RUN_ID}'
),
summary_diffs AS (
  SELECT
    'summary' AS check_name,
    CONCAT(COALESCE(r.station, p.station), ' ', CAST(COALESCE(r.obs_date, p.obs_date) AS STRING)) AS item,
    CASE
      WHEN p.station IS NULL THEN 'missing from pipeline'
      WHEN r.station IS NULL THEN 'extra in pipeline'
      ELSE ARRAY_TO_STRING(ARRAY(
        SELECT name FROM UNNEST([
          IF(r.nobs  IS DISTINCT FROM p.nobs,  'nobs',  NULL),
          IF(r.ntemp IS DISTINCT FROM p.ntemp, 'ntemp', NULL),
          IF(r.tmin  IS DISTINCT FROM p.tmin,  'tmin',  NULL),
          IF(r.tmax  IS DISTINCT FROM p.tmax,  'tmax',  NULL),
          IF(r.tmean IS DISTINCT FROM p.tmean, 'tmean', NULL),
          IF(r.prcp  IS DISTINCT FROM p.prcp,  'prcp',  NULL),
          IF(r.wmax  IS DISTINCT FROM p.wmax,  'wmax',  NULL),
          IF(r.pmean IS DISTINCT FROM p.pmean, 'pmean', NULL)
        ]) AS name WHERE name IS NOT NULL), ', ') || ' differ'
    END AS issue
  FROM reference r
  FULL OUTER JOIN pipeline p
    ON r.station = p.station AND r.obs_date = p.obs_date
  WHERE p.station IS NULL OR r.station IS NULL
     OR r.nobs  IS DISTINCT FROM p.nobs  OR r.ntemp IS DISTINCT FROM p.ntemp
     OR r.tmin  IS DISTINCT FROM p.tmin  OR r.tmax  IS DISTINCT FROM p.tmax
     OR r.tmean IS DISTINCT FROM p.tmean OR r.prcp  IS DISTINCT FROM p.prcp
     OR r.wmax  IS DISTINCT FROM p.wmax  OR r.pmean IS DISTINCT FROM p.pmean
),
-- Error lines are compared as a multiset: same lines, same number of times, any order.
reference_errors AS (
  SELECT line, COUNT(*) AS n FROM `${DATASET}.reference_errors_raw` GROUP BY line
),
pipeline_errors AS (
  SELECT error_line AS line, COUNT(*) AS n
  FROM `${DATASET}.errors` WHERE run_id = '${RUN_ID}' GROUP BY line
),
error_diffs AS (
  SELECT
    'errors' AS check_name,
    COALESCE(r.line, p.line) AS item,
    FORMAT('reference x%d, pipeline x%d', IFNULL(r.n, 0), IFNULL(p.n, 0)) AS issue
  FROM reference_errors r
  FULL OUTER JOIN pipeline_errors p ON r.line = p.line
  WHERE IFNULL(r.n, 0) != IFNULL(p.n, 0)
)
SELECT * FROM summary_diffs
UNION ALL
SELECT * FROM error_diffs
ORDER BY check_name, item
