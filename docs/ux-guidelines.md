# Interface and experience guidelines

These rules govern every new or changed piece of user interface in this fork of
SandFly. A feature that breaks them is not done, even if it builds.

## Labels

- Every interactive element **must** have an accessibility label. No exceptions:
  a feature whose controls are missing labels is not finished.
- A label is a **name only**, and the name must fully express the element's
  function.
- Do **not** add a separate description or hint that repeats what the name
  already says. Verbose, redundant descriptions are a defect, not a help.

Examples:

- Right: a button named "Send message".
- Wrong: a button named "Paper plane" — the name describes the icon, not the
  function.
- Wrong: a button named "Send message" plus a description "Send message button,
  tap to send your message" — the description only repeats the name.

## Spoken announcements

- Keep announcements to the minimum. Announce only when it is truly necessary.
- Keep each announcement as short as possible.
- Never talk over content the user is currently hearing.

## Focus order

- Moving through a screen sequentially must follow the screen's logical reading
  order.
- Walk through every new or changed screen in order before calling it done.

## Gestures

- New gestures may be added freely.
- Every added gesture must be recorded in the registry below, with a plain
  description of what it does, in the same change that adds it.
- Removed gestures are recorded in [differences.md](../differences.md), in the
  same change that removes them.

### Gesture registry

| Gesture | Action | Added in |
| --- | --- | --- |
| _(none yet)_ | | |

## Checklist for interface work

- [ ] Every interactive element has a name-only label that fully states its function.
- [ ] No redundant descriptions or hints anywhere.
- [ ] Announcements are minimal and short.
- [ ] Focus order walked through and logical.
- [ ] New gestures entered in the registry above.
