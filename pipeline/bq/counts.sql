-- Row counts behind the parity check, for one run. ${DATASET} and ${RUN_ID} are substituted.
SELECT 'reference summary rows' AS what,
       COUNTIF(NOT STARTS_WITH(line, 'STATN') AND NOT STARTS_WITH(line, '*TOTAL')) AS n
FROM `${DATASET}.reference_summary_raw`
UNION ALL
SELECT 'pipeline summary rows', COUNT(*) FROM `${DATASET}.summary` WHERE run_id = '${RUN_ID}'
UNION ALL
SELECT 'reference error rows', COUNT(*) FROM `${DATASET}.reference_errors_raw`
UNION ALL
SELECT 'pipeline error rows', COUNT(*) FROM `${DATASET}.errors` WHERE run_id = '${RUN_ID}'
ORDER BY what
