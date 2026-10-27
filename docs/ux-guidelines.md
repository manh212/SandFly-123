# Interface and experience guidelines

These rules govern every new or changed piece of user interface in this fork of
SandFly. A feature that breaks them is not done, even if it builds.

## Clarify behavior before implementation

For a requested feature, ask the owner about the relevant actions, navigation,
feedback, defaults, accessibility and failure behavior one question at a time.
Wait for each answer, then summarize the agreed behavior and acceptance criteria.
Begin implementation only after explicit approval, as required by
[CONTRIBUTING.md](../CONTRIBUTING.md#feature-clarification-and-approval).
Choose only tests relevant to the approved change and its affected interactions.

## Settings organization

- Keep Updates first on the main settings screen.
- The main screen contains navigation entries only. Controls belong inside their
  related settings pages.
- The Navigation landing page also contains topic links only. Do not mix direct
  controls with links to other navigation topics.
- Keep backup, restore and application reset together in their own main entry.
- Place each setting with the task it affects. Avoid catch-all screens such as
  Verbosity when their controls belong to several different tasks.
- A nested page must configure its parent topic directly. Remove pages that only
  add an unnecessary navigation step.

## Labels

- Every interactive element **must** have an accessibility label. No exceptions:
  a feature whose controls are missing labels is not finished.
- A label is a **name only**, and the name must fully express the element's
  function.
- Do **not** add a separate description or hint that repeats what the name
  already says. Verbose, redundant descriptions are a defect, not a help.

Examples:

- Right: a button named "Gửi tin nhắn".
- Wrong: a button named "Máy bay giấy" — the name describes the icon, not the
  function.
- Wrong: a button named "Gửi tin nhắn" plus a description "Nút gửi tin nhắn,
  chạm để gửi tin nhắn" — the description only repeats the name.

## Spoken announcements

- Keep announcements to the minimum. Announce only when it is truly necessary.
- Keep each announcement as short as possible.
- Never talk over content the user is currently hearing.

## Focus order

- Moving through a screen sequentially must follow the screen's logical reading
  order.
- Review the source of every new or changed screen for logical focus order.
- The owner checks focus order and screen reader behavior on a phone after the
  dev build is available. The coding agent does not perform device testing.

## Gestures

- Add new gestures only within the behavior explicitly approved by the owner.

## Checklist for interface work

- [ ] Every interactive element has a name-only label that fully states its function.
- [ ] No redundant descriptions or hints anywhere.
- [ ] Announcements are minimal and short.
- [ ] Source reviewed for logical focus order.

The owner performs the on-device checks after receiving the dev build.
