package com.google.android.accessibility.talkback.taskassistant

enum class TaskState { IDLE, OBSERVING, PLANNING, EXECUTING, PAUSED, FINISHED, FAILED, CANCELLED }

enum class TaskExecution { PERFORMED, STALE, FAILED }

interface TaskHost {
  fun observe(done: (Result<TaskScreen>) -> Unit)
  fun createPrompt(goal: String, screen: TaskScreen, apps: List<TaskApp>, history: List<String>): String =
    TaskProtocol.prompt(goal, screen, apps, history)
  fun plan(prompt: String, done: (Result<String>) -> Unit)
  fun execute(screen: TaskScreen, step: TaskStep, done: (TaskExecution) -> Unit)
  fun changed(state: TaskState, message: String)
  fun cancelOperations()
}

class TaskEngine(private val host: TaskHost, private val apps: List<TaskApp>) {
  var state = TaskState.IDLE
    private set
  var goal = ""
    private set
  var message = ""
    private set
  private var generation = 0L
  private var steps = 0
  private var failures = 0
  private var unchanged = 0
  private var pendingChange: String? = null
  private var pendingSteps: ArrayDeque<TaskStep>? = null
  private val history = ArrayDeque<String>()
  private val repeated = HashMap<String, Int>()

  val busy: Boolean
    get() = state in setOf(TaskState.OBSERVING, TaskState.PLANNING, TaskState.EXECUTING)

  val active: Boolean
    get() = busy || state == TaskState.PAUSED

  fun start(request: String) {
    require(request.isNotBlank() && request.length <= 4000)
    invalidate()
    goal = request.trim()
    history.clear()
    repeated.clear()
    steps = 0
    failures = 0
    unchanged = 0
    pendingChange = null
    pendingSteps = null
    observe()
  }

  fun pause() {
    if (!active) return
    invalidate()
    pendingSteps = null
    setState(TaskState.PAUSED, "Đã tạm dừng")
  }

  fun resume() {
    if (state != TaskState.PAUSED) return
    addHistory("User resumed after manual interaction. Re-evaluate the current screen and goal.")
    pendingChange = null
    unchanged = 0
    pendingSteps = null
    observe()
  }

  fun cancel() {
    invalidate()
    pendingSteps = null
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
    pendingSteps = null
    setState(TaskState.FAILED, text)
  }

  private fun observe() {
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
        if (pendingSteps != null) { executeNextSequence(screen); return@observe }
        addHistory("The last action did not change the screen. Try another way: a different node, wait for loading, scroll, or try a different target.")
        plan(screen)
        return@observe
      }
      if (pendingChange != null) addHistory("Observed a changed screen after the last action.")
      pendingChange = null
      unchanged = 0
      if (pendingSteps != null) executeNextSequence(screen) else plan(screen)
    }
  }

  private fun plan(screen: TaskScreen) {
    setState(TaskState.PLANNING, "Đang chọn bước tiếp theo")
    val token = generation
    val prompt = runCatching { host.createPrompt(goal, screen, apps, history.toList()) }.getOrElse {
      fail(it.message ?: "Không chuẩn bị được dữ liệu cho AI")
      return
    }
    host.plan(prompt) { result ->
      if (token != generation || state != TaskState.PLANNING) return@plan
      val answer = result.getOrElse { fail("AI không trả lời được. ${it.message.orEmpty()}"); return@plan }
      val plan = try { TaskProtocol.parsePlan(answer, screen, apps) } catch (e: Exception) {
        fail("AI trả lời không hợp lệ. ${e.message.orEmpty()}"); return@plan
      }
      when (plan) {
        is TaskProtocol.TaskPlan.Single -> {
          val step = plan.step
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
            TaskOperation.ASK -> fail(step.message)
            else -> execute(screen, step)
          }
        }
        is TaskProtocol.TaskPlan.Sequence -> {
          pendingSteps = ArrayDeque(plan.steps)
          addHistory("Chuỗi ${plan.steps.size} bước đã nhận. Thực hiện lần lượt, đối chiếu từng mục trên màn hình mới.")
          executeNextSequence(screen)
        }
      }
    }
  }

  private fun executeNextSequence(screen: TaskScreen) {
    val step = pendingSteps?.removeFirstOrNull()
    if (step == null) {
      pendingSteps = null
      addHistory("Đã thực hiện xong chuỗi.")
      plan(screen)
      return
    }
    execute(screen, step)
  }

  private fun execute(screen: TaskScreen, step: TaskStep) {
    val target = if (step.node.isNotBlank()) step.node else step.label
    val signature = "${screen.fingerprint}:${step.operation}:$target:${step.packageName}"
    val attempts = (repeated[signature] ?: 0) + 1
    repeated[signature] = attempts
    if (attempts > 3) { pendingSteps = null; fail("Đã dừng vì tác vụ đang lặp lại cùng một thao tác"); return }
    setState(TaskState.EXECUTING, "Đang thực hiện bước ${steps + 1}")
    val token = generation
    host.execute(screen, step) { outcome ->
      if (generation != token || state != TaskState.EXECUTING) return@execute
      steps++
      when (outcome) {
        TaskExecution.PERFORMED -> {
          failures = 0
          pendingChange = if (step.operation == TaskOperation.WAIT) null else screen.fingerprint
          addHistory("Action ${step.operation.wire} on ${screen.packageName}: ${screen.nodes.firstOrNull { it.id == step.node }?.label.orEmpty().take(160)}${step.label.take(160)}. Await observable result.")
        }
        TaskExecution.STALE -> {
          failures++
          pendingSteps = null
          addHistory("Screen changed before action. No action was executed. Reconsider using a fresh snapshot.")
        }
        TaskExecution.FAILED -> {
          failures++
          pendingSteps = null
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
}
