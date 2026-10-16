"""Verify published core dependency boundaries without loading platform/Spring code."""
from pathlib import Path
import subprocess
result = subprocess.run(
    ["mvn", "-q", "-pl", "sdk-java/sdk-core", "dependency:tree", "-DoutputFile=target/core-dependencies.txt", "-Dscope=runtime"],
    check=True
)
tree = Path("sdk-java/sdk-core/target/core-dependencies.txt").read_text()
for forbidden in ("org.springframework", "org.hswebframework", "org.jetlinks:"):
    if forbidden in tree:
        raise SystemExit("Forbidden core dependency: " + forbidden)
print("PASS: core has no Spring/platform/hsweb dependencies")
