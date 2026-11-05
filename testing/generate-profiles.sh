#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Only the isolated .benchmark package is installed; never the main app/data.
./gradlew :benchmark:connectedProfileAndroidTest \
 -Pandroid.testInstrumentationRunnerArguments.class=com.termllm.benchmark.GenerateBaselineProfile \
 -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
python3 - <<'INNER'
from pathlib import Path
files=list(Path('benchmark/build/outputs/connected_android_test_additional_output/profile').rglob('GenerateBaselineProfile_startup-startup-prof.txt'))
assert len(files)==1, 'Run with exactly one owned emulator'
text=files[0].read_text();assert 'Lcom/termllm/app/' in text
Path('app/src/main/baseline-prof.txt').write_text(text)
Path('app/src/main/startup-prof.txt').write_text(text)
INNER
