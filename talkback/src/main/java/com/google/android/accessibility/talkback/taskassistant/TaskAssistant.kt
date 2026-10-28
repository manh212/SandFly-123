package com.google.android.accessibility.talkback.taskassistant

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.pause.PauseController
import com.google.android.accessibility.utils.material.MaterialComponentUtils
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.widget.DialogUtils
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class TaskAssistant private constructor(private val service: TalkBackService) : TaskHost {
  private val main = Handler(Looper.getMainLooper())
  private val worker = Executors.newSingleThreadExecutor()
  private val epoch = AtomicLong()
  private val reader = TaskScreenReader(service)
  private val planner = TaskPlanner(service)
  private var engine: TaskEngine? = null
  private var ai: TaskAiChoice? = null
  private var dialog: AlertDialog? = null
  private var draft = ""
  private var closed = false
  private var voicePending = false
  private var voiceGeneration = 0L
  private val consumedKeys = mutableSetOf<Int>()
  private var lastState = TaskState.IDLE

  private fun apps(): List<TaskApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return service.packageManager.queryIntentActivities(intent, 0)
      .map { TaskApp(it.activityInfo.packageName, it.loadLabel(service.packageManager).toString()) }
      .distinctBy { it.packageName }.sortedBy { it.name }
  }

  fun show() {
    if (closed || dialog?.isShowing == true) return
    voicePending = false
    voiceGeneration++
    if (engine?.busy == true) engine?.cancel()
    if (engine?.state == TaskState.PAUSED) { showPaused(); return }
    showEntry()
  }

  private fun column(): LinearLayout = LinearLayout(service).apply {
    orientation = LinearLayout.VERTICAL
    val pad = (16 * resources.displayMetrics.density).toInt()
    setPadding(pad, pad, pad, pad)
  }

  private fun showEntry() {
    val content = column()
    val field = KeyboardSafeEditText(service).apply {
      hint = service.getString(R.string.task_goal)
      minLines = 2
      maxLines = 6
      setText(draft)
    }
    content.addView(field)
    content.addView(Button(service).apply {
      setText(R.string.task_speak)
      setOnClickListener {
        draft = field.text.toString()
        dismissDialog()
        voicePending = true
        val voiceToken = ++voiceGeneration
        runCatching {
          service.startActivity(Intent(service, TaskVoiceInputActivity::class.java).putExtra(TaskVoiceInputActivity.REQUEST_TOKEN, voiceToken).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { voicePending = false; announce(service.getString(R.string.task_voice_unavailable)); showEntry() }
      }
    })
    val picker = TaskAiPicker(service) { provider ->
      draft = field.text.toString()
      dismissDialog()
      TaskAiPicker.openSettings(service, provider)
    }
    content.addView(picker)
    content.addView(TextView(service).apply { setText(R.string.task_data_notice) })
    val builder = MaterialComponentUtils.alertDialogBuilder(service).setTitle(R.string.task_title).setView(ScrollView(service).apply { addView(content) })
      .setPositiveButton(R.string.task_start, null).setNegativeButton(R.string.task_close, null)
    val shown = display(builder) { draft = field.text.toString() }
    shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
      val goal = field.text.toString().trim()
      if (goal.isEmpty()) { field.error = service.getString(R.string.task_goal); return@setOnClickListener }
      if (goal.length > 4000) { field.error = service.getString(R.string.task_goal_too_long); return@setOnClickListener }
      val choice = TaskAiCatalog.selected(service)
      val problem = picker.problem()
      if (choice == null || problem != null) { announce(problem ?: service.getString(R.string.task_ai_missing)); return@setOnClickListener }
      draft = goal
      TaskAiCatalog.select(service, choice)
      ai = choice
      dismissDialog()
      voicePending = false
      voiceGeneration++
      if (engine?.active == true) engine?.cancel()
      engine = TaskEngine(this, apps())
      engine?.start(goal)
    }
  }

  private fun showPaused() {
    display(MaterialComponentUtils.alertDialogBuilder(service).setTitle(R.string.task_paused).setMessage(engine?.goal)
      .setPositiveButton(R.string.task_resume) { _, _ -> dismissDialog(); engine?.resume() }
      .setNeutralButton(R.string.task_new) { _, _ -> engine?.cancel(); dismissDialog(); showEntry() }
      .setNegativeButton(R.string.task_cancel) { _, _ -> engine?.cancel() })
  }

  private fun display(builder: AlertDialog.Builder, onDismiss: () -> Unit = {}): AlertDialog {
    dismissDialog()
    val created = builder.create()
    created.window?.let(DialogUtils::setWindowTypeToDialog)
    dialog = created
    created.setOnDismissListener {
      if (dialog === created) { dialog = null; onDismiss() }
    }
    created.show()
    return created
  }

  private fun dismissDialog() {
    val previous = dialog
    dialog = null
    previous?.dismiss()
  }

  private fun userInput(): Boolean {
    if (dialog != null) return false
    if (engine?.busy != true) return false
    engine?.cancel()
    return true
  }

  private fun keyEvent(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_UP) return consumedKeys.remove(event.keyCode)
    if (event.action != KeyEvent.ACTION_DOWN) return false
    if (event.keyCode in consumedKeys) return true
    return userInput().also { if (it) consumedKeys += event.keyCode }
  }

  private fun event(event: AccessibilityEvent) {
    if (engine?.busy == true && !canOperate()) { engine?.pause(); return }
    if (event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) userInput()
  }

  private fun canOperate(): Boolean = TalkBackService.isServiceActive() && !PauseController.isPaused() &&
    service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true

  override fun observe(done: (Result<TaskScreen>) -> Unit) {
    val token = epoch.get()
    main.postDelayed({
      if (closed || epoch.get() != token) return@postDelayed
      if (!canOperate()) {
        done(Result.failure(IllegalStateException("Điện thoại đang khóa hoặc Meridian đang tạm dừng")))
        return@postDelayed
      }
      worker.execute {
        val result = runCatching { reader.read().screen }
        main.post { if (!closed && epoch.get() == token) done(result) }
      }
    }, 700)
  }

  override fun createPrompt(goal: String, screen: TaskScreen, apps: List<TaskApp>, history: List<String>): String =
    if (ai?.provider == null) TaskPrompt.local(goal, screen, apps, history) else TaskProtocol.prompt(goal, screen, apps, history)

  override fun plan(prompt: String, done: (Result<String>) -> Unit) {
    val selected = ai ?: return done(Result.failure(IllegalStateException("Chưa chọn AI")))
    planner.request(prompt, selected, done)
  }

  override fun execute(screen: TaskScreen, step: TaskStep, done: (TaskExecution) -> Unit) {
    val token = epoch.get()
    worker.execute {
      val outcome = runCatching {
        val fresh = reader.read()
        if (closed || epoch.get() != token || !canOperate()) TaskExecution.STALE
        else {
          val effective: TaskStep? = when {
            step.node.isBlank() && step.label.isNotBlank() -> resolveLabeled(fresh, step)?.let { step.copy(node = it) }
            fresh.screen.fingerprint == screen.fingerprint -> step
            step.operation in setOf(TaskOperation.BACK, TaskOperation.HOME, TaskOperation.WAIT, TaskOperation.OPEN_APP) -> step
            else -> resolveTarget(screen, step, fresh)?.let { step.copy(node = it) }
          }
          if (effective == null) TaskExecution.STALE
          else {
            val performed = when (effective.operation) {
              TaskOperation.OPEN_APP -> service.packageManager.getLaunchIntentForPackage(effective.packageName)?.let {
                if (epoch.get() != token || !canOperate()) false else {
                  service.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
                }
              } ?: false
              TaskOperation.BACK -> if (epoch.get() == token && canOperate()) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) else false
              TaskOperation.HOME -> if (epoch.get() == token && canOperate()) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) else false
              TaskOperation.WAIT -> true
              else -> if (epoch.get() == token && canOperate()) reader.perform(fresh, effective) else false
            }
            if (performed) TaskExecution.PERFORMED else TaskExecution.FAILED
          }
        }
      }.getOrDefault(TaskExecution.FAILED)
      main.post { if (!closed && epoch.get() == token) done(outcome) }
    }
  }

  private fun resolveLabeled(fresh: CapturedTaskScreen, step: TaskStep): String? {
    val matches = fresh.screen.nodes.filter {
      matchesName(it.label, step.label) && (step.viewId.isEmpty() || it.viewId == step.viewId || it.viewId.isEmpty()) &&
        (step.role.isEmpty() || it.role == step.role) && step.operation.wire in it.actions &&
        (step.checked == null || it.checked == step.checked)
    }
    return if (matches.size == 1) matches[0].id else null
  }

  private fun resolveTarget(planned: TaskScreen, step: TaskStep, fresh: CapturedTaskScreen): String? {
    val target = planned.nodes.firstOrNull { it.id == step.node } ?: return null
    val matches = fresh.screen.nodes.filter {
      matchesName(it.label, target.label) && (it.viewId == target.viewId || it.viewId.isEmpty() || target.viewId.isEmpty()) &&
        it.role == target.role && step.operation.wire in it.actions &&
        (target.checked == null || it.checked == target.checked)
    }
    return if (matches.size == 1) matches[0].id else null
  }

  private fun matchesName(fresh: String, planned: String): Boolean {
    val a = TaskProtocol.normalize(fresh)
    val b = TaskProtocol.normalize(planned)
    if (a.isEmpty() || b.isEmpty()) return a == b
    if (a == b || a.contains(b) || b.contains(a)) return true
    val compactA = a.filter { it.isLetterOrDigit() }
    val compactB = b.filter { it.isLetterOrDigit() }
    return compactA.isNotEmpty() && compactB.isNotEmpty() &&
      (compactA == compactB || compactA.contains(compactB) || compactB.contains(compactA))
  }

  override fun changed(state: TaskState, message: String) {
    if (closed || state == lastState) return
    lastState = state
    if (state in setOf(TaskState.FINISHED, TaskState.FAILED, TaskState.CANCELLED, TaskState.PAUSED)) announce(message)
    if (state == TaskState.FINISHED || state == TaskState.FAILED) {
      display(MaterialComponentUtils.alertDialogBuilder(service).setTitle(R.string.task_result).setMessage(message)
        .setPositiveButton(R.string.task_close, null).setNeutralButton(R.string.task_new) { _, _ -> showEntry() })
    }
  }

  private fun announce(text: String) {
    service.speechController.speak(text, Performance.EVENT_ID_UNTRACKED,
      SpeechController.SpeakOptions.create().setQueueMode(SpeechController.QUEUE_MODE_QUEUE))
  }

  override fun cancelOperations() {
    epoch.incrementAndGet()
    planner.cancel()
    dismissDialog()
  }

  private fun close() {
    closed = true
    engine?.cancel()
    planner.close()
    worker.shutdownNow()
    dismissDialog()
  }

  companion object {
    private var instance: TaskAssistant? = null

    @JvmStatic fun show(service: TalkBackService) {
      val controller = instance ?: TaskAssistant(service).also { instance = it }
      controller.show()
    }

    @JvmStatic fun onAccessibilityEvent(event: AccessibilityEvent) { instance?.event(event) }

    @JvmStatic fun onGesture(): Boolean = instance?.userInput() == true

    @JvmStatic fun onKeyEvent(event: KeyEvent): Boolean = instance?.keyEvent(event) == true

    @JvmStatic fun closeService() { instance?.close(); instance = null }

    @JvmStatic fun onVoiceResult(token: Long, text: String?) {
      val controller = instance ?: return
      if (!controller.voicePending || controller.voiceGeneration != token || controller.closed || controller.engine?.active == true) return
      controller.voicePending = false
      if (text != null) controller.draft = text
      controller.main.postDelayed({ if (!controller.closed && controller.voiceGeneration == token && controller.engine?.active != true) controller.showEntry() }, 350)
    }
  }
}
