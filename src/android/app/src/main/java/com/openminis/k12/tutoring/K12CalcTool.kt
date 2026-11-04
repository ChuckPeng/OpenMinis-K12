package com.openminis.k12.tutoring

import android.os.Looper
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device math tool the chat model can call. Symja runs off the main thread.
 * The expression is not written to logcat.
 */
object K12CalcTool {
    const val NAME = "k12_calc"

    fun execute(argsJson: String): ToolExecutionResult {
        val expression = try {
            JSONObject(argsJson).optString("expression", "").trim()
        } catch (_: Exception) {
            ""
        }
        if (expression.isEmpty()) {
            return ToolExecutionResult("missing expression", false, toolTitle = "计算")
        }
        val result = evaluateOffMain(expression)
        val ok = result is CalcResult.Ok
        TutoringRuntime.noteCalc(ok)
        val text = when (result) {
            is CalcResult.Ok -> result.value
            is CalcResult.Err -> result.message
        }
        return ToolExecutionResult(text, ok, toolTitle = "计算")
    }

    private fun evaluateOffMain(expression: String): CalcResult {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return CalcEngines.preferred().evaluate(expression)
        }
        val box = AtomicReference<CalcResult>(CalcResult.Err("unavailable"))
        val latch = CountDownLatch(1)
        Thread({
            box.set(CalcEngines.preferred().evaluate(expression))
            latch.countDown()
        }, "k12-calc").apply { isDaemon = true }.start()
        if (!latch.await(65, TimeUnit.SECONDS)) return CalcResult.Err("timeout")
        return box.get()
    }
}
