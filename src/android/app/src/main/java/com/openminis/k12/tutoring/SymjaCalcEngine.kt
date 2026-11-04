package com.openminis.k12.tutoring

import edu.jas.kern.ComputerThreads
import org.matheclipse.core.basic.Config
import org.matheclipse.core.eval.ExprEvaluator
import org.matheclipse.core.expression.F
import org.matheclipse.core.interfaces.IAST
import org.matheclipse.core.interfaces.IExpr
import org.matheclipse.core.interfaces.IRational
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.abs
import kotlin.math.round

/**
 * On-device Symja (matheclipse 3.2.0) for tutoring A4.
 *
 * The builtin table is loaded on a daemon thread the first time a check runs.
 * Calls from the Android main thread return [CalcResult.Err] `"main-thread"`
 * without touching the engine. Each check has a wall-clock budget; a timed-out
 * engine is discarded so a stuck evaluation cannot poison the next one.
 *
 * Student text is not evaluated as a Symja program. After a character and
 * nesting screen, the parsed tree must use an allow-list of arithmetic heads.
 */
object SymjaCalcEngine : AlgebraCas {
    const val DEFAULT_TIMEOUT_MS = 2_000L
    const val MAX_INPUT_CHARS = 512
    const val MAX_NESTING = 32
    private const val MAX_NODES = 256
    private const val INIT_BUDGET_MS = 60_000L
    private const val RECURSION_LIMIT = 128
    private const val ITERATION_LIMIT = 4_096
    private const val OUTPUT_LIMIT = 4_096
    private const val MAX_RETIRES = 4

    /** Unit tests stretch a check past [timeoutForTestsMs]. Not for production. */
    @Volatile
    var stallForTestsMs: Long = 0

    /** Unit tests shrink the per-check budget. Not for production. */
    @Volatile
    var timeoutForTestsMs: Long = DEFAULT_TIMEOUT_MS

    private val gate = ReentrantLock()
    private val configured = AtomicBoolean(false)
    private val warmupStarted = AtomicBoolean(false)
    private val retires = AtomicInteger(0)

    /** True after one check has finished. The first check waits out table load. */
    private val served = AtomicBoolean(false)

    /** Unit tests wait until a check is inside the gate. Not for production. */
    @Volatile
    internal var enteredForTests: Boolean = false

    @Volatile
    private var worker: ExecutorService? = null

    @Volatile
    private var evaluator: ExprEvaluator? = null

    private val allowedChars = Regex("^[0-9A-Za-z+\\-*/^=().,{}! \\t]+$")

    private val allowedHeads = setOf(
        "Plus", "Times", "Power", "List", "Equal", "Unequal",
        "Greater", "Less", "GreaterEqual", "LessEqual",
        "Sin", "Cos", "Tan", "Cot", "Sec", "Csc",
        "Sinh", "Cosh", "Tanh",
        "Log", "Exp", "Abs", "Factorial", "Sqrt",
    )

    private val numericHeads = setOf(
        "Plus", "Times", "Power", "Sin", "Cos", "Tan", "Log", "Exp", "Abs", "Sqrt", "Factorial",
    )

    private val constants = setOf("Pi", "E", "I")

    /** Heads whose real poles are not a finite set we can list. */
    private val infinitePoleHeads = setOf("Tan", "Cot", "Sec", "Csc")

    /** Start loading the builtin table off the caller thread. Safe on the main thread. */
    fun warmUpAsync() {
        if (evaluator != null || !warmupStarted.compareAndSet(false, true)) return
        Thread({
            val ok = runCatching { warmUp() }.isSuccess
            if (!ok) warmupStarted.set(false)
        }, "k12-symja-warmup").apply { isDaemon = true }.start()
    }

    fun warmUp() {
        if (onAndroidMainThread()) return
        // Loading the table is not a completed check. The first evaluate still
        // gets the long budget if this call is what built the engine.
        runEngine(markServed = false) { it.clearVariables() }
    }

    /** Lets a unit test observe a check that arrives while warmup holds the gate. */
    internal fun prepareColdWaitForTests() {
        served.set(false)
    }

    override fun evaluate(expression: String): CalcResult {
        if (onAndroidMainThread()) return CalcResult.Err("main-thread")
        val prepared = prepare(expression) ?: return CalcResult.Err("rejected")
        return when (val out = runEngine { ev -> evalNumericOrSimplified(ev, prepared) }) {
            is Outcome.Ok -> out.value
            is Outcome.Fail -> CalcResult.Err(out.message)
        }
    }

    override fun simplify(expression: String): CalcResult =
        rewrite(expression) { ev, expr -> ev.eval(F.Simplify(expr)) }

    override fun factor(expression: String): CalcResult =
        rewrite(expression) { ev, expr -> ev.eval(F.Factor(expr)) }

    override fun equivalent(left: String, right: String): CalcEquivalence {
        if (onAndroidMainThread()) return CalcEquivalence.Unavailable("main-thread")
        val a = prepare(left) ?: return CalcEquivalence.Unavailable("rejected")
        val b = prepare(right) ?: return CalcEquivalence.Unavailable("rejected")
        return when (val out = runEngine { ev -> equivalenceOf(ev, a, b) }) {
            is Outcome.Ok -> out.value
            is Outcome.Fail -> CalcEquivalence.Unavailable(out.message)
        }
    }

    override fun solve(equation: String, variable: String, field: CasField): CalcResult {
        if (onAndroidMainThread()) return CalcResult.Err("main-thread")
        val eq = prepare(equation) ?: return CalcResult.Err("rejected")
        val v = prepare(variable) ?: return CalcResult.Err("rejected")
        return when (val out = runEngine { ev -> solveCanonical(ev, eq, v, field) }) {
            is Outcome.Ok -> out.value
            is Outcome.Fail -> CalcResult.Err(out.message)
        }
    }

    override fun solutionsMatch(
        student: String,
        equation: String,
        variable: String,
        field: CasField,
    ): CalcEquivalence {
        if (onAndroidMainThread()) return CalcEquivalence.Unavailable("main-thread")
        val eq = prepare(equation) ?: return CalcEquivalence.Unavailable("rejected")
        val v = prepare(variable) ?: return CalcEquivalence.Unavailable("rejected")
        val answered = prepare(braceIfList(student)) ?: return CalcEquivalence.Unavailable("rejected")
        return when (val out = runEngine { ev -> matchSolutions(ev, answered, eq, v, field) }) {
            is Outcome.Ok -> out.value
            is Outcome.Fail -> CalcEquivalence.Unavailable(out.message)
        }
    }

    private fun rewrite(expression: String, op: (ExprEvaluator, IExpr) -> IExpr): CalcResult {
        if (onAndroidMainThread()) return CalcResult.Err("main-thread")
        val prepared = prepare(expression) ?: return CalcResult.Err("rejected")
        return when (val out = runEngine { ev ->
            val parsed = parseTree(ev, prepared) ?: return@runEngine CalcResult.Err("rejected")
            val value = op(ev, parsed)
            if (isNonFinite(value)) CalcResult.Err("non-finite") else CalcResult.Ok(value.toString())
        }) {
            is Outcome.Ok -> out.value
            is Outcome.Fail -> CalcResult.Err(out.message)
        }
    }

    private fun evalNumericOrSimplified(ev: ExprEvaluator, prepared: String): CalcResult {
        val parsed = parseTree(ev, prepared) ?: return CalcResult.Err("rejected")
        val value = ev.eval(parsed)
        if (isNonFinite(value)) return CalcResult.Err("non-finite")
        if (isNumeric(parsed)) {
            val n = ev.evalf(parsed)
            if (!n.isFinite()) return CalcResult.Err("non-finite")
            return CalcResult.Ok(formatDouble(n))
        }
        val simplified = ev.eval(F.Simplify(parsed))
        if (isNonFinite(simplified)) return CalcResult.Err("non-finite")
        return CalcResult.Ok(simplified.toString())
    }

    private fun equivalenceOf(ev: ExprEvaluator, left: String, right: String): CalcEquivalence {
        val a = parseTree(ev, left) ?: return CalcEquivalence.Unavailable("rejected")
        val b = parseTree(ev, right) ?: return CalcEquivalence.Unavailable("rejected")
        val algebraic = algebraicSame(ev, a, b)
        if (algebraic != true) {
            return if (algebraic == false) CalcEquivalence.Different else CalcEquivalence.Unavailable("symja")
        }
        val leftDomain = realDomain(ev, a) ?: return CalcEquivalence.Unavailable("domain")
        val rightDomain = realDomain(ev, b) ?: return CalcEquivalence.Unavailable("domain")
        val where = symmetricDifference(leftDomain, rightDomain)
        return if (where.isEmpty()) CalcEquivalence.Same("0") else CalcEquivalence.Restricted(where)
    }

    private fun sameExpr(ev: ExprEvaluator, left: IExpr, right: IExpr): CalcEquivalence {
        return when (algebraicSame(ev, left, right)) {
            true -> CalcEquivalence.Same("0")
            false -> CalcEquivalence.Different
            null -> CalcEquivalence.Unavailable("symja")
        }
    }

    /** True, false, or null when Symja cannot decide. Ignores domain holes. */
    private fun algebraicSame(ev: ExprEvaluator, left: IExpr, right: IExpr): Boolean? {
        val diff = ev.eval(F.Simplify(F.Subtract(left, right)))
        if (isNonFinite(diff)) return null
        val zero = ev.eval(F.PossibleZeroQ(diff))
        return when {
            zero.isTrue() -> true
            zero.isFalse() -> false
            else -> null
        }
    }

    private fun solveCanonical(
        ev: ExprEvaluator,
        equation: String,
        variable: String,
        field: CasField,
    ): CalcResult {
        val roots = rootsOf(ev, equation, variable, field) ?: return CalcResult.Err("not-equation")
        val text = roots.map { it.toString() }.sorted().joinToString(prefix = "{", postfix = "}", separator = ", ")
        return CalcResult.Ok(text)
    }

    private fun matchSolutions(
        ev: ExprEvaluator,
        student: String,
        equation: String,
        variable: String,
        field: CasField,
    ): CalcEquivalence {
        val roots = rootsOf(ev, equation, variable, field) ?: return CalcEquivalence.Unavailable("not-equation")
        val given = parseTree(ev, student) ?: return CalcEquivalence.Unavailable("rejected")
        val answers = when {
            given.isAST && headName(given) == "List" -> argsOf(given as IAST)
            else -> listOf(given)
        }
        if (answers.size != roots.size) return CalcEquivalence.Different
        val used = BooleanArray(roots.size)
        for (answer in answers) {
            val idx = roots.indices.firstOrNull { i ->
                !used[i] && sameExpr(ev, answer, roots[i]) is CalcEquivalence.Same
            } ?: return CalcEquivalence.Different
            used[idx] = true
        }
        return CalcEquivalence.Same("solutions")
    }

    private fun rootsOf(
        ev: ExprEvaluator,
        equation: String,
        variable: String,
        field: CasField,
    ): List<IExpr>? {
        val eq = parseTree(ev, equation) ?: return null
        if (!eq.isAST || headName(eq) != "Equal") return null
        val sym = parseTree(ev, variable) ?: return null
        if (!sym.isSymbol || sym.isBuiltInSymbol) return null
        val solved = ev.eval(F.Solve(eq, sym))
        val roots = rootList(solved) ?: return null
        if (field == CasField.COMPLEX) return roots
        return roots.filter { isRealValue(ev, it) }
    }

    private fun rootList(solved: IExpr): List<IExpr>? {
        if (!solved.isAST || headName(solved) != "List") return null
        val roots = ArrayList<IExpr>()
        val outer = solved as IAST
        for (i in 1 until outer.size()) {
            val item = outer.get(i)
            if (!item.isAST) return null
            val inner = item as IAST
            when (headName(inner)) {
                "Rule" -> roots.add(inner.arg2())
                "List" -> {
                    var found = false
                    for (j in 1 until inner.size()) {
                        val rule = inner.get(j)
                        if (rule.isAST && headName(rule) == "Rule") {
                            roots.add((rule as IAST).arg2())
                            found = true
                        }
                    }
                    if (!found) return null
                }
                else -> return null
            }
        }
        return roots
    }

    /** A root counts as real only when its imaginary part is identically zero. */
    private fun isRealValue(ev: ExprEvaluator, expr: IExpr): Boolean {
        val value = ev.eval(expr)
        if (isNonFinite(value)) return false
        val im = ev.eval(F.Im(value))
        if (isNonFinite(im)) return false
        return ev.eval(F.PossibleZeroQ(im)).isTrue()
    }

    /**
     * Real domain as a set of conditions. Null when the domain is not a finite
     * combination of poles and radicand inequalities (the check must not guess).
     * An expression undefined everywhere is the single condition `undefined`.
     */
    private fun realDomain(ev: ExprEvaluator, expr: IExpr): RealDomain? {
        val acc = DomainAcc()
        if (!accumulateDomain(ev, expr, acc)) return null
        if (acc.empty) return RealDomain(setOf("undefined"))
        return RealDomain(acc.poles + acc.bounds)
    }

    private fun accumulateDomain(ev: ExprEvaluator, expr: IExpr, acc: DomainAcc): Boolean {
        if (acc.empty) return true
        if (expr.isNumber || expr.isSymbol) return true
        if (!expr.isAST) return false
        val ast = expr as IAST
        val head = headName(ast)
        if (head in infinitePoleHeads) return false
        return when (head) {
            "Power" -> accumulatePower(ev, ast, acc)
            "Sqrt" -> addBound(ev, ast.arg1(), strict = false, acc) &&
                accumulateDomain(ev, ast.arg1(), acc)
            "Log" -> addBound(ev, ast.arg1(), strict = true, acc) &&
                accumulateDomain(ev, ast.arg1(), acc)
            else -> {
                for (i in 1 until ast.size()) {
                    if (!accumulateDomain(ev, ast.get(i), acc)) return false
                }
                true
            }
        }
    }

    private fun accumulatePower(ev: ExprEvaluator, ast: IAST, acc: DomainAcc): Boolean {
        if (ast.size() < 3) return false
        val base = ast.arg1()
        val exp = ast.arg2()
        if (!accumulateDomain(ev, base, acc) || !accumulateDomain(ev, exp, acc)) return false
        if (exp.isInteger()) {
            return if (exp.isNegative()) addPoles(ev, base, acc) else true
        }
        if (!exp.isRational()) return false
        val evenRoot = (exp as IRational).denominator().isEven()
        if (evenRoot && !addBound(ev, base, strict = exp.isNegative(), acc)) return false
        return if (exp.isNegative()) addPoles(ev, base, acc) else true
    }

    private fun addPoles(ev: ExprEvaluator, base: IExpr, acc: DomainAcc): Boolean {
        val simplified = ev.eval(F.Simplify(base))
        if (isNonFinite(simplified)) return false
        if (ev.eval(F.PossibleZeroQ(simplified)).isTrue()) {
            acc.empty = true
            return true
        }
        val symbols = userSymbols(base)
        if (symbols.isEmpty()) return true
        if (symbols.size != 1) return false
        val name = symbols[0]
        val sym = ev.parse(name)
        val roots = rootList(ev.eval(F.Solve(F.Equal(base, F.C0), sym))) ?: return false
        for (root in roots) {
            val value = ev.eval(root)
            if (isNonFinite(value) || userSymbols(value).isNotEmpty()) return false
            if (!isRealValue(ev, value)) continue
            acc.poles.add("$name!=${value.toString()}")
        }
        return true
    }

    private fun addBound(ev: ExprEvaluator, radicand: IExpr, strict: Boolean, acc: DomainAcc): Boolean {
        val simplified = ev.eval(F.Simplify(radicand))
        if (isNonFinite(simplified)) return false
        if (simplified.isNumber && !strict && simplified.isNonNegativeResult()) return true
        if (simplified.isNumber && strict && simplified.isPositiveResult()) return true
        val op = if (strict) ">" else ">="
        val text = simplified.toString()
        val body = if (text.matches(Regex("[A-Za-z][A-Za-z0-9]*"))) text else "($text)"
        acc.bounds.add("$body$op" + "0")
        return true
    }

    private fun userSymbols(expr: IExpr): List<String> {
        val found = sortedSetOf<String>()
        val stack = ArrayDeque<IExpr>()
        stack.add(expr)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current.isSymbol) {
                val name = simpleName(current.toString())
                if (!current.isBuiltInSymbol && name !in constants) found.add(name)
                continue
            }
            if (current.isAST) {
                val ast = current as IAST
                for (i in 0 until ast.size()) stack.add(ast.get(i))
            }
        }
        return found.toList()
    }

    private fun symmetricDifference(left: RealDomain, right: RealDomain): List<String> {
        val onlyLeft = left.conditions.filter { it !in right.conditions }
        val onlyRight = right.conditions.filter { it !in left.conditions }
        return (onlyLeft + onlyRight).sorted()
    }

    private data class RealDomain(val conditions: Set<String>)

    private class DomainAcc {
        val poles = HashSet<String>()
        val bounds = HashSet<String>()
        var empty = false
    }

    private fun parseTree(ev: ExprEvaluator, prepared: String): IExpr? {
        val parsed = try {
            ev.parse(prepared)
        } catch (_: RuntimeException) {
            return null
        } catch (_: StackOverflowError) {
            return null
        }
        val budget = intArrayOf(MAX_NODES)
        return if (allowedTree(parsed, 0, budget)) parsed else null
    }

    private fun allowedTree(expr: IExpr, depth: Int, budget: IntArray): Boolean {
        if (depth > MAX_NESTING || budget[0]-- <= 0) return false
        if (expr.isNumber) return true
        if (expr.isSymbol) {
            val name = simpleName(expr.toString())
            if (name in constants) return true
            if (expr.isBuiltInSymbol) return false
            return name.matches(Regex("^[A-Za-z][A-Za-z0-9]{0,15}$"))
        }
        if (!expr.isAST) return false
        val ast = expr as IAST
        if (headName(ast) !in allowedHeads) return false
        for (i in 1 until ast.size()) {
            if (!allowedTree(ast.get(i), depth + 1, budget)) return false
        }
        return true
    }

    private fun isNumeric(expr: IExpr): Boolean {
        if (expr.isNumber) return true
        if (expr.isSymbol) return simpleName(expr.toString()) in constants
        if (!expr.isAST) return false
        val ast = expr as IAST
        if (headName(ast) !in numericHeads) return false
        for (i in 1 until ast.size()) {
            if (!isNumeric(ast.get(i))) return false
        }
        return true
    }

    private fun prepare(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty() || s.length > MAX_INPUT_CHARS) return null
        s = s.replace('−', '-')
            .replace('–', '-')
            .replace('—', '-')
            .replace('×', '*')
            .replace('·', '*')
            .replace('÷', '/')
            .replace("√(", "Sqrt(")
        if (s.length > MAX_INPUT_CHARS || !allowedChars.matches(s)) return null
        if (nestingBad(s)) return null
        return s
    }

    private fun braceIfList(raw: String): String {
        val s = raw.trim()
        if (s.startsWith("{") && s.endsWith("}")) return s
        return if (topLevelComma(s)) "{$s}" else s
    }

    private fun topLevelComma(s: String): Boolean {
        var depth = 0
        for (c in s) {
            when (c) {
                '(', '{' -> depth++
                ')', '}' -> depth--
                ',' -> if (depth == 0) return true
            }
        }
        return false
    }

    private fun nestingBad(s: String): Boolean {
        var depth = 0
        for (c in s) {
            when (c) {
                '(', '{' -> {
                    depth++
                    if (depth > MAX_NESTING) return true
                }
                ')', '}' -> {
                    depth--
                    if (depth < 0) return true
                }
            }
        }
        return depth != 0
    }

    private fun isNonFinite(expr: IExpr): Boolean {
        val s = expr.toString()
        return s == "ComplexInfinity" || s == "Indeterminate" ||
            s == "Infinity" || s == "-Infinity" || s.contains("DirectedInfinity")
    }

    private fun formatDouble(v: Double): String {
        if (!v.isFinite()) error("non-finite")
        val nearest = round(v)
        if (abs(v - nearest) < 1e-9 && abs(nearest) < 1.0e15) return nearest.toLong().toString()
        return java.math.BigDecimal.valueOf(v)
            .setScale(12, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    private fun headName(expr: IExpr): String =
        simpleName(if (expr.isAST) expr.head().toString() else expr.toString())

    private fun simpleName(raw: String): String {
        val i = raw.lastIndexOf('`')
        return if (i >= 0) raw.substring(i + 1) else raw
    }

    private fun argsOf(ast: IAST): List<IExpr> {
        val out = ArrayList<IExpr>(ast.argSize())
        for (i in 1 until ast.size()) out.add(ast.get(i))
        return out
    }

    private sealed class Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>()
        data class Fail(val message: String) : Outcome<Nothing>()
    }

    private fun <T> runEngine(markServed: Boolean = true, block: (ExprEvaluator) -> T): Outcome<T> {
        if (retires.get() >= MAX_RETIRES) return Outcome.Fail("unavailable")
        val evalBudget = timeoutForTestsMs.coerceAtLeast(1L)
        // noteQuestion starts warmup, and the model can call k12_calc while
        // that load still holds the gate. Waiting only evalBudget returns
        // "busy" and the chat then shows that word instead of the number.
        // A check that arrives during the first load waits for the table.
        // After one check has completed, a taken gate is "busy" at evalBudget.
        val cold = evaluator == null || !served.get()
        val lockBudget = if (cold) INIT_BUDGET_MS else evalBudget
        if (!gate.tryLock(lockBudget, TimeUnit.MILLISECONDS)) return Outcome.Fail("busy")
        enteredForTests = true
        try {
            val exec = worker ?: Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "k12-symja").apply { isDaemon = true }
            }.also { worker = it }
            val firstLoad = evaluator == null
            val ready = exec.submit(Callable { ensureEvaluator() })
            val ev = try {
                ready.get(INIT_BUDGET_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                ready.cancel(true)
                retire()
                return Outcome.Fail("timeout")
            } catch (_: ExecutionException) {
                retire()
                return Outcome.Fail("init")
            }
            val stall = stallForTestsMs
            val work = exec.submit(Callable {
                if (stall > 0) Thread.sleep(stall)
                applyLimits(ev)
                ev.clearVariables()
                block(ev)
            })
            // The first numeric check also loads classes inside the eval.
            // A shortened test budget still applies, so the timeout test can
            // discard a stalled engine without waiting out init.
            val workBudget = if ((firstLoad || !served.get()) && timeoutForTestsMs >= DEFAULT_TIMEOUT_MS) {
                INIT_BUDGET_MS
            } else {
                evalBudget
            }
            return try {
                val value = work.get(workBudget, TimeUnit.MILLISECONDS)
                if (markServed) served.set(true)
                Outcome.Ok(value)
            } catch (_: TimeoutException) {
                work.cancel(true)
                retire()
                Outcome.Fail("timeout")
            } catch (e: ExecutionException) {
                when (e.cause) {
                    is StackOverflowError, is OutOfMemoryError -> {
                        retire()
                        Outcome.Fail("overflow")
                    }
                    else -> Outcome.Fail("symja")
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Outcome.Fail("interrupted")
        } finally {
            enteredForTests = false
            gate.unlock()
        }
    }

    private fun ensureEvaluator(): ExprEvaluator {
        evaluator?.let { return it }
        configureStatic()
        val created = ExprEvaluator(true, 0.toShort())
        applyLimits(created)
        runCatching { ComputerThreads.setNoThreads() }
        evaluator = created
        return created
    }

    private fun applyLimits(ev: ExprEvaluator) {
        ev.evalEngine.setRecursionLimit(RECURSION_LIMIT)
        ev.evalEngine.setIterationLimit(ITERATION_LIMIT)
        ev.evalEngine.setOutputSizeLimit(OUTPUT_LIMIT)
    }

    private fun configureStatic() {
        if (!configured.compareAndSet(false, true)) return
        System.setProperty("log4j2.statusLoggerLevel", "OFF")
        Config.FILESYSTEM_ENABLED = false
        Config.JAVA_AWT_DESKTOP_AVAILABLE = false
        Config.SWING_PLOT_FRAME = false
        Config.DISABLE_JMX = true
        Config.UNPROTECT_ALLOWED = false
        Config.JAS_NO_THREADS = true
        Config.MAX_POLYNOMIAL_DEGREE = 12
        Config.MAX_AST_SIZE = 1_024
        Config.MAX_BIT_LENGTH = 4_096
        Config.MAX_INPUT_LEAVES = 512
        Config.MAX_LOOP_COUNT = 50_000
    }

    private fun retire() {
        retires.incrementAndGet()
        evaluator = null
        served.set(false)
        worker?.shutdownNow()
        worker = null
    }

    internal fun onAndroidMainThread(): Boolean {
        return try {
            val looper = android.os.Looper.getMainLooper() ?: return false
            Thread.currentThread() === looper.thread
        } catch (_: Throwable) {
            false
        }
    }
}
