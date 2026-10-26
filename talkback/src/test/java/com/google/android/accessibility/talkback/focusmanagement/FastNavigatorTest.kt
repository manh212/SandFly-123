/*
 * Copyright 2026 The SandFly Authors
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

package com.google.android.accessibility.talkback.focusmanagement

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.focusmanagement.action.NavigationAction
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenStateMonitor
import com.google.android.accessibility.talkback.focusmanagement.record.AccessibilityFocusActionHistory
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo
import com.google.android.accessibility.talkback.monitor.InputMethodMonitor
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.traversal.OrderedTraversalStrategy
import com.google.android.accessibility.utils.traversal.TraversalStrategy
import com.google.android.accessibility.utils.traversal.TraversalStrategyUtils
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The fast navigator, pinned against synthetic trees: index arithmetic in both directions and
 * at both edges under both wrap settings, the hard cases it must answer itself — a target that
 * died, an order that has not landed, a scroll at the end of the list, a focus action the app
 * refuses — and destination equivalence with the search the original navigation runs, on the
 * same trees from the same starts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FastNavigatorTest {

  /** Minimal service for Robolectric to instantiate the collaborators against. */
  class TestService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}
  }

  private class RecordingFocusListener : FastNavigator.FocusListener {
    val aboutToSet = mutableListOf<String>()
    val set = mutableListOf<String>()
    val failed = mutableListOf<String>()

    override fun onAccessibilityFocusAboutToBeSet(
      node: AccessibilityNodeInfoCompat,
      info: FocusActionInfo,
      eventId: Performance.EventId?,
      actionTime: Long,
    ) {
      aboutToSet += node.text.toString()
    }

    override fun onAccessibilityFocusSet(
      node: AccessibilityNodeInfoCompat,
      info: FocusActionInfo,
      eventId: Performance.EventId?,
      actionTime: Long,
    ) {
      set += node.text.toString()
    }

    override fun onAccessibilityFocusSetFailed(
      node: AccessibilityNodeInfoCompat,
      info: FocusActionInfo,
      eventId: Performance.EventId?,
      actionTime: Long,
    ) {
      failed += node.text.toString()
    }
  }

  private lateinit var navigator: FastNavigator
  private lateinit var listener: RecordingFocusListener
  private val feedbackCount = AtomicInteger()
  private val performance = Performance.getInstance()

  @Before
  fun setUp() {
    val service = Robolectric.setupService(TestService::class.java)
    val history = AccessibilityFocusActionHistory(service)
    val screenState = ScreenStateMonitor(service, InputMethodMonitor(service)).state
    navigator = FastNavigator(service, history, screenState) { false }
    listener = RecordingFocusListener()
    navigator.setFocusListener(listener)
    feedbackCount.set(0)
    navigator.setFeedbackReturner(
      Pipeline.FeedbackReturner {
        feedbackCount.incrementAndGet()
        true
      }
    )
    performance.setComputeStatsEnabled(true)
    FocusProcessorForLogicalNavigation.setWrapAround(true)
  }

  @After
  fun tearDown() {
    TraversalTreeCache.clearCompletely("test")
    // Navigations here mark the cache's burst clock, which other classes in this sandbox
    // would otherwise still see: the test clock does not advance on its own.
    TraversalTreeCache.noteNavigationActivity(0L)
    FocusProcessorForLogicalNavigation.setWrapAround(true)
  }

  private fun item(parent: AccessibilityNodeInfo?, tag: String, top: Int): AccessibilityNodeInfo {
    val node = AccessibilityNodeInfo.obtain()
    node.className = "android.widget.Button"
    node.packageName = "com.example.chat"
    node.isVisibleToUser = true
    node.isFocusable = true
    node.isClickable = true
    node.text = tag
    node.setBoundsInScreen(Rect(0, top, 600, top + 100))
    parent?.let { shadowOf(it).addChild(node) }
    return node
  }

  /** A vertical list of buttons named by [tags], in a plain container. */
  private fun listTree(tags: List<String>): AccessibilityNodeInfo =
    listTreeWithInfos(tags).first

  /**
   * The same list, with the child infos themselves: a shadow property a test sets — a dead
   * node's refresh, a refused action — must be set on these, before any order is built, since
   * every node an order hands out is a copy of them and copies what they hold when it is made.
   */
  private fun listTreeWithInfos(
    tags: List<String>
  ): Pair<AccessibilityNodeInfo, List<AccessibilityNodeInfo>> {
    val root = AccessibilityNodeInfo.obtain()
    root.className = "android.widget.FrameLayout"
    root.packageName = "com.example.chat"
    root.isVisibleToUser = true
    root.setBoundsInScreen(Rect(0, 0, 600, tags.size * 100))
    val children = tags.mapIndexed { index, tag -> item(root, tag, index * 100) }
    return root to children
  }

  /** Saves [rootInfo]'s order in the cache, and returns its children in tree order. */
  private fun saveOrder(
    rootInfo: AccessibilityNodeInfo
  ): List<AccessibilityNodeInfoCompat> {
    val root = AccessibilityNodeInfoCompat.wrap(rootInfo)
    TraversalTreeCache.put(root, OrderedTraversalStrategy(root))
    return (0 until root.childCount).map { checkNotNull(root.getChild(it)) }
  }

  /** Saves [rootInfo]'s order in the cache, and returns the root the order was read from. */
  private fun saveOrderRoot(rootInfo: AccessibilityNodeInfo): AccessibilityNodeInfoCompat {
    val root = AccessibilityNodeInfoCompat.wrap(rootInfo)
    TraversalTreeCache.put(root, OrderedTraversalStrategy(root))
    return root
  }

  /** A node with an explicit place and size: the pages, scrollers and items of a pager. */
  private fun placed(
    parent: AccessibilityNodeInfo?,
    tag: String?,
    rect: Rect,
    scrollable: Boolean = false,
    visible: Boolean = true,
    speaking: Boolean = true,
  ): AccessibilityNodeInfo {
    val node = AccessibilityNodeInfo.obtain()
    node.className = if (speaking) "android.widget.Button" else "android.widget.FrameLayout"
    node.packageName = "com.example.chat"
    node.isVisibleToUser = visible
    node.isScrollable = scrollable
    if (speaking) {
      node.isFocusable = true
      node.isClickable = true
      node.text = tag
    }
    node.setBoundsInScreen(rect)
    parent?.let { shadowOf(it).addChild(node) }
    return node
  }

  /** The node of [root]'s tree whose text is [tag], searching containers as well as items. */
  private fun findInTree(
    root: AccessibilityNodeInfoCompat,
    tag: String,
  ): AccessibilityNodeInfoCompat? {
    if (root.text?.toString() == tag) {
      return root
    }
    for (i in 0 until root.childCount) {
      val child = root.getChild(i) ?: continue
      findInTree(child, tag)?.let {
        return it
      }
    }
    return null
  }

  /** A two-page pager: page A on screen, page B composed but not yet shown. */
  private class PagerFixture(
    val rootInfo: AccessibilityNodeInfo,
    val pageAItems: List<AccessibilityNodeInfo>,
    val pageBItems: List<AccessibilityNodeInfo>,
  )

  private fun pagerTree(): PagerFixture {
    val root = AccessibilityNodeInfo.obtain()
    root.className = "androidx.recyclerview.widget.RecyclerView"
    root.packageName = "com.example.chat"
    root.isVisibleToUser = true
    root.isScrollable = true
    root.setBoundsInScreen(Rect(0, 0, 600, 2000))
    val pageA = placed(root, null, Rect(0, 0, 600, 1000), speaking = false)
    val pageB = placed(root, null, Rect(0, 1000, 600, 2000), speaking = false)
    val aItems =
      listOf("A1", "A2", "A3").mapIndexed { index, tag ->
        placed(pageA, tag, Rect(0, index * 100, 600, index * 100 + 100))
      }
    val bItems =
      listOf("B1", "B2", "B3").mapIndexed { index, tag ->
        placed(pageB, tag, Rect(0, 1000 + index * 100, 600, 1100 + index * 100), visible = false)
      }
    return PagerFixture(root, aItems, bItems)
  }

  /** The pager's turn, as the app reports it once the snap has settled: A off, B on. */
  private fun flipPager(fixture: PagerFixture) {
    fixture.pageAItems.forEach { it.isVisibleToUser = false }
    fixture.pageBItems.forEach { it.isVisibleToUser = true }
  }

  private fun focusOn(node: AccessibilityNodeInfoCompat) {
    node.unwrap().isAccessibilityFocused = true
    TraversalTreeCache.rememberFocus(node)
  }

  private fun swipe(
    forward: Boolean,
    wrap: Boolean = false,
    scroll: Boolean = false,
    targetType: Int = NavigationTarget.TARGET_DEFAULT,
  ): NavigationAction =
    NavigationAction.Builder()
      .setAction(NavigationAction.DIRECTIONAL_NAVIGATION)
      .setDirection(
        if (forward) {
          TraversalStrategy.SEARCH_FOCUS_FORWARD
        } else {
          TraversalStrategy.SEARCH_FOCUS_BACKWARD
        }
      )
      .setShouldWrap(wrap)
      .setShouldScroll(scroll)
      .setTarget(targetType)
      .build()

  @Test
  fun forward_fromTheMiddle_focusesTheNextItem_andTellsTheListenerAsItFires() {
    val children = saveOrder(listTree(listOf("A", "B", "C")))
    focusOn(children[1])
    val handledBefore = performance.getFastNavigationsHandledCount()

    assertTrue(navigator.handle(swipe(forward = true), null))

    assertEquals(listOf("C"), listener.set)
    assertEquals(listOf("C"), listener.aboutToSet)
    assertTrue(listener.failed.isEmpty())
    assertTrue(
      shadowOf(children[2].unwrap())
        .performedActions
        .contains(AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS)
    )
    assertEquals(handledBefore + 1, performance.getFastNavigationsHandledCount())
  }

  @Test
  fun backward_fromTheMiddle_focusesThePreviousItem() {
    val children = saveOrder(listTree(listOf("A", "B", "C")))
    focusOn(children[1])

    assertTrue(navigator.handle(swipe(forward = false), null))

    assertEquals(listOf("A"), listener.set)
  }

  @Test
  fun destinations_matchTheOriginalSearch_onTheSameTreesFromTheSameStarts() {
    // The fixture shape of the order tests: "A" declares itself before "B", so the reading
    // order (root, box, A, B, C) is not the child order, and the comparison cannot pass by
    // both sides reading the children in the order they were added.
    val rootInfo = AccessibilityNodeInfo.obtain()
    rootInfo.className = "android.widget.FrameLayout"
    rootInfo.packageName = "com.example.chat"
    rootInfo.isVisibleToUser = true
    rootInfo.setBoundsInScreen(Rect(0, 0, 600, 600))
    val box = item(rootInfo, "box", 0)
    val b = item(box, "B", 100)
    val a = item(box, "A", 0)
    shadowOf(a).setTraversalBefore(b)
    item(rootInfo, "C", 300)

    val root = AccessibilityNodeInfoCompat.wrap(rootInfo)
    val strategy = OrderedTraversalStrategy(root)
    TraversalTreeCache.put(root, strategy)
    val boxNode = checkNotNull(root.getChild(0))
    // The box's children were added B first, then A: child order is not reading order here.
    val nodeB = checkNotNull(boxNode.getChild(0))
    val nodeA = checkNotNull(boxNode.getChild(1))
    val nodeC = checkNotNull(root.getChild(1))
    val filter =
      NavigationTarget.createNodeFilter(NavigationTarget.TARGET_DEFAULT, strategy.speakingNodesCache)

    val cases =
      listOf(
        Triple(nodeA, true, "B"),
        Triple(nodeB, true, "C"),
        Triple(nodeB, false, "A"),
        Triple(nodeC, false, "B"),
      )
    for ((start, forward, expectedText) in cases) {
      val originalTarget =
        TraversalStrategyUtils.searchFocus(
          strategy,
          start,
          if (forward) {
            TraversalStrategy.SEARCH_FOCUS_FORWARD
          } else {
            TraversalStrategy.SEARCH_FOCUS_BACKWARD
          },
          filter,
        )
      assertEquals(expectedText, originalTarget?.text?.toString())

      focusOn(start)
      listener.set.clear()
      assertTrue(navigator.handle(swipe(forward), null))
      assertEquals(listOf(expectedText), listener.set)
      start.unwrap().isAccessibilityFocused = false
    }
  }

  @Test
  fun edge_withoutWrapOrScroll_announcesTheEdge_andMovesNothing() {
    val children = saveOrder(listTree(listOf("A", "B", "C")))
    focusOn(children[2])
    val handledBefore = performance.getFastNavigationsHandledCount()

    assertFalse(navigator.handle(swipe(forward = true), null))

    assertTrue(listener.set.isEmpty())
    // The old path's own edge announcement says nothing for plain items — its text names a
    // reading control, and plain items have none — so equivalence is silence here too; the
    // swipe is answered and counted, not swallowed.
    assertEquals(0, feedbackCount.get())
    assertEquals(handledBefore + 1, performance.getFastNavigationsHandledCount())
  }

  @Test
  fun edge_forANamedReadingControl_announcesTheEdge() {
    val children = saveOrder(listTree(listOf("A", "B", "C")))
    focusOn(children[2])

    assertFalse(
      navigator.handle(swipe(forward = true, targetType = NavigationTarget.TARGET_HEADING), null))

    assertTrue(listener.set.isEmpty())
    assertEquals(1, feedbackCount.get())
  }

  @Test
  fun edge_withWrap_focusesTheFirstItem() {
    val children = saveOrder(listTree(listOf("A", "B", "C")))
    focusOn(children[2])

    assertTrue(navigator.handle(swipe(forward = true, wrap = true), null))

    assertEquals(listOf("A"), listener.set)
  }

  @Test
  fun edge_wrapActionButSettingOff_announcesTheEdge() {
    FocusProcessorForLogicalNavigation.setWrapAround(false)
    val children = saveOrder(listTree(listOf("A", "B", "C")))
    focusOn(children[2])

    assertFalse(navigator.handle(swipe(forward = true, wrap = true), null))

    assertTrue(listener.set.isEmpty())
    assertEquals(0, feedbackCount.get())
  }

  @Test
  fun deadTarget_theNearestLivingItemInTheDirectionStandsIn() {
    val (rootInfo, childInfos) = listTreeWithInfos(listOf("A", "B", "C", "D"))
    // B is gone by the time the swipe asks for it again: targets are not refreshed before
    // firing, so a dead one shows itself by refusing the focus action.
    shadowOf(childInfos[1]).setOnPerformActionListener { action, _ ->
      action != AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS
    }
    val children = saveOrder(rootInfo)
    focusOn(children[0])
    val substitutedBefore =
      performance.getFastNavigationHardCaseCount(Performance.FAST_NAV_HARD_DEAD_TARGET_SUBSTITUTED)

    assertTrue(navigator.handle(swipe(forward = true), null))

    assertEquals(listOf("C"), listener.set)
    assertEquals(
      substitutedBefore + 1,
      performance.getFastNavigationHardCaseCount(
        Performance.FAST_NAV_HARD_DEAD_TARGET_SUBSTITUTED
      ),
    )
  }

  @Test
  fun allTargetsRefuse_countsTheFailure_andStillAnnouncesTheEdge() {
    val (rootInfo, childInfos) = listTreeWithInfos(listOf("A", "B"))
    // The only target refuses the focus action, and refuses it again in the rebuilt order.
    shadowOf(childInfos[1]).setOnPerformActionListener { action, _ ->
      action != AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS
    }
    val children = saveOrder(rootInfo)
    focusOn(children[0])
    val stuckBefore =
      performance.getFastNavigationStuckCount(Performance.FAST_NAV_STUCK_FOCUS_ACTION_FAILED)

    assertFalse(navigator.handle(swipe(forward = true), null))

    assertTrue(listener.set.isEmpty())
    // Loud in the numbers, as required; the edge announcement is the old path's own, which
    // says nothing for plain items.
    assertEquals(0, feedbackCount.get())
    assertEquals(
      stuckBefore + 1,
      performance.getFastNavigationStuckCount(Performance.FAST_NAV_STUCK_FOCUS_ACTION_FAILED),
    )
  }

  @Test
  fun focusActionRefused_buildsTheOrderOnTheSpotAndTriesOnceMore_thenCountsTheFailure() {
    val (rootInfo, childInfos) = listTreeWithInfos(listOf("A", "B", "C"))
    // The app refuses the focus action on C, however fresh the node it is asked with.
    shadowOf(childInfos[2]).setOnPerformActionListener { action, _ ->
      action != AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS
    }
    val children = saveOrder(rootInfo)
    focusOn(children[1])
    val builtBefore =
      performance.getFastNavigationHardCaseCount(Performance.FAST_NAV_HARD_BUILT_ON_SPOT)
    val stuckBefore =
      performance.getFastNavigationStuckCount(Performance.FAST_NAV_STUCK_FOCUS_ACTION_FAILED)

    assertFalse(navigator.handle(swipe(forward = true), null))

    // The refusal was retried exactly once, from an order built on the spot after the saved
    // one was thrown away, and then counted — not looped on, and not handed anywhere else.
    assertEquals(listOf("C", "C"), listener.failed)
    assertTrue(listener.set.isEmpty())
    assertEquals(
      builtBefore + 1,
      performance.getFastNavigationHardCaseCount(Performance.FAST_NAV_HARD_BUILT_ON_SPOT),
    )
    assertEquals(
      stuckBefore + 1,
      performance.getFastNavigationStuckCount(Performance.FAST_NAV_STUCK_FOCUS_ACTION_FAILED),
    )
  }

  @Test
  fun edgeWithScroll_scrollsItself_andGivesUpLoudlyAndEarlyWhenTheScrollChangesNothing() {
    val rootInfo = listTree(listOf("A", "B"))
    rootInfo.isScrollable = true
    val root = AccessibilityNodeInfoCompat.wrap(rootInfo)
    val children = saveOrder(rootInfo)
    focusOn(children[1])
    val scrolledBefore =
      performance.getFastNavigationHardCaseCount(Performance.FAST_NAV_HARD_SELF_SCROLL)
    val stuckBefore =
      performance.getFastNavigationStuckCount(Performance.FAST_NAV_STUCK_SCROLL_NO_LANDING)
    val noEffectBefore = performance.getScrollNoEffectCount()
    val noNewItemBefore =
      performance.getScrollLandingsFailedCount(Performance.SCROLL_LANDING_FAIL_NO_NEWLY_VISIBLE)

    assertTrue(navigator.handle(swipe(forward = true, scroll = true), null))

    assertTrue(
      shadowOf(root.unwrap())
        .performedActions
        .contains(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)
    )
    assertEquals(
      scrolledBefore + 1,
      performance.getFastNavigationHardCaseCount(Performance.FAST_NAV_HARD_SELF_SCROLL),
    )

    // Nothing ever changes in this tree: the silence is judged at the no-effect window, not
    // at the landing deadline, so by half a second the failure is already counted — with
    // its reason — and the edge is answered with the old path's own announcement, which
    // says nothing for plain items, so the loud part here is the count, exactly as specified.
    shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
    assertEquals(
      stuckBefore + 1,
      performance.getFastNavigationStuckCount(Performance.FAST_NAV_STUCK_SCROLL_NO_LANDING),
    )
    assertEquals(noEffectBefore + 1, performance.getScrollNoEffectCount())
    assertEquals(
      noNewItemBefore + 1,
      performance.getScrollLandingsFailedCount(Performance.SCROLL_LANDING_FAIL_NO_NEWLY_VISIBLE),
    )
    assertEquals(0, feedbackCount.get())

    // And it is given up on exactly once.
    shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
    assertEquals(
      stuckBefore + 1,
      performance.getFastNavigationStuckCount(Performance.FAST_NAV_STUCK_SCROLL_NO_LANDING),
    )
  }

  @Test
  fun scrollLanding_afterTheContentSettles_landsTheFirstNewItem() {
    val rootInfo = listTree(listOf("A", "B"))
    rootInfo.isScrollable = true
    val children = saveOrder(rootInfo)
    focusOn(children[1])
    val byEventBefore = performance.getScrollLandingsByEventCount()
    val bySettleBefore =
      performance.getScrollLandingsBySourceCount(Performance.SCROLL_LANDING_BY_SETTLE)

    assertTrue(navigator.handle(swipe(forward = true, scroll = true), null))

    // The content the scroll was fired for arrives: C joins the window, and the saved order
    // is the new one, as the background build would leave it.
    item(rootInfo, "C", 200)
    saveOrder(rootInfo)
    navigator.onWindowContentChanged(rootInfo.windowId)

    // The change alone does not land the swipe: a landing waits for the window to go quiet,
    // so a change still in flight can never be read as the settled content.
    shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
    assertTrue(listener.set.isEmpty())

    shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
    assertEquals(listOf("C"), listener.set)
    assertEquals(byEventBefore + 1, performance.getScrollLandingsByEventCount())
    assertEquals(
      bySettleBefore + 1,
      performance.getScrollLandingsBySourceCount(Performance.SCROLL_LANDING_BY_SETTLE),
    )
  }

  @Test
  fun scrollLanding_pagerTurn_landsTheFirstItemOfTheNewPage() {
    val fixture = pagerTree()
    val root = saveOrderRoot(fixture.rootInfo)
    focusOn(checkNotNull(findInTree(root, "A3")))
    val bySettleBefore =
      performance.getScrollLandingsBySourceCount(Performance.SCROLL_LANDING_BY_SETTLE)

    assertTrue(navigator.handle(swipe(forward = true, scroll = true), null))
    assertTrue(
      shadowOf(fixture.rootInfo)
        .performedActions
        .contains(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)
    )

    flipPager(fixture)
    saveOrder(fixture.rootInfo)
    navigator.onWindowContentChanged(fixture.rootInfo.windowId)
    shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)

    assertEquals(listOf("B1"), listener.set)
    assertEquals(
      bySettleBefore + 1,
      performance.getScrollLandingsBySourceCount(Performance.SCROLL_LANDING_BY_SETTLE),
    )
  }

  @Test
  fun scrollLanding_pagerRecyclesTheAnchorItem_landsByWindowAndPositionAnyway() {
    val fixture = pagerTree()
    val root = saveOrderRoot(fixture.rootInfo)
    focusOn(checkNotNull(findInTree(root, "A3")))

    assertTrue(navigator.handle(swipe(forward = true, scroll = true), null))

    // The pager recycles the very item the swipe started from: the window's new tree has no
    // A3 at all — its view now carries new-page content — and page B is what is on screen.
    // A1 and A2 are still on screen and still the same views, so the new tree holds the very
    // same nodes for them, as a re-read of the window would hand back; only A3 is gone.
    // The landing is anchored on the window and on position, so nothing it needs is gone.
    val turned = AccessibilityNodeInfo.obtain()
    turned.className = "android.widget.FrameLayout"
    turned.packageName = "com.example.chat"
    turned.isVisibleToUser = true
    turned.isScrollable = true
    turned.setBoundsInScreen(Rect(0, 0, 600, 2000))
    shadowOf(turned).addChild(fixture.pageAItems[0])
    shadowOf(turned).addChild(fixture.pageAItems[1])
    placed(turned, "B1", Rect(0, 200, 600, 300))
    placed(turned, "B2", Rect(0, 300, 600, 400))
    placed(turned, "B3", Rect(0, 400, 600, 500))
    saveOrder(turned)
    navigator.onWindowContentChanged(turned.windowId)
    shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)

    assertEquals("landed", listOf("B1"), listener.set)
  }

  @Test
  fun scrollLanding_churnKeepsTheLandingWaiting_untilTheTreeSettles() {
    val rootInfo = listTree(listOf("A", "B"))
    rootInfo.isScrollable = true
    val children = saveOrder(rootInfo)
    focusOn(children[1])
    val bySettleBefore =
      performance.getScrollLandingsBySourceCount(Performance.SCROLL_LANDING_BY_SETTLE)

    assertTrue(navigator.handle(swipe(forward = true, scroll = true), null))

    // C joins the window at once but stays off screen while the window churns: five
    // changes, each starting the quiet window over, none letting it close — and no landing
    // try may run against any of the trees in between.
    val cInfo = item(rootInfo, "C", 200)
    cInfo.isVisibleToUser = false
    repeat(5) {
      saveOrder(rootInfo)
      navigator.onWindowContentChanged(rootInfo.windowId)
      shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
      assertTrue(listener.set.isEmpty())
    }

    // The churn stops with C on screen: the first quiet window lands the swipe, once.
    cInfo.isVisibleToUser = true
    saveOrder(rootInfo)
    navigator.onWindowContentChanged(rootInfo.windowId)
    shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
    assertEquals(listOf("C"), listener.set)
    assertEquals(
      bySettleBefore + 1,
      performance.getScrollLandingsBySourceCount(Performance.SCROLL_LANDING_BY_SETTLE),
    )
  }

  @Test
  fun scrollLanding_anOrderFromBeforeTheScrollIsNeverServedToIt() {
    val rootInfo = listTree(listOf("A", "B"))
    rootInfo.isScrollable = true
    val children = saveOrder(rootInfo)
    focusOn(children[1])
    val refusedBefore = performance.getScrollLandingPreScrollOrderReadsCount()

    assertTrue(navigator.handle(swipe(forward = true, scroll = true), null))

    // The scroll's own change arrives while the pre-scroll order is still the saved one:
    // the landing is offered it, refuses it by its generation, and lands nothing.
    navigator.onWindowContentChanged(rootInfo.windowId)
    shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
    assertEquals(refusedBefore + 1, performance.getScrollLandingPreScrollOrderReadsCount())
    assertTrue(listener.set.isEmpty())

    // The post-scroll order lands, and with it the item.
    item(rootInfo, "C", 200)
    saveOrder(rootInfo)
    navigator.onWindowContentChanged(rootInfo.windowId)
    shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
    assertEquals(listOf("C"), listener.set)
  }

  @Test
  fun scrollLanding_theAimedItemDroppedByTheApp_landsTheNewItemAnyway() {
    val (rootInfo, childInfos) = listTreeWithInfos(listOf("A", "B", "C"))
    rootInfo.isScrollable = true
    // C is in the order but off screen: the swipe aims at it, and scrolls to bring it in.
    // The swipe is built as the gesture path builds it, with scrolling allowed: past the
    // last on-screen item is where a swipe may scroll.
    childInfos[2].isVisibleToUser = false
    val children = saveOrder(rootInfo)
    focusOn(children[1])

    assertTrue(navigator.handle(swipe(forward = true, scroll = true), null))
    assertTrue(
      shadowOf(rootInfo)
        .performedActions
        .contains(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)
    )

    // The app drops C outright and brings D instead. A and B are still on screen and
    // still the same views, so the window's new tree holds the very same nodes for them,
    // as a re-read of the window would hand back; only C is gone and D is new.
    val turned = AccessibilityNodeInfo.obtain()
    turned.className = "android.widget.FrameLayout"
    turned.packageName = "com.example.chat"
    turned.isVisibleToUser = true
    turned.isScrollable = true
    turned.setBoundsInScreen(Rect(0, 0, 600, 300))
    shadowOf(turned).addChild(childInfos[0])
    shadowOf(turned).addChild(childInfos[1])
    item(turned, "D", 200)
    saveOrder(turned)
    navigator.onWindowContentChanged(turned.windowId)
    shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)

    assertEquals(listOf("D"), listener.set)
  }

  @Test
  fun scrollLanding_aSecondScrollSupersedesTheFirstLanding() {
    val rootInfo = listTree(listOf("A", "B"))
    rootInfo.isScrollable = true
    val children = saveOrder(rootInfo)
    focusOn(children[1])
    val supersededBefore = performance.getScrollLandingsSupersededCount()

    assertTrue(navigator.handle(swipe(forward = true, scroll = true), null))
    assertTrue(navigator.handle(swipe(forward = true, scroll = true), null))
    assertEquals(supersededBefore + 1, performance.getScrollLandingsSupersededCount())

    // Only the second scroll's landing is alive to land, and it lands exactly once.
    item(rootInfo, "C", 200)
    saveOrder(rootInfo)
    navigator.onWindowContentChanged(rootInfo.windowId)
    shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
    assertEquals(listOf("C"), listener.set)
  }

  @Test
  fun scrollFor_aNestedScrollerInsideAnItem_doesNotStealThePagesScroll() {
    val rootInfo = AccessibilityNodeInfo.obtain()
    rootInfo.className = "android.widget.FrameLayout"
    rootInfo.packageName = "com.example.chat"
    rootInfo.isVisibleToUser = true
    rootInfo.isScrollable = true
    rootInfo.setBoundsInScreen(Rect(0, 0, 600, 2000))
    val row = placed(rootInfo, null, Rect(0, 0, 600, 300), speaking = false)
    val chipScroller =
      placed(row, null, Rect(0, 0, 600, 100), scrollable = true, speaking = false)
    placed(chipScroller, "chip1", Rect(0, 0, 300, 100))
    placed(chipScroller, "chip2", Rect(300, 0, 600, 100))
    val lastInfo = placed(rootInfo, "last", Rect(0, 300, 600, 400), visible = false)

    val root = saveOrderRoot(rootInfo)
    focusOn(checkNotNull(findInTree(root, "chip2")))

    // The next item is off screen, so the swipe scrolls — and the scroll must fire on the
    // page's own scroller, not on the small chip row nested inside the item: the chip row
    // would answer true while the page never moves. The swipe is built as the gesture
    // path builds it, with scrolling allowed.
    assertTrue(navigator.handle(swipe(forward = true, scroll = true), null))
    assertTrue(
      shadowOf(rootInfo)
        .performedActions
        .contains(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)
    )
    assertFalse(
      shadowOf(chipScroller)
        .performedActions
        .contains(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)
    )

    lastInfo.isVisibleToUser = true
    saveOrder(rootInfo)
    navigator.onWindowContentChanged(rootInfo.windowId)
    shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
    assertEquals(listOf("last"), listener.set)
  }

  @Test
  fun noPivotAndNoWindow_countsNoPivot_andMovesNothing() {
    val stuckBefore =
      performance.getFastNavigationStuckCount(Performance.FAST_NAV_STUCK_NO_PIVOT)

    assertFalse(navigator.handle(swipe(forward = true), null))

    assertTrue(listener.set.isEmpty())
    assertEquals(
      stuckBefore + 1,
      performance.getFastNavigationStuckCount(Performance.FAST_NAV_STUCK_NO_PIVOT),
    )
  }

  @Test
  fun landingIndex_firstNewItemPastTheSharedOnes() {
    val a = node("A")
    val b = node("B")
    val c = node("C")
    val d = node("D")
    val old = listOf(a, b)
    // After a forward scroll the orders overlap on B; the landing is C, the first new item.
    assertEquals(2, FastNavigator.landingIndex(listOf(a, b, c, d), old, true) { true })
    // Nothing new: no landing.
    assertEquals(-1, FastNavigator.landingIndex(listOf(a, b), old, true) { true })
    // No overlap at all: the first item on screen in the direction.
    assertEquals(0, FastNavigator.landingIndex(listOf(c, d), old, true) { true })
    // Backward mirrors it: the last new item before the shared ones.
    assertEquals(1, FastNavigator.landingIndex(listOf(d, c, a, b), old, false) { true })
    // Invisible items are skipped.
    assertEquals(3, FastNavigator.landingIndex(listOf(a, b, c, d), old, true) { it == d })
  }

  @Test
  fun nextCandidateIndex_walksTheFilteredListBothWays() {
    val a = node("A")
    val b = node("B")
    val c = node("C")
    val all = listOf(a, b, c)
    val filterAll =
      object : com.google.android.accessibility.utils.Filter<AccessibilityNodeInfoCompat>() {
        override fun accept(node: AccessibilityNodeInfoCompat) = true
      }
    val filterNotB =
      object : com.google.android.accessibility.utils.Filter<AccessibilityNodeInfoCompat>() {
        override fun accept(node: AccessibilityNodeInfoCompat) = node != b
      }

    assertEquals(1, FastNavigator.nextCandidateIndex(all, 0, true, filterAll))
    assertEquals(2, FastNavigator.nextCandidateIndex(all, 0, true, filterNotB))
    assertEquals(-1, FastNavigator.nextCandidateIndex(all, 2, true, filterAll))
    assertEquals(1, FastNavigator.nextCandidateIndex(all, 2, false, filterAll))
    assertEquals(0, FastNavigator.nextCandidateIndex(all, 2, false, filterNotB))
    assertEquals(1, FastNavigator.indexOfNode(all, b))
    assertEquals(-1, FastNavigator.indexOfNode(all, node("Z")))
  }

  @Test
  fun deadTarget_skipRefreshOn_refusedActionStepsOnWithinTheSameOrder() {
    val (rootInfo, childInfos) = listTreeWithInfos(listOf("A", "B", "C", "D"))
    // B is dead by the time the swipe reaches it: with no refresh before firing, the refused
    // focus action itself is what reports it.
    shadowOf(childInfos[1]).setOnPerformActionListener { action, _ ->
      action != AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS
    }
    val children = saveOrder(rootInfo)
    focusOn(children[0])
    val substitutedBefore =
      performance.getFastNavigationHardCaseCount(Performance.FAST_NAV_HARD_DEAD_TARGET_SUBSTITUTED)
    val builtBefore =
      performance.getFastNavigationHardCaseCount(Performance.FAST_NAV_HARD_BUILT_ON_SPOT)

    assertTrue(navigator.handle(swipe(forward = true), null))

    // The swipe stepped past B inside the order it already held — no rebuild was paid for —
    // and landed on C, the nearest living item in the direction, counted as a substitution.
    assertEquals(listOf("B"), listener.failed)
    assertEquals(listOf("C"), listener.set)
    assertEquals(
      substitutedBefore + 1,
      performance.getFastNavigationHardCaseCount(
        Performance.FAST_NAV_HARD_DEAD_TARGET_SUBSTITUTED
      ),
    )
    assertEquals(
      builtBefore,
      performance.getFastNavigationHardCaseCount(Performance.FAST_NAV_HARD_BUILT_ON_SPOT),
    )
  }

  @Test
  fun skipRefreshOn_aTargetWhoseRefreshWouldFailIsFiredAnyway() {
    val (rootInfo, childInfos) = listTreeWithInfos(listOf("A", "B", "C"))
    // B's refresh would report it gone, but nothing asks: the refresh is skipped, the action
    // is the only vouching, and the action succeeds.
    shadowOf(childInfos[1]).setRefreshReturnValue(false)
    val children = saveOrder(rootInfo)
    focusOn(children[0])

    assertTrue(navigator.handle(swipe(forward = true), null))

    assertEquals(listOf("B"), listener.set)
  }

  @Test
  fun fireFirst_theActionGoesOutBeforeTheSpeechIsTold() {
    val (rootInfo, childInfos) = listTreeWithInfos(listOf("A", "B"))
    val order = mutableListOf<String>()
    shadowOf(childInfos[1]).setOnPerformActionListener { action, _ ->
      if (action == AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS) {
        order += "action"
      }
      true
    }
    val children = saveOrder(rootInfo)
    focusOn(children[0])
    navigator.setFocusListener(
      object : FastNavigator.FocusListener {
        override fun onAccessibilityFocusAboutToBeSet(
          node: AccessibilityNodeInfoCompat,
          info: FocusActionInfo,
          eventId: Performance.EventId?,
          actionTime: Long,
        ) {
          order += "speech"
        }

        override fun onAccessibilityFocusSet(
          node: AccessibilityNodeInfoCompat,
          info: FocusActionInfo,
          eventId: Performance.EventId?,
          actionTime: Long,
        ) {}

        override fun onAccessibilityFocusSetFailed(
          node: AccessibilityNodeInfoCompat,
          info: FocusActionInfo,
          eventId: Performance.EventId?,
          actionTime: Long,
        ) {}
      }
    )

    assertTrue(navigator.handle(swipe(forward = true), null))

    assertEquals(listOf("action", "speech"), order)
  }

  private fun node(tag: String): AccessibilityNodeInfoCompat {
    val info = AccessibilityNodeInfo.obtain()
    info.className = "android.widget.Button"
    info.text = tag
    return AccessibilityNodeInfoCompat.wrap(info)
  }
}
