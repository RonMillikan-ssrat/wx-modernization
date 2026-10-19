-- Rows behind the Phase 3 parity check, and the largest disagreement in packing steps.
WITH pipeline AS (
  SELECT p.station, p.valid_time, v.variable, v.value
  FROM `${DATASET}.hrrr_points` AS p,
    UNNEST([STRUCT('tmp_k' AS variable, p.tmp_k AS value), STRUCT('dpt_k', p.dpt_k),
            STRUCT('ugrd_grid', p.ugrd_grid), STRUCT('vgrd_grid', p.vgrd_grid),
            STRUCT('pres_pa', p.pres_pa)]) AS v
  WHERE p.run_id = '${RUN_ID}'
)
SELECT
  COUNT(DISTINCT CONCAT(p.station, CAST(p.valid_time AS STRING))) AS station_hours,
  COUNT(*) AS values_compared,
  COUNT(DISTINCT p.station) AS stations,
  MIN(p.valid_time) AS first_valid_time,
  MAX(p.valid_time) AS last_valid_time,
  MAX(ABS(r.value - p.value) / r.packing_step) AS max_diff_in_packing_steps
FROM pipeline p
JOIN `${DATASET}.hrrr_reference` r
  ON r.station = p.station AND r.valid_time = p.valid_time AND r.variable = p.variable
