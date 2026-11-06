# Navigation group management

The entry at **Menu và bảng điều khiển → Nhóm điều hướng** opens the selector-group
list. The first row, **Thêm nhóm mới**, starts a separate creation screen.
Existing groups retain their order; new groups are appended. Group reordering
and the old custom screen-reader swipe actions on group rows are not exposed.

## Group screens

Selecting a group opens its management page with these controls, in order:

1. **Đổi tên nhóm** opens a name-edit dialog with the existing name filled in.
2. **Chỉnh sửa các mục** opens a two-section item editor.
3. **Bật nhóm** is a direct switch.
4. **Xóa nhóm** appears last and requires confirmation.

A group name cannot be blank or equal to another group's trimmed name,
case-insensitively. The active enabled-group invariant is preserved.

## Item editor

The first section, **Các mục đang có**, shows the group's ordered items.
Selecting an item opens a short dialog containing applicable **Di chuyển lên**,
**Di chuyển xuống**, and **Xóa khỏi nhóm** actions. No separate sort screen is
provided. The second section, **Các mục có thể thêm**, contains everything not
already in the current group. Selecting an item appends it immediately. An item
may be present in several groups simultaneously; adding or removing it from one
group never moves it out of another.

Removal of the last item automatically deletes its group. The action is blocked
when it would leave no navigation group or no enabled group. Other removal and
reordering actions take effect immediately. After reordering or removing a
non-final item, focus returns to the moved item or the nearest remaining item.

## Create new group

The creation page contains an inline name field, a multi-select item picker,
**Tạo nhóm**, and **Hủy**. Selection is temporary until **Tạo nhóm** is pressed.
The new group requires a nonempty name and at least one item. Canceling or
returning without creating a group leaves all existing groups and items intact.
Successful creation returns to the list and focuses the newly appended group.

## Validation

Relevant automated coverage includes `SelectorGroupManagerTest` for shared
items, unique names, automatic deletion, and minimum-group invariants, plus
`SelectorGroupScreensTest` for first-row creation and nested-screen navigation.
The owner verifies touch exploration, spoken announcements, and accessibility
focus on the published dev build, including after add, remove, rename, and
reorder operations.
