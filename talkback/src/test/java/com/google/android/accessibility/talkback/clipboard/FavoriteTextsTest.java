package com.google.android.accessibility.talkback.clipboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FavoriteTextsTest {
  @Rule public TemporaryFolder folder = new TemporaryFolder();
  private File directory;

  @Before
  public void setUp() throws Exception {
    directory = folder.newFolder();
    FavoriteTexts.init(directory);
  }

  @Test
  public void textAndOrderSurviveReloadWithoutDuplicates() {
    assertTrue(FavoriteTexts.add("  Xin chào\n😀  "));
    assertTrue(FavoriteTexts.add("second"));
    assertTrue(FavoriteTexts.add("second"));
    FavoriteTexts.init(directory);
    assertEquals(Arrays.asList("second", "  Xin chào\n😀  "), FavoriteTexts.getEntries());
  }

  @Test
  public void removingClipboardHistoryDoesNotRemoveFavorites() {
    FavoriteTexts.add("saved");
    ClipboardHistory.init(null);
    ClipboardHistory.record("saved");
    ClipboardHistory.clear();
    assertEquals(Arrays.asList("saved"), FavoriteTexts.getEntries());
  }

  @Test
  public void deletionAndClearPersist() {
    FavoriteTexts.add("one");
    FavoriteTexts.add("two");
    assertTrue(FavoriteTexts.remove("one"));
    FavoriteTexts.init(directory);
    assertEquals(Arrays.asList("two"), FavoriteTexts.getEntries());
    assertTrue(FavoriteTexts.clear());
    FavoriteTexts.init(directory);
    assertTrue(FavoriteTexts.getEntries().isEmpty());
  }

  @Test
  public void emptyTextAndFailedWritesDoNotCreateEntries() throws Exception {
    assertFalse(FavoriteTexts.add(" \n "));
    assertFalse(FavoriteTexts.add(null));
    FavoriteTexts.init(folder.newFile());
    assertFalse(FavoriteTexts.add("cannot save"));
    assertTrue(FavoriteTexts.getEntries().isEmpty());
  }

  @Test
  public void backupOnlyStoreIsRecovered() throws Exception {
    Files.write(new File(directory, "favorite-texts.json.bak").toPath(),
        "[\"recovered\"]".getBytes(StandardCharsets.UTF_8));
    FavoriteTexts.init(directory);
    assertEquals(Arrays.asList("recovered"), FavoriteTexts.getEntries());
  }
}
