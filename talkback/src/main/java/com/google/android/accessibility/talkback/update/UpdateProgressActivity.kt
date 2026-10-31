/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.update

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.accessibility.talkback.R

class UpdateProgressActivity : Activity() {
  private val handler = Handler(Looper.getMainLooper())
  private var dialog: AlertDialog? = null
  private lateinit var progressBar: ProgressBar
  private lateinit var statusText: TextView

  private val refresh =
    object : Runnable {
      override fun run() {
        updateProgress()
        if (dialog?.isShowing == true && (Updater.isDownloading || Updater.isInstalling)) {
          handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val padding = (24 * resources.displayMetrics.density).toInt()
    val content =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
      }
    progressBar =
      ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        isIndeterminate = true
        contentDescription = getString(R.string.update_progress_title)
      }
    statusText = TextView(this)
    content.addView(
      progressBar,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )
    content.addView(
      statusText,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ).apply {
        topMargin = (16 * resources.displayMetrics.density).toInt()
      },
    )

    val progressDialog =
      AlertDialog.Builder(this)
        .setTitle(R.string.update_progress_title)
        .setView(content)
        .setNegativeButton(R.string.update_progress_dismiss, null)
        .setCancelable(true)
        .create()
    dialog = progressDialog
    progressDialog.setOnDismissListener {
      handler.removeCallbacks(refresh)
      finish()
    }
    progressDialog.show()
    updateProgress()
    handler.postDelayed(refresh, REFRESH_INTERVAL_MS)
  }

  override fun onDestroy() {
    handler.removeCallbacks(refresh)
    dialog = null
    super.onDestroy()
  }

  private fun updateProgress() {
    if (!::progressBar.isInitialized || !::statusText.isInitialized) return
    when {
      Updater.isDownloading -> {
        val percent = Updater.downloadPercent
        if (percent < 0) {
          progressBar.isIndeterminate = true
          statusText.setText(R.string.update_progress_indeterminate)
        } else {
          progressBar.isIndeterminate = false
          progressBar.progress = percent
          statusText.text = getString(R.string.update_progress_percent, percent)
        }
      }
      Updater.isInstalling -> {
        progressBar.isIndeterminate = false
        progressBar.progress = 100
        statusText.setText(R.string.update_progress_installing)
      }
      else -> {
        progressBar.isIndeterminate = false
        progressBar.progress = 100
        statusText.setText(R.string.update_progress_finished)
      }
    }
  }

  private companion object {
    const val REFRESH_INTERVAL_MS = 500L
  }
}
