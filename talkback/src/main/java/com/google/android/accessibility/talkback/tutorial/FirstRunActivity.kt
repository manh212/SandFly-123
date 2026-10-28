package com.google.android.accessibility.talkback.tutorial

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.utils.SharedPreferencesUtils

class FirstRunActivity : AppCompatActivity() {
  private var questionStep = false
  private lateinit var titleView: TextView
  private lateinit var messageView: TextView
  private lateinit var continueButton: Button
  private lateinit var yesButton: Button
  private lateinit var noButton: Button

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_first_run)
    titleView = findViewById(R.id.first_run_title)
    messageView = findViewById(R.id.first_run_message)
    continueButton = findViewById(R.id.first_run_continue)
    yesButton = findViewById(R.id.first_run_yes)
    noButton = findViewById(R.id.first_run_no)
    continueButton.setOnClickListener { showQuestion() }
    yesButton.setOnClickListener { acceptTutorial() }
    noButton.setOnClickListener { declineTutorial() }
    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          if (questionStep) {
            showIntro()
          } else {
            declineTutorial()
          }
        }
      },
    )
    if (savedInstanceState?.getBoolean(KEY_QUESTION, false) == true) {
      showQuestion()
    } else {
      showIntro()
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putBoolean(KEY_QUESTION, questionStep)
  }

  private fun showIntro() {
    questionStep = false
    titleView.setText(R.string.meridian_intro_title)
    messageView.setText(R.string.meridian_intro)
    messageView.visibility = View.VISIBLE
    continueButton.visibility = View.VISIBLE
    yesButton.visibility = View.GONE
    noButton.visibility = View.GONE
    titleView.post { titleView.requestFocus() }
  }

  private fun showQuestion() {
    questionStep = true
    titleView.setText(R.string.first_run_tutorial_question)
    messageView.visibility = View.GONE
    continueButton.visibility = View.GONE
    yesButton.visibility = View.VISIBLE
    noButton.visibility = View.VISIBLE
    titleView.post { titleView.requestFocus() }
  }

  private fun acceptTutorial() {
    startActivity(TutorialActivity.intent(this, TutorialLessons.FIRST_LESSON))
    finish()
  }

  private fun declineTutorial() {
    val service = TalkBackService.getInstance()
    if (service != null) {
      service.onTutorialClosed()
    } else {
      SharedPreferencesUtils.getSharedPreferences(this)
        .edit()
        .putBoolean(TalkBackService.PREF_HAS_TRAINING_FINISHED, true)
        .apply()
    }
    finish()
  }

  companion object {
    private const val KEY_QUESTION = "question_step"
  }
}
