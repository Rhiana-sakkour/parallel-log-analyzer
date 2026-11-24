These files were produced in the development sandbox, which has ONE logical processor (1 vCPU).
They prove that the whole pipeline (tests -> benchmark -> figures -> report) works and they are REAL measurements of that machine,
but a 1-core machine cannot show any speedup (threads are only time-sliced), so they are NOT suitable for submission.
Run scripts/run_all.sh on your own multi-core machine: it fills ../results and generates ../report/report.docx from YOUR numbers.
