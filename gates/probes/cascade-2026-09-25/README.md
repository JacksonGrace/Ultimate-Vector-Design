# Cascade hunt probes (2026-09-25)

Research probes written by the bridge-cascade hunt (the user's 5 s-per-spinner-click report, DECISIONS D252).
One-off — NOT part of the core battery, not compiled by the gates/*.java line.
Each loads "UVD long bridge update time 9.25.26.uvd" (or builds fixtures), runs the shell's rebuild loop verbatim and prints per-pass signature-slot deltas + ms per simulated click.
Build one: javac -cp target/classes -d gates/out gates/probes/cascade-2026-09-25/<Name>.java ; run: java -cp "target/classes;gates/out" uvd.<Name>
The reusable timing probe is gates/BridgeSlowProbe.java (`fixed` mode = old-route vs new-route fixed-point check).
