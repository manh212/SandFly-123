package com.Braille;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BrailleSettingsMenuTest {
    @Test
    public void menuScrollPreservesEveryButtonInOrder() {
        Context context = RuntimeEnvironment.getApplication();
        LinearLayout items = new LinearLayout(context);
        items.setOrientation(LinearLayout.VERTICAL);
        String[] titles = {
                "Cài đặt bàn phím",
                "Tăng tốc khi nhập",
                "Âm thanh và Rung",
                "Cài đặt biểu tượng",
                "Cài đặt cử chỉ",
                "Cài đặt bảng chữ"
        };
        for (String title : titles) {
            Button button = new Button(context);
            button.setText(title);
            items.addView(button);
        }

        ScrollView scroll = BrailleSettingsMenu.wrap(context, items);
        assertTrue(scroll.isFillViewport());
        assertSame(items, scroll.getChildAt(0));
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, items.getLayoutParams().width);
        assertEquals(titles.length, items.getChildCount());
        for (int i = 0; i < titles.length; i++) {
            Button button = (Button) items.getChildAt(i);
            assertEquals(titles[i], button.getText().toString());
            assertTrue(button.isFocusable());
        }
    }

    @Test
    public void statusAndNavigationInsetsKeepFirstAndLastButtonsAccessible() {
        LinearLayout root = new LinearLayout(RuntimeEnvironment.getApplication());
        BrailleSettingsMenu.applySafePadding(root, 10, 55, 24);
        assertEquals(10, root.getPaddingLeft());
        assertEquals(65, root.getPaddingTop());
        assertEquals(34, root.getPaddingBottom());
        BrailleSettingsMenu.applySafePadding(root, 10, -7, -9);
        assertEquals(10, root.getPaddingTop());
        assertEquals(10, root.getPaddingBottom());
    }
}
