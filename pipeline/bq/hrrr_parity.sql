-- Phase 3 parity: Java/netCDF-Java pipeline vs the eccodes oracle, for one run.
-- Grid cell must match exactly; values must agree within half a packing step (2^E / 10^D),
-- the resolution at which the file stored them. Returns one row per difference; empty = parity.
-- ${DATASET} and ${RUN_ID} are substituted by scripts/deploy_phase3.sh.
WITH pipeline AS (
  SELECT p.station, p.valid_time, p.grid_i, p.grid_j, v.variable, v.value
  FROM `${DATASET}.hrrr_points` AS p,
    UNNEST([
      STRUCT('tmp_k' AS variable, p.tmp_k AS value),
      STRUCT('dpt_k' AS variable, p.dpt_k AS value),
      STRUCT('ugrd_grid' AS variable, p.ugrd_grid AS value),
      STRUCT('vgrd_grid' AS variable, p.vgrd_grid AS value),
      STRUCT('pres_pa' AS variable, p.pres_pa AS value)
    ]) AS v
  WHERE p.run_id = '${RUN_ID}'
),
reference AS (
  SELECT station, valid_time, variable, i, j, value, packing_step
  FROM `${DATASET}.hrrr_reference`
  WHERE valid_time IN (SELECT DISTINCT valid_time FROM pipeline)
),
joined AS (
  SELECT
    COALESCE(r.station, p.station) AS station,
    COALESCE(r.valid_time, p.valid_time) AS valid_time,
    COALESCE(r.variable, p.variable) AS variable,
    r.i AS ref_i, r.j AS ref_j, p.grid_i AS pipe_i, p.grid_j AS pipe_j,
    r.value AS ref_value, p.value AS pipe_value, r.packing_step,
    r.station IS NULL AS missing_in_reference, p.station IS NULL AS missing_in_pipeline
  FROM reference r
  FULL OUTER JOIN pipeline p
    ON r.station = p.station AND r.valid_time = p.valid_time AND r.variable = p.variable
)
SELECT
  station, CAST(valid_time AS STRING) AS valid_time, variable,
  CASE
    WHEN missing_in_pipeline THEN 'missing from pipeline'
    WHEN missing_in_reference THEN 'extra in pipeline'
    WHEN ref_i != pipe_i OR ref_j != pipe_j
      THEN FORMAT('grid cell differs: reference (%d,%d), pipeline (%d,%d)', ref_i, ref_j, pipe_i, pipe_j)
    WHEN (ref_value IS NULL) != (pipe_value IS NULL)
      THEN FORMAT('null mismatch: reference %t, pipeline %t', ref_value, pipe_value)
    ELSE FORMAT('value differs by %g (%.2f packing steps): reference %t, pipeline %t',
                ABS(ref_value - pipe_value), ABS(ref_value - pipe_value) / packing_step, ref_value, pipe_value)
  END AS issue
FROM joined
WHERE missing_in_pipeline OR missing_in_reference
   OR ref_i != pipe_i OR ref_j != pipe_j
   OR (ref_value IS NULL) != (pipe_value IS NULL)
   OR ABS(ref_value - pipe_value) > packing_step / 2
ORDER BY station, valid_time, variable
