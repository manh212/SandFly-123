package com.google.android.accessibility.talkback.taskassistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.gemini.AiProvider
import com.google.android.accessibility.talkback.actor.gemini.AiProviderFragment
import com.google.android.accessibility.talkback.actor.gemini.GeminiSettingsFragment
import com.google.android.accessibility.talkback.actor.gemini.GeminiModel
import com.google.android.accessibility.talkback.actor.gemini.local.LocalModel
import com.google.android.accessibility.talkback.actor.gemini.local.LocalModelManager
import com.google.android.accessibility.talkback.actor.gemini.local.OnDeviceAiSettings
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.material.MaterialComponentUtils
import com.google.android.accessibility.utils.preference.BasePreferencesActivity
import com.google.android.accessibility.utils.widget.DialogUtils
import java.util.concurrent.Executors

class TaskAiPicker(context: Context, private val configure: (AiProvider) -> Unit = { openSettings(context, it) }) : LinearLayout(context) {
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)
  private val manager = LocalModelManager.get(context)
  private val main = Handler(Looper.getMainLooper())
  private val providers = AiProvider.entries.filter { it != AiProvider.NONE }
  private val mode = Spinner(context)
  private val provider = Spinner(context)
  private val model = Button(context)
  private val reload = Button(context)
  private val download = Button(context)
  private val settings = Button(context)
  private val status = TextView(context)
  private var generation = 0L
  private var loading = false
  private var failure: String? = null
  private var child: AlertDialog? = null
  private var cachedScope = ""
  private var cachedModels = emptyList<TaskRemoteModel>()
  private val listener: () -> Unit = { if (isAttachedToWindow) refresh() }
  private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    if (key?.contains("api_key") == true || key == AiProvider.PREF_OTHER_ADDRESS || key == GeminiModel.PREF_KEY ||
      AiProvider.entries.any { it.modelPref == key }) {
      main.post { if (isAttachedToWindow) { invalidateLoad(); refresh(); autoLoad() } }
    }
  }

  init {
    orientation = VERTICAL
    mode.contentDescription = context.getString(R.string.task_processing_mode)
    mode.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
      listOf(context.getString(R.string.task_cloud), context.getString(R.string.task_local)))
    mode.setSelection(if (TaskAiCatalog.isLocal(prefs)) 1 else 0)
    provider.contentDescription = context.getString(R.string.task_service)
    provider.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, providers.map { context.getString(it.label) })
    provider.setSelection(providers.indexOf(TaskAiCatalog.provider(prefs)).coerceAtLeast(0))
    listOf(mode, provider, model, reload, download, settings, status).forEach(::addView)
    reload.setText(R.string.task_reload_models)
    model.setOnClickListener { if (TaskAiCatalog.isLocal(prefs)) chooseLocal() else chooseCloud() }
    reload.setOnClickListener { loadModels(true) }
    download.setOnClickListener { downloadClicked() }
    settings.setOnClickListener { configure(TaskAiCatalog.provider(prefs)) }
    mode.onItemSelectedListener = selection { index ->
      if ((index == 1) != TaskAiCatalog.isLocal(prefs)) {
        TaskAiCatalog.setLocal(prefs, index == 1)
        invalidateLoad()
        refresh()
        autoLoad()
      }
    }
    provider.onItemSelectedListener = selection { index ->
      if (providers[index] != TaskAiCatalog.provider(prefs)) {
        TaskAiCatalog.setProvider(prefs, providers[index])
        invalidateLoad()
        refresh()
        autoLoad()
      }
    }
    refresh()
  }

  private fun selection(selected: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { selected(position) }
    override fun onNothingSelected(parent: AdapterView<*>?) { }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    manager.addListener(listener)
    prefs.registerOnSharedPreferenceChangeListener(preferencesListener)
    refresh()
    autoLoad()
  }

  override fun onDetachedFromWindow() {
    invalidateLoad()
    manager.removeListener(listener)
    prefs.unregisterOnSharedPreferenceChangeListener(preferencesListener)
    child?.dismiss()
    child = null
    super.onDetachedFromWindow()
  }

  private fun invalidateLoad() { generation++; loading = false; failure = null; cachedScope = "" }

  private fun availableModels(service: AiProvider): List<TaskRemoteModel> {
    val scope = TaskModelDirectory.currentScope(prefs, service)
    if (cachedScope != scope) {
      cachedModels = TaskModelDirectory.cached(prefs, service)
      cachedScope = scope
    }
    return cachedModels
  }

  private fun autoLoad() {
    val service = TaskAiCatalog.provider(prefs)
    if (!TaskAiCatalog.isLocal(prefs) && TaskAiCatalog.canConnect(prefs, service) &&
      availableModels(service).isEmpty() && !loading) loadModels(false)
  }

  fun problem(): String? {
    if (TaskAiCatalog.isLocal(prefs)) {
      val selected = TaskAiCatalog.localModel(prefs)
      if (!selected.writesJson) return context.getString(R.string.task_model_incompatible)
      if (support(selected) != OnDeviceAiSettings.Support.OK) return context.getString(R.string.task_local_unsupported)
      if (!manager.store.isInstalled(selected)) return context.getString(R.string.task_model_not_downloaded)
    } else {
      val service = TaskAiCatalog.provider(prefs)
      if (!TaskAiCatalog.canConnect(prefs, service)) return context.getString(R.string.task_service_not_ready)
      if (TaskAiCatalog.model(prefs, service).isEmpty()) return context.getString(R.string.task_choose_model)
    }
    return null
  }

  private fun refresh() {
    val local = TaskAiCatalog.isLocal(prefs)
    provider.visibility = if (local) GONE else VISIBLE
    reload.visibility = if (local) GONE else VISIBLE
    settings.visibility = if (local) GONE else VISIBLE
    download.visibility = if (local) VISIBLE else GONE
    if (!local) {
      val service = TaskAiCatalog.provider(prefs)
      val id = TaskAiCatalog.model(prefs, service)
      model.text = context.getString(R.string.task_current_model, id.ifBlank { context.getString(R.string.task_not_chosen) })
      settings.text = context.getString(R.string.task_configure_service, context.getString(service.label))
      model.isEnabled = !loading && TaskAiCatalog.canConnect(prefs, service)
      reload.isEnabled = model.isEnabled
      status.text = if (loading) context.getString(R.string.task_models_loading) else failure ?: problem()
        ?: context.getString(R.string.task_models_count, availableModels(service).size)
      return
    }
    val selected = TaskAiCatalog.localModel(prefs)
    model.text = context.getString(R.string.task_current_model, selected.name)
    model.isEnabled = true
    val downloading = manager.state as? LocalModelManager.State.Downloading
    val installed = manager.store.isInstalled(selected)
    download.setText(when {
      downloading?.model == selected -> R.string.task_pause_model_download
      manager.store.partialBytes(selected) > 0 && !installed -> R.string.task_resume_model_download
      else -> R.string.task_download_model
    })
    download.isEnabled = support(selected) == OnDeviceAiSettings.Support.OK && !installed &&
      manager.state != LocalModelManager.State.Importing && (downloading == null || downloading.model == selected)
    status.text = when {
      downloading != null -> context.getString(R.string.task_download_progress, downloading.model.name,
        (100L * downloading.bytes / downloading.model.sizeBytes).coerceIn(0, 100).toInt(), size(downloading.bytes), size(downloading.model.sizeBytes))
      manager.state == LocalModelManager.State.Importing -> context.getString(R.string.task_model_importing)
      manager.state is LocalModelManager.State.Failed && !installed -> context.getString(R.string.task_download_failed,
        downloadFailure((manager.state as LocalModelManager.State.Failed).failure))
      installed -> context.getString(R.string.task_model_installed, size(selected.sizeBytes))
      else -> problem() + " · " + size(selected.sizeBytes)
    }
  }

  private fun support(model: LocalModel): OnDeviceAiSettings.Support = manager.support(model).let {
    if (it == OnDeviceAiSettings.Support.NOT_ENOUGH_RAM && OnDeviceAiSettings.ignoreMemoryLimits(prefs)) OnDeviceAiSettings.Support.OK else it
  }

  private fun chooseCloud() {
    val service = TaskAiCatalog.provider(prefs)
    val available = availableModels(service)
    if (available.isEmpty()) { loadModels(true); return }
    val id = TaskAiCatalog.model(prefs, service)
    val models = if (id.isNotBlank() && available.none { it.id == id }) listOf(TaskRemoteModel(id, "Đang chọn, chưa có trong danh sách dịch vụ")) + available else available
    show(MaterialComponentUtils.alertDialogBuilder(context).setTitle(R.string.task_choose_model)
      .setSingleChoiceItems(models.map { it.label }.toTypedArray(), models.indexOfFirst { it.id == id }) { dialog, index ->
        val chosen = models[index]
        TaskAiCatalog.select(context, TaskAiChoice(service.id, chosen.label, chosen.id, service))
        dialog.dismiss()
        refresh()
      }.setNegativeButton(R.string.task_close, null))
  }

  private fun chooseLocal() {
    val models = LocalModel.entries
    val labels = models.map {
      val state = when {
        !it.writesJson -> context.getString(R.string.task_model_incompatible)
        support(it) != OnDeviceAiSettings.Support.OK -> context.getString(R.string.task_local_unsupported)
        manager.store.isInstalled(it) -> context.getString(R.string.task_downloaded)
        else -> context.getString(R.string.task_not_downloaded)
      }
      "${it.name} · ${size(it.sizeBytes)} · $state"
    }
    show(MaterialComponentUtils.alertDialogBuilder(context).setTitle(R.string.task_choose_model)
      .setSingleChoiceItems(labels.toTypedArray(), models.indexOf(TaskAiCatalog.localModel(prefs))) { dialog, index ->
        val chosen = models[index]
        if (!chosen.writesJson || support(chosen) != OnDeviceAiSettings.Support.OK) {
          Toast.makeText(context, labels[index], Toast.LENGTH_LONG).show()
        } else {
          TaskAiCatalog.setLocalModel(prefs, chosen)
          dialog.dismiss()
          refresh()
        }
      }.setNegativeButton(R.string.task_close, null))
  }

  private fun loadModels(openAfter: Boolean) {
    val service = TaskAiCatalog.provider(prefs)
    if (loading || !TaskAiCatalog.canConnect(prefs, service)) return
    val token = ++generation
    val scope = TaskModelDirectory.currentScope(prefs, service)
    loading = true
    failure = null
    refresh()
    executor.execute {
      val result = runCatching { TaskModelDirectory.load(prefs, service) }
      main.post {
        if (!isAttachedToWindow || token != generation) return@post
        loading = false
        if (TaskModelDirectory.currentScope(prefs, service) != scope) {
          failure = context.getString(R.string.task_service_not_ready)
          refresh()
          return@post
        }
        result.fold({ models ->
          TaskModelDirectory.remember(prefs, service, models, scope)
          cachedScope = scope
          cachedModels = models
          if (models.isEmpty()) failure = context.getString(R.string.task_models_empty)
          refresh()
          if (openAfter && models.isNotEmpty()) chooseCloud()
        }, { error ->
          failure = context.getString(R.string.task_models_failed, TaskAiErrors.listFailure(error))
          refresh()
        })
      }
    }
  }

  private fun downloadClicked() {
    val model = TaskAiCatalog.localModel(prefs)
    if ((manager.state as? LocalModelManager.State.Downloading)?.model == model) { manager.pauseDownload(); return }
    if (!model.writesJson || support(model) != OnDeviceAiSettings.Support.OK) return
    show(MaterialComponentUtils.alertDialogBuilder(context).setTitle(R.string.task_download_model)
      .setMessage(context.getString(R.string.task_download_confirm, model.name, size(model.sizeBytes)))
      .setPositiveButton(R.string.task_download_model) { _, _ -> manager.startDownload(model); refresh() }
      .setNegativeButton(R.string.task_close, null))
  }

  private fun size(bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

  private fun downloadFailure(failure: com.google.android.accessibility.talkback.actor.gemini.local.ModelFiles.Failure): String = when (failure) {
    com.google.android.accessibility.talkback.actor.gemini.local.ModelFiles.Failure.LOW_SPACE -> "Không đủ dung lượng lưu trữ"
    com.google.android.accessibility.talkback.actor.gemini.local.ModelFiles.Failure.HASH_MISMATCH,
    com.google.android.accessibility.talkback.actor.gemini.local.ModelFiles.Failure.SIZE_MISMATCH -> "Tệp tải về chưa hợp lệ. Hãy tải lại"
    com.google.android.accessibility.talkback.actor.gemini.local.ModelFiles.Failure.STORAGE -> "Không ghi được tệp mô hình"
    com.google.android.accessibility.talkback.actor.gemini.local.ModelFiles.Failure.TIME_LIMIT -> "Android đã dừng tải nền. Bạn có thể tiếp tục tải"
    else -> context.getString(R.string.task_check_connection)
  }

  private fun show(builder: AlertDialog.Builder) {
    child?.dismiss()
    val created = builder.create()
    if (context is AccessibilityService) created.window?.let(DialogUtils::setWindowTypeToDialog)
    child = created
    created.setOnDismissListener { if (child === created) child = null }
    created.show()
  }

  companion object {
    private val executor = Executors.newCachedThreadPool { run -> Thread(run, "task-model-list").apply { isDaemon = true } }

    fun openSettings(context: Context, provider: AiProvider) {
      context.startActivity(Intent(context, TalkBackPreferencesActivity::class.java)
        .putExtra(BasePreferencesActivity.FRAGMENT_NAME, if (provider == AiProvider.GEMINI) GeminiSettingsFragment::class.java.name else AiProviderFragment::class.java.name)
        .putExtra(BasePreferencesActivity.FRAGMENT_ARGS, Bundle().apply { putString(AiProviderFragment.ARG_PROVIDER, provider.id) })
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }
}
