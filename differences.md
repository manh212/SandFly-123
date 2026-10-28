# Differences from TalkBack

Meridian is built from Backtalk, which is built from Google's TalkBack source releases. This document describes how Meridian differs from TalkBack: features that TalkBack doesn't have, defaults that Meridian changes, and problems in TalkBack that Meridian fixes. To learn how to install Meridian, see the [README](README.md).

Each section names the settings involved as they appear in Meridian. Unless a section says otherwise, each setting is in Meridian settings.

## Speed

### Faster speech after a swipe

When you swiped to an item, TalkBack waited for the app to confirm the new focus before it put together what to say. Meridian speaks the item as soon as it has set focus, and does less other work around each swipe. On a Galaxy Z Fold7, the median time from lifting your finger to hearing speech went from about 68 ms to about 39 ms.

This doesn't apply when focus moves to another window, when the screen changes, or during continuous reading. Keys on the keyboard, sliders, progress bars, pages in a pager, and web content still wait for the app.

### Next swipe prepared in advance

While you listen to an item, Meridian works out what a swipe forward or back would say. When your swipe lands on the item that Meridian prepared, speech starts immediately: on average 33 ms after the swipe, compared with 60 ms without preparation. In testing, about four in five swipes used prepared speech.

Meridian doesn't prepare anything for the first swipe on a screen or after the screen changes, and it discards prepared speech after 5 seconds.

### Items that a swipe scrolls into view

When a swipe moves to a list item that is only partly on screen, the list scrolls it into view. By default, Meridian then waits for the app to confirm the new focus, as TalkBack does, so these swipes don't get the faster speech above. Speaking straight away would come before the list had scrolled, when the item's text can still be off screen, and only its place in the list, such as "20 of 80", would be said.

When you turn on **Controls** > **Touch and activation** > **Reduce delay** > **Speak items before they scroll into view**, Meridian speaks these items straight away too, including the parts that are still past the edge of the list, since they will be on screen once it has scrolled. Parts the app has hidden are still left out. Apps that follow Android's conventions work correctly with this setting on. It is off by default to protect you from apps that don't: in those, a part that the app keeps out of sight by moving it outside the item, instead of hiding it, is read even though it isn't shown, and so is a part that stays off screen when the list can't scroll the whole item into view.

### Reading order kept between swipes

TalkBack asked the app for every item on the screen on each swipe, and waited for each answer. Meridian keeps the reading order between swipes, and builds it again only when the screen changes, or when Meridian scrolls or clicks. Changes to text or state, such as a clock that ticks or a progress bar that moves, don't discard the order.

To find the next item in a list, TalkBack also built the reading order of the whole screen three times for one swipe: once to check whether focus was at the end of the list, once to find the item, and once to check whether the item needed to be scrolled into view. Meridian builds it once and reuses it.

In a test on a Realme phone, three in four swipes reused the reading order, and the longest pause during swiping went from about 450 ms to about 150 ms.

### Low-latency audio

Android's usual audio path adds about 60 to 70 ms before a sound or speech reaches the speaker. When you turn on **Sound and vibration** > **Low-latency audio**, Meridian plays its sounds and speech through a single fast audio track at your phone's own sample rate. On a Galaxy Z Fold7, this path takes about 21 ms.

Low-latency audio also changes how pausing speech works. When you tap with two fingers, speech stops mid-word, and when you resume, it carries on from exactly where it stopped. Meridian holds up to 10 seconds of paused speech.

With low-latency audio on, Meridian also watches for a speech engine that stops responding. If the engine says nothing about new speech for 3 seconds, Meridian switches to the next installed engine.

Low-latency audio is on by default. If you used an earlier version of Meridian with the setting off, Meridian turns it on once when you update. If speech or sounds break up, turn the setting off. Low-latency speech requires Android 11 or later; on older versions, only Meridian's sounds use the fast path. Speech falls back to the usual path for engines that play their own audio or that report errors. During calls, and for 10 seconds after a call starts or ends, speech also plays the usual way. Each sound plays the usual way the first time that Meridian uses it, and 3D sounds still play through the 3D player.

### Always interrupt speech

Ordinarily, speech is queued: an announcement waits until the ones before it have finished, so a new announcement can be heard seconds after it was made. When you turn on **Speech** > **Always interrupt speech**, every new announcement instead stops the one currently speaking and clears the ones waiting, so you always hear the newest announcement straight away. Announcements that Meridian itself marks as uninterruptible are still left to finish. The setting is off by default; turn it off again if announcements you want to hear in full get cut off.

### Less lag while scrolling

After each scroll event, TalkBack searched the list for a new item to focus. The search blocked touch exploration and could make gestures fail. Meridian searches once, after scrolling stops.

### Less stalling in busy apps

In busy apps, a backlog of accessibility events could block gestures for almost 3 seconds. Meridian handles events in slices of about 16 ms, so gestures get through between them. It also skips lookups of view classes that can never succeed, which stalled screens that have many custom views.

### Faster response on empty space

When you touched an empty part of the screen, TalkBack waited 100 ms after the focus delay before it played the sound. Meridian waits 40 ms.

### Faster screen changes

After a window changes, TalkBack waits for the window to settle before it says the title. TalkBack counted that wait in a way that made 550 ms last about 900 ms. Meridian counts the real time.

The **Turn off animations** setting in **Controls** > **Touch and activation** > **Reduce delay** turns off animations for the whole phone, so screens change immediately and the wait is only 50 ms. TalkBack called this setting **Reduce window announcement delay**, and had disabled the code that turns animations off, so the setting did nothing. Meridian makes the setting work, and turns animations back on when you turn Meridian off. On Android 12 and earlier, which don't let Meridian turn animations off, the setting only shortens the wait. The setting is on by default.

The wait also ends early when it can. While waiting, Meridian looks at the windows every 10 ms or so, and if two looks in a row see the same windows and every changed window already has a title in the window list, the title is said at once — after about 20 ms — instead of waiting out the whole delay. Windows appear in the list with their titles right away in modern apps, so this is the usual case. The full delay stays as the limit for a window that has no title yet, and placing the first focus in the new window ends with the same early announcement instead of the full delay. A pane title that only arrives after the early announcement is announced when it comes, so such a window can be named twice: once by its own title, then by the pane's.

When you activated an item, or pressed a key, while the item was being spoken or just after, TalkBack still spoke its usage hint, such as "Double-tap to activate", and the name of the new window waited for the hint. Meridian drops the hint.

TalkBack speaks the window name only after the speech before it, and the first item in the new window only after the window name. **Verbosity** > **Window names** chooses when window names are spoken:

*   **Always**, the default, as TalkBack does.
*   **Always, cut off by the first item**: the window name interrupts the speech before it, and the first item interrupts the window name as soon as it is focused, so usually only the window sound is heard before the first item.
*   **Only when a window opens**: no window name when focus moves into another window, such as the keyboard.
*   **Never**: a screen change plays only the window sound.

### Swiping into the next part of a list

When the next item was only partly on screen, TalkBack scrolled it into view, waited for the scroll before it spoke, and then waited another 110 ms in case more scroll events arrived. Meridian speaks the item immediately and lets the scroll finish while it speaks.

When the next item is off screen, Meridian checks once per frame whether the list has moved, rather than wait for the scroll event, which Android sends at most every 100 ms. On a Samsung phone, swipes that scroll went from about 310 ms to about 60 ms when the item was partly on screen, and from about 320 ms to about 140 ms when the list had to scroll a page.

Meridian also finds the end of a list that's laid out in reverse, such as a chat, correctly.

### Scroll views that show the next screen immediately

Some screens are one long page rather than a list, and keep all of their content in the accessibility tree. On these screens, when you swipe past the last item on screen, Meridian scrolls straight to the next screen without the animation, so that the next item is at the top, and searches again immediately. An item that was cut off at the edge of the screen is read rather than scrolled past, as TalkBack's page scroll did.

This requires Android 14 or later. Lists, grids, pagers, and Compose screens still scroll a page at a time, because they create only the items on screen or they animate their scrolling.

### Focus that stays where a swipe's scroll put it

A list can keep scrolling for a moment after a swipe has moved focus to the next item. TalkBack treated the rest of that scroll as a scroll that you made, and moved focus on to the next item by itself. Meridian counts it as part of its own scroll. As a result, if you scroll the same list yourself within a second of a swipe's scroll, focus doesn't move.

### Swiping away from long paragraphs

Some speech engines, such as Gryphon, stop only between the blocks of text that they synthesize, and treat a whole item as one block. When you swiped away from a long paragraph on a web page, the next item could wait up to 850 ms for the engine to stop.

To fix this, turn on **Text-to-speech** > **Send long text a sentence at a time**. Meridian then sends text that's longer than 150 characters about a sentence at a time, so the engine stops immediately. In Chrome on a Samsung phone with Gryphon, web swipes went from as much as 900 ms to at most about 110 ms.

The setting is off by default, because many engines, such as RHVoice, already stop immediately, and they pause longer between sentences that are sent separately.

## Speech

### Text-to-speech settings

In TalkBack, **Text-to-speech** opens Android's system speech settings. Meridian has its own **Text-to-speech** screen, under **Feedback** in Meridian settings. On this screen, you can choose a speech engine for Meridian that's separate from Android's default engine, and set the speech volume, rate, and pitch with sliders. It also links to Android's system settings, and holds the speech settings that are described in the following sections.

### Speech engine reading control

The **Speech engine** reading control switches between your installed speech engines when you swipe up or down. It changes the engine of **Meridian default**, so it's unavailable while a voice profile is in use. It isn't in the reading controls by default. To add it, go to **Reading controls**.

### Voice profiles

A voice profile is a set of speech settings that you can switch to at once: a speech engine, language, voice, volume, rate, pitch, and **Send long text a sentence at a time**. For example, one profile can use a slower rate for long text while **Meridian default** stays fast. To add one, go to **Text-to-speech** > **Voice profiles** and choose **Add voice profile**. When you choose a language, the voice list shows only the engine's voices in that language.

To switch profiles, swipe up or down with the **Voice profile** reading control, or choose **Voice profile in use** on the **Voice profiles** screen. The **Voice profile** reading control is separate from the **Speech engine** reading control. It isn't in the reading controls by default. To add it, go to **Reading controls**. You can also add a **Voice profile** item to the Meridian menu in **Meridian menu** settings, which lists the profiles and says which is in use. The **Choose voice profile**, **Previous voice profile** and **Next voice profile** actions can be assigned to gestures and keyboard shortcuts.

The reading control goes through the profiles in the order of the **Voice profiles** screen, after **Meridian default**. To change the order, hold a profile and drag it, or use the **Move up** and **Move down** actions. Meridian says which profile it moved above or below. Profiles also have **Rename** and **Delete** actions, which a long press shows too.

**Meridian default** is always there, and uses the rest of the text-to-speech settings. While a profile is in use, changing the speech rate or pitch with gestures or the reading controls changes that profile only. All speech uses the profile's engine and voice, so Meridian doesn't switch language or dialect, and the **Spoken language** reading control and menu item are hidden.

### Speech volume

TalkBack always plays speech at the accessibility volume. In Meridian, **Text-to-speech** > **Speech volume** chooses the volume that speech and Meridian's sounds play at:

- **Accessibility volume**, the default.
- **Media volume**.
- **Ring volume**: speech plays as a ringtone does, so it stays on the phone when media is cast to another device. Like a ringtone, it can also play on the phone's speaker while headphones are connected, and it is silent when the ringer is.
- **Call volume, earpiece**: speech plays as a call does, on the earpiece when no headset is connected.

The **Speech volume** reading control, which you can turn on in **Reading controls**, switches between them. While **Audio output device** sends speech to a device other than the system default, speech uses the accessibility volume.

### Automatic language and dialect changes

When text is marked as another language, TalkBack switches the voice to that language. Meridian lets you turn this off. In **Text-to-speech**, **Switch language automatically** controls changes to another language, and **Switch dialect automatically** controls changes to another country's form of the voice's language, such as US English text with a British English voice. Both are on by default.

When you turn a change off, Meridian no longer splits the text or pauses around it. Text marked as the voice's own language and country, such as US English for a US English voice, isn't split either, whatever the settings, since the voice doesn't change. Meridian compares languages by their codes only, so Serbian in Latin and in Cyrillic script counts as two dialects, and Norwegian marked as `no` and as `nb` counts as two languages.

### How emoji are spoken

Some speech engines read emoji badly, use names of their own, or don't read them at all. **Verbosity** > **Emoji** chooses how they're spoken:

*   **Read by speech engine**, the default, gives the engine the emoji as TalkBack did.
*   **Read by Meridian** replaces each emoji with its name from the [Unicode CLDR](https://cldr.unicode.org/), in the language the text is spoken in, for 138 languages and regional variants. A skin tone, a family, a flag or a keycap is one emoji with one name.
*   **None** leaves emoji out, except in text that is only emoji, such as a reaction button or a character, which is still named.

With **Read by Meridian**, **Count repeated emoji** says an emoji repeated in a row once with a count, such as "3 grinning face", from 2 to 6 repeats. Moving by character names a whole emoji once, and moving by word stops on emoji. Copying or spelling the last spoken phrase gives the emoji, not their names. You can also add **Emoji** to the reading controls and the Meridian menu.

With **None**, moving by word skips emoji, as moving by character still names them. Emoji newer than Meridian's names, Emoji 18.0, are left to the speech engine. Some languages lack CLDR names for some emoji, which are then spoken in English.

### Resume speech where you paused it

With speech engines that don't report word positions, such as RHVoice and Gryphon, TalkBack started the text over when you resumed speech. Meridian learns how fast your engine speaks from the items that it finishes, works out how far the engine got when you paused, and resumes from the start of that sentence or phrase. It can repeat a few words, but it never skips any. Engines that report word positions still resume from the word, and with [low-latency audio](#low-latency-audio) on, speech resumes mid-word.

When speech resumes, Meridian doesn't play the item's sound and vibration again.

### Two-finger tap right after a swipe

TalkBack didn't recognize a two-finger tap made right after a swipe, or it paused older speech instead of the current item. Meridian recognizes the tap and pauses the speech for the item that you swiped to.

### Keys that stop speech

In TalkBack, only Ctrl on a physical keyboard stops speech, so a window name or an announcement held up the item that a key moved to. In Meridian, every key on a physical keyboard stops speech, except modifier keys such as Shift. Ctrl, Alt and Meta shortcuts always stop it. In **Verbosity**:

*   **Interrupt speech when typing** chooses when typed characters stop speech: **Always**, the default, **In edit fields**, or **On keyboard echo**, only when the keyboard echo says what you typed.
*   **Interrupt speech on Enter**, on by default.

A window name that a key stopped isn't spoken later. Pressing Ctrl alone pauses speech, and Meridian keeps the key from the app, so that an app such as YouTube doesn't show its keyboard shortcuts. A shortcut with Ctrl still reaches the app. An app that needs Ctrl alone, such as a remote desktop app, doesn't get it.

### Speech while an app records

While another app records from the microphone without headphones, and during calls, TalkBack dropped announcements that you asked for with a gesture or key. Meridian speaks them: the time and date, the battery, the status, the window title, phonetic letters, **Inspect focused item**, and turning scripts on or off.

With **Text-to-speech** > **Quiet while recording** on, Meridian stays quiet while an app records without headphones, except for speech within two seconds of a touch, gesture or key, so that notifications and other speech you didn't ask for aren't recorded. It's off by default.

### Speak volume changes

To stop Meridian from saying the new volume after you press the volume keys, turn off **Verbosity** > **Speak volume changes**. It's on by default.

### Keyboard echo

**Keyboard echo**, under **Typing**, sets the echo for each way of typing: **On-screen keyboard** and **Physical keyboard**, each with **None**, **Characters**, **Words**, or **Characters and words**. In TalkBack, keyboard echo is in **Verbosity**. Each echo setting is also a reading control and a Meridian menu item, which you can turn on in **Reading controls** and **Meridian menu**. Keyboard echo isn't part of the verbosity presets. When you update, Meridian copies your earlier echo settings once.

Word echo says the whole word, back to the last space, as it is in the field. TalkBack said only the part typed since the last punctuation, such as "normal" for "non-normal", and only the changed letters of a corrected word. Words with apostrophes or hyphens, such as "don't", are one word, and a web or email address is one word. When a keyboard adds a word, its punctuation, and a space all at once, Meridian echoes the word rather than just the space.

### Order of item details

**Verbosity** > **Order of item details** sets the order in which Meridian says an item's name, type, and state. TalkBack has this setting in **Advanced settings** and offers three orders to choose from. Meridian lists the name, type, and state, and you put them in any of the six orders: touch and hold a detail and drag it up or down, or use its **Move up** and **Move down** actions. With Meridian on, double-tap and hold a detail, then drag it. Meridian says the detail's position as you pick it up, move it, and drop it.

TalkBack ignored the order for a row whose checkbox or switch has no text of its own, such as the rows in **Reading controls**, and always read the checkbox's type and state before the row's text. Meridian reads such a row as one control, with the row's text as its name, in the order that you chose. A button or tab whose label is a separate view inside it, as in the Pixel power menu, also follows the order, with the label as its name.

### "Meridian off" at the right volume

When you turn Meridian off, it says "Meridian off" at the accessibility volume, using your device's real volume levels. TalkBack only approximated that volume.

### Table column headers

In **Verbosity**, you can choose whether Meridian reads table column headers before or after the cell's data, or leaves them out:

*   **After cell data**, the default, keeps TalkBack's order: the cell's contents, then the row and column headers or numbers.
*   **Before cell data** speaks the row and column headers or numbers before the cell's contents, along with "Column heading" or "Row heading" for a header cell. This applies to grids as well as tables, so an item in a photo grid says its row and column first.
*   **Do not read** leaves column headers out, and reads column numbers only when row and column numbers are on.

Under preset settings in **Verbosity**, you can also turn off **Speak row and column numbers** to hear only named headers without row and column coordinates. The setting is on by default.

Both settings only apply while **Speak container info** is on, because rows and columns aren't spoken at all when it's off.

### Speak letters phonetically

TalkBack's **Speak letters with examples** switch is called **Speak letters phonetically** in Meridian, in **Verbosity** under preset settings, and in on-screen keyboard settings. It has four choices:

*   **Off** says only the letter.
*   **After the letter, delayed**, the default, is what the switch did when it was on. Keyboard keys say the letter, then its phonetic word, as in "h, hotel". Moving by character, the word comes a quarter of a second after the letter, and moving on before then skips it.
*   **After the letter, no delay** says the letter and its word together, as in "h hotel".
*   **Instead of the letter** says only the word, as in "hotel".

With **After the letter, no delay** and **Instead of the letter**, Meridian says it in all of these places. **After the letter, delayed** still ignores **Spell last spoken phrase**, deleting a letter, and inserting a letter with a touch keyboard like Gboard, just as TalkBack did when **Speak letters with examples** was turned on.

The **High** preset uses **After the letter, delayed** and the **Low** preset uses **Off**. Your choice from the switch carries over.

### Translate last spoken utterance

The **Translate last spoken utterance** action translates the last phrase that Meridian said and speaks the translation. You can assign it to a gesture or a keyboard shortcut, or add it to the Meridian menu. Meridian keeps only the most recent utterance, in memory: there is no history, and nothing is kept after Meridian stops.

Translation runs on the phone, with ML Kit, so it works without a network connection. **Feedback** > **Offline translation** chooses the source and target languages and downloads the language pack for the pair. Until the pack is downloaded, the action says that it isn't.

## Notifications

### Samsung watch notification content

This fork has no watch version, so there are no watch notification cards to read. See [Wear OS watches](#wear-os-watches).

### Reading notifications

TalkBack reads each new notification with a fixed format and has no settings for it. In Meridian, **Notifications** is a category of its own in Meridian settings. **Speak notifications** turns reading new notifications on or off, and is on by default. Incoming calls are always read. The other screens are:

*   **Reading notifications**: **Speech format**, with the placeholders `{app}`, `{title}`, `{text}`, `{content}`, `{bigtext}`, `{subtext}`, `{ticker}`, `{category}` and `{time}`, and what private notifications say. **Voice** reads notifications with one of your [voice profiles](#voice-profiles), with all of its settings, instead of Meridian's voice. **Detect language** reads each notification in the language it's written in. When notifications for different voices arrive together, each waits for the one before it, so that two voices don't speak at once. Also a sound before notifications, emoji, capitals, long numbers, links, length limits in words, sentences, characters or seconds, a wait before reading, and what happens when a notification arrives while another is read.
*   **Apps**: for each app, **As other apps**, **Read**, **App name only**, **Read first** or **Don't read**, and a name, minimum time between notifications, speech format and **Voice** of its own. **Voice** reads the app's notifications with one of your voice profiles, such as one with another speech engine, or with Meridian's voice.
*   **Words and replacements**: words that a notification must have to be read, words that keep it from being read, and text to say differently.
*   **Kinds of notifications**: skip media players, ongoing notifications, running app notifications, progress notifications, group summaries, and low priority and system notifications.
*   **When to read**: with the screen on, off or locked, with or without headphones, while media plays, in Do Not Disturb, while the screen is touched, during calls, in silent, vibrate or car mode, and quiet hours.
*   **Rules**: conditions, such as a Bluetooth device or Wi-Fi network being connected, the battery, charging, the time, the app in front, the app or text of the notification, and actions, such as not reading it, reading only the app name, another speech format, reading it with a voice profile, or reading it first. A rule's voice takes the place of the app's. **Add a rule from a template** starts from rules such as **Silent at night**.
*   **Repeats**: skip the same notification for a while, a minimum time between an app's notifications, and leave out a start that repeats, such as a group name in a chat.
*   **Stop reading**: shake, wave over the proximity sensor, press both volume keys, dismiss the notification, or the app removes it.
*   **Summary and time**: read the notifications in the shade now or every day at a set time. Saying the time at a set interval is in [Timer settings](#timer-settings).
*   **History**: the last 100 notifications, each with whether it was read and why not, and actions such as **Don't read notifications like this**.
*   **Send a test notification**, **Allow automation apps** and **Automation**, which let apps such as Tasker turn notification speech on or off, stop it, or read the notifications.

The **Notification speech** quick settings tile turns reading on or off. The gesture actions **Read notifications**, **Notification speech on or off**, **Read the last notification again** and **Stop reading the notification** can be assigned in gesture settings. Several of these settings need notification access, which **Turn on notification access** on those screens opens.

### Meridian's notification list

With **Notifications** > **Use Meridian's notification list** on, the **Notifications** action opens a list of Meridian's own instead of the system's notification shade, which some phones, such as Samsung phones with many notifications, redraw so often that swiping through it is slow. The list shows each notification with its actions and reply field, groups as one item that shows its notifications when you double-tap it, and Playing or Paused for media. Text longer than about 160 characters is cut, and the **Show all** action reads the whole text. **Dismiss** and **Clear all** work as in the shade. **Notification details** sets which parts of a notification are read and in what order: title, text, app, time and playback. The list needs notification access, and isn't used while the phone is locked. The **System notification shade** gesture action opens the system's shade.

### Notifications that Android doesn't announce

Android tells screen readers about a new notification only when it alerts. A notification that Do Not Disturb holds back, or an update that an app posts without alerting again, such as a doorbell app replacing its last motion alert, never reaches TalkBack. When Meridian has notification access, it also receives these from its notification listener, and reads them as its notification settings say, so **Do Not Disturb** in the notification settings applies to them too. While the phone is locked, Android gives screen readers only the public version of a private notification, which many apps don't have, so TalkBack reads nothing; with notification access, Meridian reads the whole notification from its listener instead. Progress and ongoing notifications are left out. Incoming calls are listed in the notification history as announced.

### Toasts in Do Not Disturb

While Do Not Disturb was on, TalkBack didn't speak toasts, the short messages that apps show at the bottom of the screen. Meridian speaks toasts, and stays silent only for notifications.

### No "collapsed" on each notification

Meridian doesn't say "collapsed" for each notification on the lock screen and in the notification shade. It still says "expanded" when you open a notification, and it still says "collapsed" in other apps.

### Notices that you can dismiss

TalkBack marked its notices that it was updated, or that gestures changed, as ongoing, so you couldn't swipe them away or dismiss them. In Meridian, you can dismiss these notices, including on the lock screen. The notifications for pausing Meridian and for downloading an on-device model stay ongoing while those continue.

### Meridian's quick settings panel

With **Advanced settings** > **Use Meridian's quick settings** on, the Quick settings action opens a grid of Meridian's own instead of the system's quick settings: switches, sliders and buttons in the order you choose, in as many columns as you choose. Meridian says each tile's row and column. Each control is one tile with its name, its state and, where there is one, what it's connected to. Swipe up or down on a volume slider to move it one step. Double tap and hold an item to open its settings. The **System quick settings** gesture action and the panel's button open the system's quick settings.

**Advanced settings** > **Quick settings panel** adds, removes and moves items, and sets the number of columns. Each item says its row and column; **Move** lists the cells, and the item you move takes the cell you pick while the items between move one cell to make room. Meridian says how many moved. **Add control** lists Meridian's controls: Wi-Fi, Bluetooth, mobile data, hotspot, airplane mode, data saver, Do Not Disturb, sound mode, flashlight and its brightness, location, auto-rotate, battery saver, dark theme, Night Light, Extra dim, color inversion, color correction, NFC, microphone and camera access, brightness, adaptive brightness, screen timeout, font size, the volumes, media, media output, next alarm, battery, Settings and the power menu. **Add app tile** lists the tiles that installed apps offer, and **Add system tile** lists the system's own tiles. A system tile that Meridian has a control for, such as Hotspot, is added as that control.

Android only lets the system and the shell change some settings, and only lets the system run tiles. With Shizuku running, Meridian changes the radios, the hotspot, data saver, battery saver, dark theme and the microphone and camera switches with shell commands, runs app tiles with `cmd statusbar click-tile`, and gives itself the permissions it needs for the rest. Without Shizuku, those switches open the system's settings for them. System tiles, and app tiles without Shizuku, are used in the system's quick settings: Meridian opens them, activates the tile and closes them without speaking them, and then says the tile's new state.

## Timer and stopwatch

### Timer settings

**Timer settings**, in the main settings, gathers the time announcements that TalkBack keeps in its notification settings: how often Meridian says the time, the words it says it with, and **Exact times**, which says the time on the minute and needs the Alarms and reminders permission. Without that permission, the time can be said up to a minute late.

### Stopwatch

The **Stopwatch** screen, from **Timer settings**, has **Sound** and **Vibration** switches for the stopwatch's start, stop and reminder signals, and **Remind every**, which sets how often it says how long it has run: every 10 minutes by default. Starting it says "Stopwatch started"; stopping it says how long it ran. When the screen turns on while it runs, Meridian says how long it has run, then the time. **Timer history** lists the runs that have finished.

The **Toggle stopwatch** action starts it or stops it. By default, holding volume up at the lock screen performs it; see [Volume key settings](#volume-key-settings).

## Sound and vibration

### Audio output device

**Sound and vibration** > **Audio output device** chooses where Meridian's speech and sounds play. **System default** follows Android. **Phone speaker** plays on the phone's speaker even when Bluetooth is connected, and you can also choose a connected Bluetooth, wired, USB, or hearing aid device by name.

Meridian keeps your choice when devices connect or disconnect, or when other apps start playing. If the device that you chose isn't connected, Meridian uses another external device, and the setting shows "(disconnected)" next to the device name. During phone calls, Android decides where audio plays.

**Sound and vibration** > **Phone speaker with these devices** lists the Bluetooth, wired and USB outputs that have been connected while Meridian runs. While one that you check is connected, Meridian plays on the phone speaker, whatever **Audio output device** is set to: for example, on the speaker while music plays on a Bluetooth speaker, and in your ears with earbuds. Outputs are known by name, so two devices with the same name are one choice.

### A vibration for every sound

Like VoiceOver, Meridian can tell you what happened by touch alone. Every sound has its own vibration, which plays even when **Sound feedback** is off, so you can turn all sounds off and still feel each action. No two actions share a vibration.

Frequent actions are a single hit: a soft tap when focus lands on an item, a firm click on an item that you can activate, and a softer tap over an empty area. Others have a rhythm of their own. The end of a list, or a completed action, is two firm knocks. Entering a list is two light taps with the second one stronger, and leaving a list is two light taps with the second one weaker. A window change is three soft taps with the middle one strongest. When Meridian scrolls a list, you feel three quick ticks. When the circle menu opens, you feel one tick for each item. The loading tone, which repeats while you wait for an image or screen description, has a short heartbeat that's firm enough to feel through a case.

Progress bar tones and the sound for capital letters don't vibrate. Phones that support rich haptics play the vibrations as composed effects. Phones without them play softer and shorter forms of the same patterns.

**Sound feedback volume** is a slider from 5 to 100 percent in steps of 5, rather than a list of 25, 50, 75 and 100 percent. **Vibration strength**, below **Vibration feedback**, sets how strong vibrations are, from 5 to 100 percent, on phones whose vibrator can vary its strength.

### Ticks while you scroll with two fingers

TalkBack played its scroll sound and vibration at most every 250 ms while you scrolled. Meridian plays the scroll sound and one light tick for each item that scrolls past, so a slow drag ticks slowly and a fast drag ticks quickly. Lists that don't number their items tick once for every 48 dp of scrolling. You feel the first tick as soon as the drag reaches the app, and Meridian hands the drag to the app sooner than TalkBack did. Scrolls that Meridian makes itself keep the usual sound.

### Individual sounds and vibrations

To turn off one sound or one vibration and keep the rest, go to **Sound and vibration** > **Individual sounds and vibrations**. There's a switch for each sound and each vibration, including the screen-off sound. Turning off a sound keeps its vibration, and turning off a vibration keeps its sound. To hear or feel one, open the actions menu on its switch and choose **Preview**. **Sound feedback** and **Vibration feedback** still turn all of them off at once.

### Sound themes

A sound theme is a set of sounds and vibrations that replace Meridian's own, with its own control sounds and 3D audio settings. You can install as many themes as you like, from a file or a link, and switch between them in **Sound and vibration** > **Sound themes**. A link to a GitHub repository that contains a theme also works, and you can open or share a theme file, or a link to one, with Meridian from another app.

To replace single sounds with your own, open **Sounds** under the theme that you're using. To share your sounds, save the theme as a file. For more information about installing, using, and making themes, see [Sound themes](themes.md).

### Control sounds and vibrations

Like the [Unspoken](https://github.com/ahicks92/Unspoken) add-on for NVDA, Meridian can play a sound for each kind of element in place of the focus sound, and leave out words such as "button" and "checkbox". There are sounds and vibrations for buttons, checkboxes (which also cover switches and toggle buttons), radio buttons, edit fields, drop-down lists, sliders, images, clocks, tabs, menu items, and list items, and on web pages, for links and tree items. To turn them on, go to **Sound and vibration** > **Sound themes** and turn on **Control sounds and vibrations**.

Meridian has no control sounds or vibrations of its own: they come from the sound theme that you're using. Until a kind of element has a sound or a vibration, it plays the focus sound and Meridian says what kind of element it is. An element that has a vibration but no sound plays the focus sound with its own vibration. A control's vibration plays even with sound feedback off, so you can learn the kinds of controls by touch alone.

A row that isn't a control itself, such as a row in Settings, plays the sound of the control in it, such as its switch, and otherwise the sound for its kind of item, such as a list item. Plain text and keys on the keyboard keep the usual focus sound. Moving into or out of a list plays the list sound and the control's sound together, with the control's vibration.

To keep hearing the kind of control as well, turn on **Still say the kind of control** in **Sound and vibration**. Each control sound and vibration also has its own switch in **Individual sounds and vibrations**.

### Navigation sounds

TalkBack plays a sound and vibration when a gesture starts and another when it ends. Meridian plays neither. In their place, a sound theme can give a sound and a vibration to each of these:

- **Next reading control** and **Previous reading control**: selecting another reading control, with a gesture, the rotor or a keyboard shortcut.
- **Edge gesture bottom**, **Edge gesture middle** and **Edge gesture top**: a finger touching an edge where an edge gesture starts, the gesture reaching its first click, and the gesture reaching its second click. Sliding back plays the sound of the place the finger is back at. Lifting plays nothing.
- **Insertion point moved**: moving by character, word, line or paragraph moves the cursor in a text field you are editing.
- **Column wrap** and **Column wrap back**: swiping forward to an item below the row you are in, and back to an item above it. Each item of a list is a row of its own; items side by side, such as icons on a home screen, cells of a table, or a title with buttons beside it, share a row. The sound plays with the focus sound, and its vibration plays in place of the focus vibration.

Meridian has no sounds of its own for these. The edge gesture middle and top have Meridian's edge gesture vibrations; the others vibrate only with a theme's vibration. Each has its own sound and vibration switch in **Individual sounds and vibrations**.

Rows are found by where items are on the screen: an item is on another row when its middle is below the bottom of the focused item, or above its top. Column wrap doesn't play for swipes that move from one window to another.

### 3D audio

Each control sound comes from where the element is on the screen: from the left for elements on the left, and from higher up for elements near the top. With headphones, the sounds play in 3D, using measurements of how a real head hears sound from each direction (the MIT Media Lab KEMAR measurements, by Bill Gardner and Keith Martin). On the phone speaker, the sounds only move between left and right.

On Android 13 and later, Meridian uses 3D when its audio plays on wired or USB headphones, a hearing aid, a Bluetooth media device, or a Bluetooth LE headset. Bluetooth LE speakers, broadcasts, and call audio don't count. On earlier versions, any connected audio device counts. To play the sounds in 3D on the speaker as well, or never, change **3D audio** for the theme. Each theme has its own **Control sounds and vibrations** and **3D audio** settings.

## Gestures

### Default gestures

Meridian changes these default gestures:

| Gesture | Meridian | TalkBack |
|---|---|---|
| Tap with four fingers | Back | Practice gestures |
| Double-tap with four fingers | Home | Tutorial |
| Triple-tap with four fingers | Overview (recent apps) | None |
| Double-tap and hold with four fingers | Notifications | Pass through the next gesture |
| Tap and hold with three fingers | Pass through the next gesture | Screen search |
| Double-tap with three fingers | Turn speech on or off | Copy |
| Triple-tap with three fingers | Copy last spoken phrase | Paste |
| Triple-tap and hold with three fingers | Paste | None |
| Double-tap and hold with two fingers | None | Selection mode |
| Triple-tap with two fingers | Speak status | Read from focused item |
| Triple-tap and hold with two fingers | None | Turn speech on or off |

### Navigation gestures

You can assign a gesture to move to the next or previous character, word, line, paragraph, heading, link, control, landmark, button, checkbox, radio button, edit field, combo box, focusable item, graphic, list, list item, table, visited link, unvisited link, or heading of a given level. Moving with one of these gestures doesn't change the reading control. When you choose an action for a gesture, these actions are under **Navigate by text**, **Navigate by element**, and **Navigate by heading level**.

Headings, links, and controls work in apps and on web pages. The other elements work only on web pages.

### More gesture actions

Meridian adds these actions, which you can assign to gestures in gesture settings. None is assigned by default.

*   **Open app**, at the end of the list of actions, lists the apps on the home screen. A gesture set to an app opens it, as its home screen icon does.
*   **Lock screen** and **Power menu**, in **System actions**.
*   **Increase media volume** and **Decrease media volume**, in **Special features**, change the media volume a step and say the new volume.
*   **Scroll to top** and **Scroll to bottom**, in **Basic navigation**, scroll the list that focus is in to its start or end a page at a time, then focus its first or last item. In a list that doesn't scroll, such as a short settings screen, they focus its first or last item. Outside a list, they use the first list on screen, or the first or last item on screen when there is none. A touch, gesture or key while it scrolls stops it.
*   **Turn browse mode on or off**, in **Menu and controls**, does what Action pressed twice does on a keyboard. It is also a keyboard shortcut, **Another shortcut to turn browse mode on or off**, unassigned by default, for keyboards that take Action pressed twice for themselves. Both need the default keymap, not the classic one.
*   **Refresh**, in **Basic navigation** and as a keyboard shortcut, scrolls the list that focus is in to its top and pulls it down, which refreshes it in most apps. **Scroll back** and **Scroll up** pull a list down too when it is already at its top, as scrolling with two fingers does. Meridian sends the pull as a swipe, so it needs Android 11 or later.
*   **System quick settings** and **System notification shade** open the system's own quick settings and notification shade, when **Use Meridian's quick settings** or **Use Meridian's notification list** is on.

When you choose an action for a gesture, the categories of actions are collapsed, except the one with the gesture's current action. The categories in gesture settings, **Reading controls** and **Meridian menu** are collapsed too, and expand when you activate them.

### Status gesture

Triple-tap with two fingers to hear what the status bar shows: the time, the battery, the Wi-Fi network and its signal, and the mobile carrier, network type, and signal. Meridian also says the ringer, Do Not Disturb, and airplane mode when they aren't in their usual state. To hear the name of the Wi-Fi network, allow location access when Meridian asks the first time.

To choose what the gesture says and in what order, go to **Status readout**. Each item has **Move up** and **Move down** actions. In gesture settings, this action is called **Speak status**. To read from the current item, which was the gesture's action in TalkBack, choose **Read from focused item** in the Meridian menu.

### Rotor

The two-finger rotation rotor was removed so that touches reach touch exploration sooner; reading controls are chosen with the reading-control gestures instead.

### Edge gestures and scrub

The edge gestures and the two-finger scrub were removed so that touches near the edges, such as the bottom row of the keyboard, reach touch exploration sooner.

### Pinch and spread

Pinch and spread gestures were removed so that two-finger movements reach apps without waiting to be told apart from them.

### Lift to activate

Meridian can activate the item under your finger when you lift your finger after exploring by touch, as TalkBack already does for keys on the keyboard. In **Controls** > **Touch and activation** > **Lift to activate**, choose **Only on navigation bar** to use it for the Back, Home, and Overview buttons only, or **Entire screen** to use it everywhere. It's **Disabled** by default. With **Only on navigation bar**, a single tap presses the button, and Meridian says which one, such as "Back" or "Home". To stop this, turn off **Controls** > **Touch and activation** > **Speak navigation bar buttons**.

With **Only on navigation bar** on Android 11 and later, touches on the navigation bar go straight to Android, so a single tap presses a button and holding Home holds it, as without a screen reader. Meridian doesn't say the buttons as you touch them.

**Lift to activate** is also a reading control, so you can change it with a swipe up or down.

### Speech kept while you touch the screen

TalkBack stops speech when a finger touches the screen. To keep speech going until touch focuses an item, turn off **Controls** > **Touch and activation** > **Stop speech when touching the screen**. Speech of the item that touch focuses still interrupts, as do gestures that speak. It's on by default.

### Wrap around

When you swipe past the last item on the screen, TalkBack stops once and then goes back to the first item. To stop at the end instead, turn off **Controls** > **Navigation** > **Wrap around**. Each swipe past the end then plays the end sound. This also applies to moving by heading, link, or control in apps, and to moving with a keyboard. Web pages can still wrap by themselves.

**Wrap around** is also a reading control, so you can turn it on or off with a swipe up or down. It's on by default.

### Stuck touch exploration

On some phones, after a touch with several fingers, Android can stop touch exploration for every screen reader until the phone restarts. Swipes and taps still work, but dragging a finger says nothing. The bug is in Android, not in the screen reader.

Meridian notices exploring touches that hover over nothing. To let it fix the problem, it needs the DUMP permission. With [Shizuku](https://shizuku.rikka.app) running, Meridian grants it when it starts: Shizuku asks once whether to allow Meridian. Without Shizuku, grant it once with adb:

```
adb shell pm grant io.github.aaron_gh.meridian android.permission.DUMP
```

With the permission, Meridian reads which touches Android left down, says "Fixing touch exploration", turns explore by touch off while it ends those touches with a gesture outside the screen, usually for less than half a second, and turns explore by touch back on. It then says "Touch exploration fixed". A touch during the fix goes straight to the app, and Meridian tries again after your next exploring touch. Without the permission, after three such touches in a row Meridian says that touch exploration may be stuck, and you need to restart the phone.

This needs a phone whose `dumpsys input` lists the touches that Android holds for accessibility, which Samsung phones do. On other phones, Meridian can only say that touch exploration may be stuck. Exploring something that has nothing for Meridian to read, such as blank space or a game, also hovers over nothing, so without the permission the warning can be wrong, at most once every 10 minutes. To turn this off, turn off **Touch and activation** > **Fix stuck touch exploration**.

### Focus in new windows

When a window opens, or you go back, TalkBack moves focus into it: back to the item you were on, to the text field with the cursor, or to the first item. To leave focus alone, turn off **Controls** > **Navigation** > **Move focus to new windows**. Meridian then speaks only the window name, and the next swipe moves to the first item. This applies to Meridian's own menus and dialogs too, when they open and when they close. Focus follows input focus only when you move it with a key, such as an arrow key or Tab, not when an app moves it by itself. When the item you focused disappears and the windows haven't changed, focus is placed again.

### Gestures recognized by Android

Meridian used to be able to recognize gestures itself, with a **Handle gestures in Meridian** switch in developer settings. In repeated testing that was always slower than letting Android recognize gestures, so Meridian no longer recognizes gestures itself: Android recognizes every gesture, always, and the switch is gone.

The gestures that only Meridian's own recognition could produce are gone too, along with their entries in gesture settings: the quadruple-tap with three fingers, tap and hold with two fingers, the two-finger rotation, scrub, pinch and spread, and the edge gestures. So are the settings that only Meridian's own recognition used: the focus and typing delay sliders, **Time between one-finger taps**, **Reduce swipe start delay**, and **Recognize swipes early** in **Reduce delay**, and the focus latency reading controls. Split typing by holding one finger on a key and tapping with another no longer happens by itself; the **Split typing** action still works when you assign it to a gesture Android recognizes.

Tap to type, which typed a key as soon as you touched it, also needed Meridian's own recognition, and is gone. [Direct key input](#direct-key-input) replaces it.
## Navigation

### Reading place kept when an item disappears

When an app removed the item that had focus, such as an ad that collapses, TalkBack jumped back to the top of the page. Meridian carries on from where you were.

### Moving by character and word in drawn text

Some apps draw their text themselves and give it to screen readers without letting them move through it, as Telegram does for messages. In such text, TalkBack could move by character, word, or paragraph only through a content description, so it couldn't move through these messages, only through reply previews. Meridian moves through the text itself when the app doesn't.

### Double tap with the actions reading control on Android 11

On Android 11, when you picked an action with the **Actions** reading control, a double tap only played a sound. In Meridian, a double tap performs the action, and a double-tap and hold performs a long press.

### Sliders that report themselves as unfocusable

realme UI reports some volume sliders as not focusable and unlabeled, so TalkBack skipped them. Meridian can focus them.

### Vertical navigation

The **Vertical navigation** reading control, which you can turn on in **Reading controls**, moves to the item below when you swipe down and to the item above when you swipe up. It skips the other items of the same row, such as the channel and options buttons beside a video in YouTube, or the buttons beside a file's name. Going up lands on the first item of the row above. In a list, it stays in the list: when there is no row below or above on screen, it scrolls the list a page and moves to the next row. At the end of the list, or outside one, it moves to the item below or above on screen, and plays the end sound when there is none.

### Reading control kept when focus moves

When focus moves onto an item with actions, a slider, a table or a spelling mistake, TalkBack changes the reading control to the one for that item, such as Actions, Adjust slider or Proofread, and changes it back afterwards. On web pages, where many items have actions, this replaces a reading control you chose, such as Headings. To keep the reading control you chose, turn off **Reading controls** > **Change reading control automatically**.

The fork adds **Reading controls** > **Change reading control in selection mode**, on by default, which keeps your reading control when selection mode starts in web content instead of switching it to Characters. It covers focus moves in web content too: with this switch off, focus moving within a web page no longer replaces your reading control with the one for the item under focus, and no longer changes it back afterwards. Outside web content, and whenever the switch is on, reading controls change as described above.

With **Reading controls** > **Links and Controls only in web content** on, the **Links** and **Controls** reading controls are left out of the list outside web content, as the web navigation reading controls are. Gestures and keyboard shortcuts for the next or previous link or control still work everywhere. It's off by default.

### Volume keys that adjust sliders

When you turn on **Controls** > **Touch and activation** > **Volume keys adjust sliders**, the volume keys change the value of the slider that has focus, and Meridian speaks the new value, as it does when you adjust the slider with the **Adjustable** reading control. Hold a key to keep changing the value. When focus is on anything else, or the screen is off, the volume keys change the volume as usual. They also still change speech volume while a finger is on the screen, during continuous reading, and with the keyboard shortcut modifier. The setting is off by default.

**Controls** > **Touch and activation** > **Volume keys move the cursor**, off by default, makes the volume keys move the cursor while a text field has focus: volume up moves it back a character and volume down moves it on, saying the character.

### Volume key settings

**Controls** > **Volume key settings** is a screen of its own. With **Meridian manages volume keys** on, Meridian handles the volume keys itself, without showing the system volume panel, and you choose what each of six presses does: **Volume up press**, **Volume up press and hold**, **Volume up press and hold on the lock screen**, and the same three for volume down. By default, a press raises or lowers the volume, a hold does nothing, and holding volume up at the lock screen toggles the stopwatch. The switches for [speaking volume changes](#speak-volume-changes), **Volume keys adjust sliders** and **Volume keys move the cursor** are on this screen too, along with the switch that makes pressing both volume keys stop a notification being read.

### Browse mode in each app

**Navigation > Remember browse mode in each app**, off by default, keeps browse mode as you last turned it on or off in each app. In an app where you haven't turned it on or off, browse mode is off, unless Smart browse mode turns it on in web content.

### Container navigation

Next container and Previous container (Action + Control + Alt + Right or Left arrow, or a four-finger swipe right or left) also stop at toolbars, tab bars, navigation bars, search bars and panes, besides lists, grids and scroll views. A bar inside a bar, such as a search bar in a toolbar, is one stop. Previous container moves to the start of the container, as Next container does, not its last item, except in web content.

### Telegram reply quotes

In Telegram and its forks, such as Nekogram, the quote of the message that a message replies to was a separate item, often cut off. Meridian reads it with its message, after the time, as "replying to" the sender and text, with the whole quoted text when the quoted message is loaded. The quote isn't focused on its own, so the quoted message can't be opened from it.

### Touching the home screen

Touching between the icons of the home screen no longer says the page, such as "Home screen 1 of 2". Swiping still reaches it. On Android 13 and later, Meridian clears Android's cached copy of the home screen when it opens again, so that its icons can be reached after you leave a launcher's app list, as in Hyperion.

## Meridian menu

### Shorter menu by default

These items are off by default in the Meridian menu: **Actions**, **Screen search**, **Add or edit labels**, **Describe text formatting**, **Copy last spoken phrase**, **Spoken language**, **Voice commands**, and **Keyboard shortcuts**. You can still use actions with the **Actions** reading control. **Text-to-speech** stays on, so that you can reach speech settings if your speech engine crashes, and Meridian adds a **Pause Meridian** item.

To change the items, go to **Meridian menu**, under **Controls** in Meridian settings. In TalkBack, this screen is under **Customize menus**.

### Circle menu

The Meridian menu can show as a circle in the middle of the screen, as it did in TalkBack 8.1 and earlier. Each item is a slice of the screen around the middle. Slide to an item and lift your finger to select it, or lift in the middle of the circle to close the menu. Items that open more items show them in a new circle. When the menu opens, you hear a rising note and feel a tick for each item.

If you open the menu with a gesture that ends with your fingers still on the screen, such as a double-tap and hold, you can slide those fingers out to an item and lift them to select it. The place where your fingers were counts as the middle of the circle.

To turn the circle menu on, go to **Meridian menu** and turn on **Circle menu**. It's off by default.

## Screen and brightness

### Brightness reading control

Use the **Brightness** reading control to change the screen brightness in steps of about 10% when you swipe up or down. It works while the screen is hidden, so you can set the brightness before you give the phone to someone. The first time that you use it, Meridian asks for the **Modify system settings** permission.

### Hide screen

For 3 minutes after you hid the screen, TalkBack set the screen to full brightness. Meridian keeps your brightness. The screen also turns fully black, including around the camera cutout. On some Pixel phones with Android 17, TalkBack's curtain only dimmed the screen.

If you turn off **Always show this** in the hide screen dialog, Meridian says only "Screen hidden" and skips the instructions for showing the screen again. When Meridian starts with the screen hidden, it says "Screen hidden" after "Meridian on".

### Proximity sensor

Meridian doesn't stop speech when something covers the proximity sensor. To turn this back on, go to **Sound and vibration** > **Cover proximity sensor to stop speech**.

### Screen on and off announcements

In **Verbosity** > **Screen on and off**, you can choose what Meridian says when the screen turns off, when it turns on, and when you unlock the phone. When the screen turns off, Meridian says "Screen off" and the ringer mode. When the screen turns on, it says the time, and it can also say the battery, the Wi-Fi network, the mobile network, the ringer and Do Not Disturb, and airplane mode, after the time in the same announcement. When you unlock the phone, it says "Device unlocked". Each of these has its own switch. By default, everything except the extra screen-on items is on.

These switches are separate from **Status readout**, so the status gesture can say different things. Meridian doesn't make the screen-on announcement during calls, and the lock screen no longer cuts off "Screen off".

## Pausing Meridian

### Pause Meridian

Pausing turns off Meridian's speech, sounds, vibration, gestures, and explore by touch, so the phone works as if no screen reader were on. Meridian stays on, so it resumes immediately. TalkBack 8.1 and earlier had this feature as suspend.

To pause, choose **Pause Meridian** in the Meridian menu, assign the **Pause Meridian** action to a gesture, or assign a key to the **Pause or resume Meridian** keyboard shortcut. The first time, Meridian asks you to confirm and says how to resume.

To resume, tap the **Meridian is paused** notification, press volume down three times quickly, or press the keyboard shortcut again. By default, Meridian also resumes when the lock screen shows. To change this, go to **Advanced settings** > **Resume Meridian** and choose **When the screen turns on** or **Only from the notification or a shortcut**.

The volume keys still change the volume while Meridian is paused. If Meridian restarts while it's paused, it starts unpaused.

## Calls

### Speaker when away from your ear

Like VoiceOver on iPhone, Meridian can move a call to the speaker when you take the phone away from your ear, and back to the earpiece when you hold it up again. A call that starts with the phone away from your ear goes straight to the speaker. Meridian leaves Bluetooth and wired headsets alone, and if you turn the speaker on or off in the Phone app, your choice stays until you move the phone again.

Android lets only certain apps, such as smartwatch companions, change where call audio goes, so Meridian needs a permission for it. With [Shizuku](https://shizuku.rikka.app) running, turn on **Sound and vibration** > **Speaker when away from your ear**, and Meridian grants the permission itself; the first time, Shizuku asks whether to allow Meridian. If the permission can't be granted, the setting turns back off. Without Shizuku, grant the permission once with adb, then turn on the setting:

```
adb shell appops set io.github.aaron_gh.meridian MANAGE_ONGOING_CALLS allow
```

Without Shizuku, until you grant the permission, the setting is unavailable and shows the command, and its **Copy command** action copies the command to the clipboard. This feature requires Android 12 or later.

## Direct touch

Audio games need raw touch, but explore by touch captures taps and swipes before the game sees them. With direct touch, Meridian passes your touches straight to the games that you choose, so you don't have to pause Meridian.

### Built in from NVGT Bridge

Direct touch is all of [NVGT Bridge](https://github.com/trypsynth/nvgt-bridge), the accessibility service that gives audio games direct touch, built into Meridian. Because Meridian is the screen reader and already knows what's on the screen, direct touch works better than running NVGT Bridge alongside Meridian.

There's nothing extra to install or turn on in Accessibility settings, and no second service that can undo Meridian's touch setting. Direct touch shares that setting with the pass-through gesture, so neither cancels the other. NVGT Bridge searches each window's views for dialogs and text fields, and looks only five levels deep. Meridian already follows the windows, the keyboard, and the screen state, so it hands touch back to you as soon as they change, with no depth limit. Meridian's own speech says "Direct touch on" and "Direct touch off", even while the game plays audio.

### Choose your games

Go to **Direct touch**, under **Controls** in Meridian settings, and turn on each game in the **Apps** list. When a game that you chose comes to the front, Meridian says "Direct touch on", and when the game gives touch back, it says "Direct touch off". To turn the announcements off, turn off **Speak when direct touch changes**. To feel a short vibration instead or as well, turn on **Vibrate when direct touch changes**. The **Direct touch** switch at the top of the screen turns the whole feature on or off, and is on by default.

### When Meridian takes touch back

Touch returns to Meridian when a dialog appears, another app opens on top of the game, you open the notification shade or quick settings, a text field takes focus, or the screen turns off. Touch goes back to the game when they're gone. The keyboard always stays with Meridian, so you can explore it while the rest of the screen stays in direct touch.

### Direct typing

By default, the keyboard area keeps working with Meridian. For a game that draws its own keyboard, open the actions menu on the game in the **Apps** list and choose **Turn on direct typing**.

### Self-voicing keyboards

A keyboard that speaks for itself, such as Advanced Braille Keyboard, needs your touches in every app, not only in games. Turn it on in the **Apps** list, and while it shows, touches on it go straight to it in any app, and the rest of the screen stays with Meridian.

### Quick settings tile

To pause and resume direct touch without leaving your game, add the **Direct touch** tile to quick settings.

### Back up and restore direct touch settings

**Back up settings** saves your direct touch choices to a file, and **Restore settings** loads them on another device.

### Turn on direct touch from your game

If you develop a game, add this `<meta-data>` element inside your `<application>` element or your main `<activity>` element, and Meridian turns your game on the first time that it sees it. Players can still turn it off.

```xml
<meta-data
    android:name="dev.nvgt.capability.DIRECT_TOUCH"
    android:value="true" />
```

## Granular editing

**Granular editing** opens the text of the focused text field, or of any focused item with text, in a screen of its own, split into pieces: characters, words, sentences, lines or paragraphs, chosen with **Split by**. It is in the **Editing** actions of a text field, and is a gesture action under **Text editing**.

- Double tap a piece to edit it. Leaving it empty deletes it.
- A piece's actions are **Select**, **Insert before**, **Insert after** and **Delete**, also in a dialog with a double tap and hold.
- **Copy** copies the selected pieces, or all of the text when none is selected.
- **More** has **Undo**, **Redo**, **Find**, **Find next**, **Replace** (all matches, ignoring case), **Add text at end**, **Select all**, **Clear selection**, **Cut selected** and **Delete selected**.
- **Save** writes the text back to the text field and closes the screen. If the field is gone by then, the text is copied instead. Back asks whether to save or discard changes.

Text that isn't in a text field, such as a message, can be edited and copied but not saved. Password fields can't be opened.

## Dictation

**Dictate**, a gesture action under **Text editing**, types what you say into the focused text field, at the cursor. A chime plays when recording starts; recording goes on, through pauses, until the gesture is performed again, which plays a second chime. Speech is silenced while recording, so that it isn't recorded. Recording stops by itself after 15 minutes.

The older actions **Start or end Gboard voice input** and **Play or pause media, start or end Gboard voice input** are a different feature: they press the microphone button of the keyboard on screen, so they only work while Gboard is shown, and they ignore the Dictation settings below. Their names say Gboard in this fork, so they are not confused with **Dictate**.

**Settings > Dictation** has:

- **Speech recognition**: Automatic, Google on-device speech recognition, Rambler (Gemini API), Whisper or Android speech recognition.
  - Google on-device speech recognition is the recognizer of the Speech Recognition & Synthesis app (`com.google.android.tts`), with that app's offline language packs. Gboard's voice typing runs on it, Rambler mode included, which is why PixelBoard's Rambler needs no API key and is fast. Meridian asks it for offline recognition in the phone's language, with formatting (punctuation and capital letters) on Android 13 and later. Rambler's own cleanup in Gboard (filler words, self-corrections) isn't available to other apps.
  - When the phone's language has no language pack (Android 13 and later can check), a row **Download (language) for on-device recognition** opens Speech Recognition & Synthesis's download screen for it.
  - Rambler (Gemini API) is Gemini 3.5 Transcribe, the model behind Rambler's cleanup, asked through the Gemini API with the Gemini API key from Gemini settings, in smart mode: filler words are removed, self-corrections resolved, and punctuation, numbers and lists formatted. With **Rambler live transcription** on (the default, shown when there is a Gemini API key), the recording is streamed while you speak to Gemini 3.5 Transcribe Live (`gemini-3.5-transcribe-live`, Live API over a WebSocket, smart mode), which returns each phrase's cleaned-up text when you pause, so after the second gesture only the last phrase is left to transcribe. If the session can't connect, ends early (the Live API ends sessions after 10 minutes) or the last phrase doesn't come within 5 seconds, the whole recording is sent to the non-live model instead. Off, the whole recording is sent after the second gesture. The recording is sent with `store: false`, so it isn't kept as an interaction to look up later. With a free-tier key, Google may still use the audio and text to improve its products; with a paid-tier key (about $0.005 a minute) it doesn't.
  - Whisper runs on the phone (whisper.cpp), with a model downloaded under Whisper models.
  - Android speech recognition uses the service chosen in Android's settings.
  - Google on-device and Android speech recognition start again after each pause until the gesture stops them.
  - In this fork, those two recognizers keep what they have recognized when a recording fails for good partway through — for example when the recognition service reports that it is busy as the next stretch starts: the text recognized so far is typed instead of thrown away, and only a recording with no recognized text at all reports that recognition isn't available. A busy or client error is also retried on a lengthening schedule — after 300, 600, 1000, 1500 and 2000 ms — instead of five tries 300 ms apart. The pauses that end one stretch of recognition and start the next are decided by the recognition service itself: Meridian asks for 10 seconds of silence tolerance through the recognition intent, which the service may not honor, and there is no segment timer on Meridian's side.
  - Automatic uses Google on-device speech recognition when it is installed and has the phone's language, else Rambler when there is a Gemini API key and Rambler knows the phone's language, else Whisper when a model is downloaded, else Android speech recognition. A chosen one that can't be used gives way to the same order. If Google on-device recognition reports a missing language during recording, the next one takes over without stopping. When Rambler doesn't answer, the recording is transcribed with Whisper if a model is downloaded.
- **Whisper models**: Tiny (32 MB), Base (60 MB), Small (190 MB) and Large v3 Turbo (574 MB), all multilingual and quantized, from Hugging Face, checked against their SHA-256. Tapping a model downloads it (asking first on mobile data), or, once downloaded, offers Use and Delete. The first downloaded model is used. Whisper is told the phone's language when it knows it, else it detects the language.
- **Correct dictation with AI** (on by default) and **AI service for dictation**: Same as automatic descriptions (the default), any AI service that is set up, or On-device AI when its model is downloaded. So Rambler or Whisper can do the recognition while a different service corrects it, and a different service from the one describing images. The text of Google on-device, Whisper and Android speech recognition goes to that service first, with up to 300 characters of the text before the cursor, for the cleanup that Gboard's Rambler does on Google's servers: filler words, stutters and false starts removed, self-corrections resolved ("two, no, three" becomes "three"), punctuation and capital letters added, misheard words corrected, and numbers, dates and spoken lists formatted. With On-device AI, all of dictation stays on the phone and works with any keyboard. (Rambler itself can't be used by other apps: Gboard sends the audio to Google's speech servers, which accept only Gboard, and runs on-device recognition beside it as a fallback. Gemini Nano, through ML Kit GenAI, only answers the app in the foreground, which Meridian never is while you dictate into another app.) Rambler's text isn't sent again. If the service doesn't answer within 20 seconds (60 for on-device AI), or answers with something much longer than what you said, the text is typed as recognized; Dictate performed while it waits does the same at once. Dictation into a password field isn't sent to the service.

Typing into text fields that only accept plain text, such as in Google Contacts, failed with the voice command "type" too; Meridian now sends plain text.

## On-screen keyboard

### Touch-only keyboard navigation

Swiping left or right skips the on-screen keyboard, including its keys and suggestions,
and moves through the other items on screen. After touching a key, the next swipe
continues from the app's input field. Touch exploration and the selected typing method
still work on the keyboard.

### Lift to type, except to send

In **On-screen keyboard** > **Typing method**, **Hold finger to select any key, then lift. Double-tap for Enter, Done, or Send.** types every key when you lift your finger, like **Hold finger to select any key, then lift**, but the key that sends or submits, such as Enter, Done, Send, Search, or Go, still needs a double-tap. This keeps you from sending a message by lifting your finger on the wrong key. Meridian recognizes this key in Gboard. In other keyboards, every key types when you lift your finger.

### Direct key input

**On-screen keyboard** > **Typing method** also has **Direct key input**. While it is selected, the keyboard window is in Android's gesture-detection passthrough region, so touches on the keyboard are not held while Android works out whether they are a gesture, and exploring the keys answers sooner. You hold your finger to select a key, and the key is typed when you lift, as with lift to type, including the key that sends or submits. Meridian types the key as soon as your finger leaves it, instead of waiting for Android's touch interaction end, which only arrives after Android's double-tap timeout. Holding a key past the typing long-press time long-presses it instead. The trade-off: double-tap does not work in the keyboard area while this method is on.

This needs Android 11 or later; on older versions the method types like lift to type for every key. If the keyboard is also turned on in **Direct touch**, touches there go straight to the keyboard, so this method has no key to type.
## Braille keyboard

This fork is speech-only, so the braille keyboard isn't included.

## Braille displays

This fork is speech-only, so braille displays aren't supported, and there are no braille display settings.

## Image and screen descriptions

### AI services

Google's source release doesn't include working Gemini support, so **Describe image** only reads text in images and **Describe screen** doesn't work. Meridian adds its own Describe screen with follow-up questions, and sends both to an AI service with your own API key. The services are Gemini, Claude, OpenAI (ChatGPT), Mistral, Groq, OpenRouter, Ollama Cloud and any other service with an OpenAI-compatible API. Each has its own settings screen in **Automatic descriptions**, with its API key and model. **AI service** chooses among the services that are set up, or **None**. A service that has just been set up is used when no other is, and when the service in use loses its key or model, the next one that is set up takes over. Questions about an image or screen, spoken or typed, go to the same service. For more information, see [AI services](README.md#ai-services).

With Gemini, Meridian asks the model to think as little as it can, and asks Gemini Flash-Lite when the chosen model is busy or takes more than 15 seconds.

### On-device AI

Meridian can describe images and screens with a model that runs on your phone, so nothing is sent to Google or anyone else. For more information, see [On-device AI](README.md#on-device-ai).

### Clear error messages

When Gemini can't describe something, TalkBack says "Something went wrong". Meridian says what failed: the key wasn't accepted, the usage limit was reached, the service is busy, it took too long, or there's no internet connection. For other errors, Meridian says the error code and the first sentence of the service's message, in English. Describe screen speaks its errors instead of showing them in a toast.

## Scripts

### Lua scripts and plugins

Like NVDA's app modules and global plugins, scripts change how Meridian works in one app or in every app. Scripts are written in Lua 5.4. A script can:

*   Give unlabeled buttons names, hide clutter, group items, and change or silence what Meridian says for an item.
*   Read the screen, press buttons, speak, play sounds and vibrate.
*   Add gestures, keyboard shortcuts, Meridian menu items, reading controls, and items in an item's Actions menu.
*   Change everything Meridian says, and react to notifications, typing and screen changes.
*   Use the internet and the clipboard, show dialogs, and keep its own data and settings.
*   With the `java` permission, call any Java code through LuaJava.

To install a script, go to **Apps** > **Scripts** and choose **Import script**. You can also share a script file, or a link to one such as a script's page on GitHub, to Meridian, or open a script file with it, and choose **Install Meridian script**. Meridian lists what the script asks to do before it installs or updates it. **Add the example script** adds a small script to start from.

Each script has a page with an on and off switch, a switch for each permission and for each gesture, key, menu item and reading control it adds, its settings on their own screen, its log, and **Remove**. While a script runs, its gestures and keys come before Meridian's.

Scripts never change Meridian's own screens, so the Scripts screen always works. **Turn off all scripts** on that screen stops every script at once, and so does the **Turn all scripts on or off** action, which you can assign to a gesture that scripts can't take over. Scripts don't run while the screen is off or Meridian is paused, and Meridian only starts the script engine once a script is turned on.

Speech requested with `sf.speak` can be heard while media is playing, including queued and interrupting requests. The script still needs the Speech permission, and muting speech, recording, voice recognition and phone calls retain their existing speech policy.

Hooks that change or suppress speech take priority over queued script work and share a 30-millisecond deadline. If the deadline expires or a long task is already running, Meridian uses its normal speech, so scripts should keep these hooks fast.

**Script sources** lists the scripts in repositories on GitHub, GitLab, Codeberg, Gitea or Bitbucket, to install them. Meridian's own scripts are listed until you remove that source. Once a day, Meridian checks installed scripts for a newer version in the source they came from, and **Check for updates** on the Scripts screen checks now. With **Update scripts automatically** on, the default, a newer version is installed without asking when it's by the same author, asks for no new permissions, and comes from the script's own source. Other updates are listed under **Updates** on the Scripts screen.

Scripts run on phones and tablets only. [Writing Meridian scripts](docs/scripting.md) describes the whole API, and [docs/scripting/examples](docs/scripting/examples) has a script to start from.

### Inspect focused item and Copy screen tree

**Inspect focused item** says the focused item's id, class, role, app, activity and window, and copies them, so that scripts can name the item. **Copy screen tree** copies every item on screen as JSON. Assign either to a gesture in gesture settings, or add it to the Meridian menu in **Meridian menu**, under **Controls**.

## Settings

### Layout and wording

Meridian lays out its main settings screen in this order: **Updates**, **Initial setup** and **Timer settings** stand on their own, then **Speech** (text-to-speech and verbosity), **Notifications** on its own, **Navigation** (navigation, touch and activation, direct touch, and volume key settings), **Sound and vibration** on its own, **Gestures and shortcuts**, **Menus and reading controls** (the Meridian menu and the reading controls), **Typing**, **On-screen information** (automatic descriptions, status readout, and screen announcements — the last moved out of **Verbosity**), **Apps** (scripts and custom labels), **System and help** (advanced settings, export and import settings, and the tutorial), and **Other settings** (offline translation, and Gemini settings when they are enabled). Only items that genuinely belong together share a group. Many settings have clearer names, such as **Speak item type** and **Order of item details**. Meridian removes the links to the Play Store, the privacy policy, the terms of service, Disability Support, and Google's TalkBack help, and the pages about new features in TalkBack. **Display speech output** is only in **Developer settings**. **Time format** is in **Verbosity**, **Turn off animations** is in **Reduce delay**, and **Cover proximity sensor to stop speech** is in **Sound and vibration**, instead of in **Advanced settings**.

Three upstream settings are removed entirely, with the features behind them:

- **Focus indicator** settings (border width and color). Android still draws its own focus frame; only Meridian's customization of it is gone, including the places that read those two preferences.
- **Let other apps speak** (Speech Control). The whole inter-app speech API is gone: the `speechcontrol` package, its AIDL interface, the exported receiver and service, the `${applicationId}.permission.SPEAK` permission, and `docs/speech-api.md`. The speech-options helper that scripts also use moved to the `speech` package.
- **Show instructions for turning off Meridian** (the exit watermark). The on-screen banner, its switch in **Advanced settings**, and the service code that showed and hid it are gone. The mis-trigger recovery itself stays: holding two fingers on the screen still turns Meridian off, and the tutorial's exit flow is unchanged.

For maintainers: `scripts/check_settings_key_contract.py` walks the settings tree from the main screen and fails if a reachable preference key disappears or if one of the three removed items comes back, so an upstream sync cannot drop or resurrect a settings item silently.

### Dark mode

On Android 12 and later, Meridian's settings follow the system dark mode.

### Slider titles

Meridian doesn't stop on a slider's title as a separate item when you swipe through settings.

### Feedback

This fork removes upstream's **Send feedback** entirely: the settings item, the Meridian menu item and the keyboard shortcut action are gone, and the app no longer sends reports — which could include Meridian's log — to the original author's report server, nor checks it for replies. To report a problem with this fork, use **Export diagnostics** in **Developer settings** and share the file yourself.

### Export and import settings

**Export settings** saves Meridian's settings to a file, and **Import settings** replaces them with a file's, such as one from another phone. API keys stay on the phone. Import settings also reads a settings file exported from Backtalk: the settings in it replace Meridian's, and Meridian's own settings that Backtalk doesn't have are kept.

### Export diagnostics

**Export diagnostics**, in **Developer settings**, collects what Meridian itself knows for debugging into one small text file: the phone and the app version, Meridian's state — including its speech engine and the state of the low-latency audio path — the last crash, and Meridian's recent log. **Export log to Downloads** saves the file in Downloads, and **Copy log** copies it instead, to send to whoever is helping. Meridian sends the diagnostics nowhere itself. The file can contain text Meridian has spoken and other personal information, so it is meant to be shared deliberately.

### Collapsed list item buttons

In some apps, each list item carries its own small buttons, and swiping stops on every one of them: a video in YouTube stops on the video, then on its channel button, then on its menu button. When you turn on **Navigation** > **Use the Actions list for supported apps**, each such item is a single stop, and its buttons move into the **Actions** reading control instead (swipe up or down to pick one, then double-tap). Touch exploration works the same way: touching any part of the item focuses the item. Buttons whose long-press does something, such as holding Like on a Facebook comment to open the reaction picker, get a separate long-press action.

On YouTube, a video's menu button is expanded into the items of the video's own menu — play next, save to Watch later, share, not interested, and the rest — presented in the **Actions** reading control just like actions the video natively supports, so there is no separate menu to open and browse. An **Open menu** action at the end still opens the menu as usual. An item the menu does not have for a particular video is reported as not available, and focus stays on the video either way.

This applies only in apps Meridian has a built-in script for, and only to items whose structure the script recognises; anything else is left exactly as it was. The scripts currently cover YouTube video items and Facebook comments. Facebook feed posts are already a single stop with their own actions, so they are unchanged. The setting is off by default.

### App icon that opens the settings

Meridian has an app icon in the launcher, labelled Meridian, that opens its settings. TalkBack's settings open only from the accessibility settings, which need TalkBack to be on. Meridian's settings open from the icon whether Meridian is on or off, so you can set it up, or change a setting, without turning it on first.

## Updates and installing

### Separate app

Meridian installs as `io.github.aaron_gh.meridian`, so it installs next to Google's TalkBack, with settings of its own. For more information, see [Install](README.md#install).

### Built-in updates

Meridian checks GitHub for new releases, and for development builds if you turn them on, and installs them when you tap its notification. For more information, see [Updates](README.md#updates).

### Appear installed from Google Play

Some apps, such as some banking apps, turn off accessibility services that Android records as installed from anywhere but Google Play. That stops a blind user who installed Meridian from a release from using it with those apps. **Advanced settings** > **Appear installed from Google Play**, off by default, makes Android record Google Play as Meridian's installer, so those apps keep working.

Android only records the installer when an app is installed, and only lets the shell or the system choose it, so Meridian reinstalls its own APK through Shizuku, naming Google Play. It is the same app, with the same settings; only what apps are told about where it came from changes. Reinstalling restarts Meridian, so speech stops for a moment. While the setting is on, the built-in updater installs updates the same way, so an update keeps the recorded installer.

Without Shizuku, the switch can't change the installer; its "Copy command" action gives the adb command to run from a computer. Turning the setting off records the installer Meridian had before. A banking app may still detect a sideloaded install in other ways.

If something records a different installer anyway, such as an update installed while Shizuku isn't running, Meridian puts Google Play back the next time Shizuku is available. While the setting is on but Google Play isn't recorded yet, the setting says so.

## Android TV

This fork doesn't include Android TV support: it runs on phones and tablets only.

## Wear OS watches

This fork doesn't include a watch version: it runs on phones and tablets only, and there is no `meridian-wear.apk`.
