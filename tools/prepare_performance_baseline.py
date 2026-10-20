"""Overlay only the instrumentation harness onto the unchanged v2.7 app sources."""
from pathlib import Path
import shutil
import sys

root = Path(__file__).resolve().parent.parent
baseline = Path(sys.argv[1])
shutil.copytree(root / 'app/src/androidTest', baseline / 'app/src/androidTest', dirs_exist_ok=True)
build = baseline / 'app/build.gradle.kts'
s = build.read_text()
s = s.replace('buildFeatures {', 'defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }\n    buildFeatures {', 1)
# Keep v2.7's debuggable flag and every production source unchanged.
current = (root / 'app/build.gradle.kts').read_text()
deps = '\n'.join(line for line in current.splitlines() if 'androidTestImplementation(' in line)
s = s.replace('dependencies {', 'dependencies {\n' + deps, 1)
build.write_text(s)
