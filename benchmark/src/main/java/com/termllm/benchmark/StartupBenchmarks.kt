package com.termllm.benchmark
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {
 @get:Rule val rule=MacrobenchmarkRule()
 @Test fun coldStartup() = rule.measureRepeated(
  packageName="com.termllm.app.benchmark",metrics=listOf(StartupTimingMetric(),FrameTimingMetric()),
  compilationMode=CompilationMode.None(),startupMode=StartupMode.COLD,iterations=3,
  setupBlock={pressHome()},measureBlock={startActivityAndWait();device.waitForIdle()},
 )
}
@RunWith(AndroidJUnit4::class)
class GenerateBaselineProfile {
 @get:Rule val rule=BaselineProfileRule()
 @Test fun startup()=rule.collect(packageName="com.termllm.app.benchmark",includeInStartupProfile=true) {
  pressHome();startActivityAndWait();device.waitForIdle()
 }
}
