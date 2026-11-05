# Braille input speed settings

**Tăng tốc khi nhập** in the Braille keyboard's settings offers accessible
sliders for letter delay (1–300 ms, default 50), swipe sensitivity
(24–120 dp, default 68), gesture recognition time (100–5000 ms, default 1000),
and long-swipe distance (130–300 dp, default 160). Short/long distinction is
disabled by default. When enabled, short swipes preserve existing assignments
and independently configured long swipes can be assigned under Cài đặt cử chỉ.
Unassigned long swipes fall back to the corresponding short action. Resetting
gesture assignments clears long overrides. Both the Android IME and preview use
saved preferences. Multi-finger preview chords are committed on release instead
of a short timer.

CI tests include BrailleSpeedSettingsTest and BrailleKeyboardServiceTest.
Real-device checks should cover staggered chords, hold-then-swipe, cancellation,
short/long motions, duration and distance limits, reset behavior and accessible
slider adjustments. A 1 ms timer does not guarantee 1 ms hardware latency.

## Menu cài đặt có thể truy cập

Mục **Tăng tốc khi nhập** nằm bên trong **Cài đặt bàn phím**, không phải
nút riêng ở menu chính. Menu chính có thể cuộn và chừa khoảng an toàn
cho thanh trạng thái và thanh điều hướng Android.
Vì vậy các nút đầu tiên và cuối cùng không bị khuất trên màn hình nhỏ
hoặc khi dùng chữ lớn; TalkBack duyệt được các nút theo thứ tự.
