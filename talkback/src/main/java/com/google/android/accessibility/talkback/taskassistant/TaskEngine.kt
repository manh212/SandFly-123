package com.google.android.accessibility.talkback.taskassistant

enum class TaskState { IDLE, OBSERVING, PLANNING, EXECUTING, CONFIRMING, ASKING, INTERRUPTED, PAUSED, FINISHED, FAILED, CANCELLED }

enum class TaskExecution { PERFORMED, STALE, FAILED }

interface TaskHost {
  fun observe(done: (Result<TaskScreen>) -> Unit)
  fun plan(prompt: String, done: (Result<String>) -> Unit)
  fun execute(screen: TaskScreen, step: TaskStep, done: (TaskExecution) -> Unit)
  fun confirm(screen: TaskScreen, step: TaskStep, done: (Boolean) -> Unit)
  fun ask(question: String, done: (String?) -> Unit)
  fun interrupted()
  fun changed(state: TaskState, message: String)
  fun cancelOperations()
  fun now(): Long
}

class TaskEngine(private val host: TaskHost, private val apps: List<TaskApp>) {
  var state = TaskState.IDLE
    private set
  var goal = ""
    private set
  var message = ""
    private set
  private var generation = 0L
  private var started = 0L
  private var pausedAt = 0L
  private var steps = 0
  private var failures = 0
  private var unchanged = 0
  private var pendingChange: String? = null
  private val history = ArrayDeque<String>()
  private val repeated = HashMap<String, Int>()

  val busy: Boolean
    get() = state in setOf(TaskState.OBSERVING, TaskState.PLANNING, TaskState.EXECUTING)

  val active: Boolean
    get() = busy || state in setOf(TaskState.CONFIRMING, TaskState.ASKING, TaskState.INTERRUPTED, TaskState.PAUSED)

  fun start(request: String) {
    require(request.isNotBlank() && request.length <= 4000)
    invalidate()
    goal = request.trim()
    history.clear()
    repeated.clear()
    started = host.now()
    steps = 0
    failures = 0
    unchanged = 0
    pendingChange = null
    observe()
  }

  fun interrupt() {
    if (!busy) return
    invalidate()
    pausedAt = host.now()
    setState(TaskState.INTERRUPTED, "Đã ngừng thao tác")
    host.interrupted()
  }

  fun pause() {
    if (!active) return
    invalidate()
    pausedAt = host.now()
    setState(TaskState.PAUSED, "Đã tạm dừng")
  }

  fun resume() {
    if (state != TaskState.PAUSED) return
    started += (host.now() - pausedAt).coerceAtLeast(0L)
    addHistory("User resumed after manual interaction. Re-evaluate the current screen and goal.")
    pendingChange = null
    unchanged = 0
    observe()
  }

  fun cancel() {
    invalidate()
    setState(TaskState.CANCELLED, "Đã hủy tác vụ")
  }

  private fun invalidate() {
    generation++
    host.cancelOperations()
  }

  private fun setState(next: TaskState, text: String) {
    state = next
    message = text
    host.changed(next, text)
  }

  private fun fail(text: String) {
    invalidate()
    setState(TaskState.FAILED, text)
  }

  private fun observe() {
    if (steps >= MAX_STEPS || host.now() - started > MAX_ACTIVE_MS) {
      fail("Đã dừng vì tác vụ vượt giới hạn thời gian hoặc số bước")
      return
    }
    setState(TaskState.OBSERVING, "Đang đọc màn hình")
    val token = ++generation
    host.observe { result ->
      if (generation != token || state != TaskState.OBSERVING) return@observe
      val screen = result.getOrElse { fail("Không đọc được màn hình. ${it.message.orEmpty()}"); return@observe }
      if (pendingChange == screen.fingerprint) {
        unchanged++
        if (unchanged < 3) { observe(); return@observe }
        pendingChange = null
        unchanged = 0
        fail("Chưa xác nhận được kết quả thao tác. Bạn có thể kiểm tra màn hình và giao việc lại")
        return@observe
      }
      if (pendingChange != null) addHistory("Observed a changed screen after the last action.")
      pendingChange = null
      unchanged = 0
      plan(screen)
    }
  }

  private fun plan(screen: TaskScreen) {
    setState(TaskState.PLANNING, "Đang chọn bước tiếp theo")
    val token = generation
    host.plan(TaskProtocol.prompt(goal, screen, apps, history.toList())) { result ->
      if (token != generation || state != TaskState.PLANNING) return@plan
      val answer = result.getOrElse { fail("AI không trả lời được. ${it.message.orEmpty()}"); return@plan }
      val step = try { TaskProtocol.parse(answer, screen, apps) } catch (e: Exception) {
        fail("AI trả lời không hợp lệ. ${e.message.orEmpty()}"); return@plan
      }
      when (step.operation) {
        TaskOperation.DONE -> {
          host.observe verified@ { latest ->
            if (generation != token || state != TaskState.PLANNING) return@verified
            val current = latest.getOrElse { fail("Không kiểm chứng được kết quả tác vụ"); return@verified }
            if (current.fingerprint != screen.fingerprint) {
              addHistory("Screen changed before completion was verified. Re-evaluate the goal.")
              observe()
            } else {
              invalidate()
              setState(TaskState.FINISHED, "Đã đối chiếu kết quả: ${step.message}")
            }
          }
        }
        TaskOperation.ASK -> ask(step.message)
        else -> if (TaskProtocol.needsConfirmation(step, screen)) confirm(screen, step) else execute(screen, step)
      }
    }
  }

  private fun confirm(screen: TaskScreen, step: TaskStep) {
    setState(TaskState.CONFIRMING, "Chờ xác nhận")
    val token = generation
    host.confirm(screen, step) { accepted ->
      if (token != generation || state != TaskState.CONFIRMING) return@confirm
      if (accepted) execute(screen, step) else pause()
    }
  }

  private fun ask(question: String) {
    setState(TaskState.ASKING, question)
    val token = generation
    host.ask(question) { answer ->
      if (generation != token || state != TaskState.ASKING) return@ask
      if (answer.isNullOrBlank()) { pause(); return@ask }
      addHistory("User clarification: ${answer.take(4000)}")
      observe()
    }
  }

  private fun execute(screen: TaskScreen, step: TaskStep) {
    val signature = "${screen.fingerprint}:${step.operation}:${step.node}:${step.packageName}"
    val attempts = (repeated[signature] ?: 0) + 1
    repeated[signature] = attempts
    if (attempts > 3) { fail("Đã dừng vì tác vụ đang lặp lại cùng một thao tác"); return }
    setState(TaskState.EXECUTING, "Đang thực hiện bước ${steps + 1}")
    val token = generation
    host.execute(screen, step) { outcome ->
      if (generation != token || state != TaskState.EXECUTING) return@execute
      steps++
      when (outcome) {
        TaskExecution.PERFORMED -> {
          failures = 0
          pendingChange = if (step.operation == TaskOperation.WAIT) null else screen.fingerprint
          addHistory("Action ${step.operation.wire} on ${screen.packageName}: ${screen.nodes.firstOrNull { it.id == step.node }?.label.orEmpty().take(160)}. Await observable result.")
        }
        TaskExecution.STALE -> {
          failures++
          addHistory("Screen changed before action. No action was executed. Reconsider using a fresh snapshot.")
        }
        TaskExecution.FAILED -> {
          failures++
          addHistory("Action failed. Do not repeat the same action blindly.")
        }
      }
      if (failures >= 3) fail("Đã dừng vì không thể thực hiện đúng thao tác") else observe()
    }
  }

  private fun addHistory(text: String) {
    history.addLast(text)
    while (history.size > 12) history.removeFirst()
  }

  companion object {
    const val MAX_STEPS = 40
    const val MAX_ACTIVE_MS = 10 * 60 * 1000L
  }
}
