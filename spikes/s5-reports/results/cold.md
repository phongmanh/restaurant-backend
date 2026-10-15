### cold --source raw --days 31 --idle 360 2026-10-14T07:28:34.589Z
| Round | Connect + first SELECT 1 | Report in PostgreSQL, cold | Pages read from storage | Same report again, warm | Pages read |
|---|---|---|---|---|---|
| 1 | 1770 ms | 1242 ms | 3995 | 362 ms | 0 |
| 2 | 1121 ms | 2436 ms | 3981 | 342 ms | 0 |
### cold --source agg --days 31 --idle 360 2026-10-14T07:40:43.745Z
| Round | Connect + first SELECT 1 | Report in PostgreSQL, cold | Pages read from storage | Same report again, warm | Pages read |
|---|---|---|---|---|---|
| 1 | 1738 ms | 76 ms | 515 | 10 ms | 0 |
