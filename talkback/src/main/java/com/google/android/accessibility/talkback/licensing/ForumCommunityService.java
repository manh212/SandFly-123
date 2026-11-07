package com.google.android.accessibility.talkback.licensing;

import android.content.Context;
import io.meridian.licensing.FirebaseRest;
import io.meridian.licensing.ForumProtocol;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class ForumCommunityService {
  private final Context context;
  private final FirebaseRest firebase;
  private final String uid;

  ForumCommunityService(Context context) throws Exception {
    this.context = context.getApplicationContext();
    firebase = Enrollment.service(this.context);
    uid = firebase.uid();
  }

  String uid() { return uid; }

  boolean isOwner() throws Exception {
    if (firebase.flag("admins/" + uid)) return true;
    try {
      return uid.equals(firebase.string("forumDeveloperUid"));
    } catch (FirebaseRest.PermissionDenied ignored) {
      return false;
    }
  }

  String authorLabel(String author) throws Exception {
    String name = firebase.get("forumProfiles/" + author).optString("name", "Thành viên");
    return name + publicBadge(firebase, author);
  }

  static String publicBadge(FirebaseRest service, String author) {
    if (!ForumProtocol.validUid(author)) return "";
    try {
      JSONObject role = service.get("forumPublicRoles/" + author);
      if ("developer".equals(role.optString("badge"))) return " · Thẻ Nhà phát triển";
      if ("moderator".equals(role.optString("badge"))) return " · Thẻ Quản trị viên";
    } catch (Exception ignored) {}
    return "";
  }

  int topicRating(String topic) throws Exception {
    if (!ForumProtocol.validId(topic) || (!isModerator() && !isOwner())) {
      throw new SecurityException("Không thực hiện được.");
    }
    return firebase.get("forumAdminRatings/" + topic).optInt("stars", 0);
  }

  void rateTopic(String topic, int stars) throws Exception {
    if (!ForumProtocol.validId(topic)) throw new IllegalArgumentException("Chủ đề không hợp lệ.");
    long boost = ForumProtocol.ratingOffset(stars);
    if (!isModerator() && !isOwner()) throw new SecurityException("Không thực hiện được.");
    JSONObject entry = firebase.get("forumTopics/" + topic);
    if (entry.length() == 0) throw new IllegalStateException("Không tìm thấy chủ đề.");
    firebase.put("forumAdminRatings/" + topic,
        new JSONObject().put("stars", stars).put("by", uid)
            .put("updatedAt", FirebaseRest.timestamp()));
    firebase.patch("forumTopics/" + topic,
        new JSONObject().put("rankAt", entry.getLong("lastAt") + boost));
  }

  void bindInstallation() throws Exception {
    String request = DeviceLicenseManager.recoveryRequest(context);
    JSONObject current = firebase.get("forumIdentity/" + uid);
    if (current.length() == 0) {
      firebase.put("forumIdentity/" + uid,
          new JSONObject().put("request", request).put("fingerprint", request.substring(7)));
    } else if (!request.equals(current.optString("request"))) {
      throw new SecurityException("Danh tính diễn đàn không khớp thiết bị hiện tại.");
    }
  }

  String currentName() throws Exception {
    bindInstallation();
    JSONObject profile = firebase.get("forumProfiles/" + uid);
    String name = profile.optString("name");
    if (!name.isEmpty()) return name;
    JSONObject enrollment = firebase.get("requests/" + uid);
    String registered = enrollment.optString("name");
    if (!registered.isEmpty()) {
      String initial;
      try { initial = ForumProtocol.validDisplayName(registered); }
      catch (IllegalArgumentException invalid) { return ""; }
      firebase.put("forumProfiles/" + uid, new JSONObject().put("name", initial));
      return initial;
    }
    return "";
  }

  void changeName(String requestedName) throws Exception {
    boolean developer = isOwner();
    if (!developer) {
      try { developer = "developer".equals(
          firebase.get("forumPublicRoles/" + uid).optString("badge")); }
      catch (FirebaseRest.PermissionDenied ignored) {}
    }
    String name = developer ? ForumProtocol.text(requestedName, 2, 60, "Tên hiển thị")
        : ForumProtocol.validDisplayName(requestedName);
    bindInstallation();
    JSONObject existing = firebase.get("forumProfiles/" + uid);
    String old = existing.optString("name");
    if (name.equals(old)) return;
    JSONObject revised = new JSONObject().put("name", name);
    if (!old.isEmpty()) {
      String event = ForumProtocol.id();
      firebase.put("forumNameHistory/" + uid + "/" + event,
          new JSONObject().put("previous", old).put("changedAt", FirebaseRest.timestamp()));
      revised.put("changeEvent", event);
    }
    firebase.put("forumProfiles/" + uid, revised);
  }

  boolean hasRegistration() throws Exception {
    bindInstallation();
    String fingerprint = firebase.get("forumIdentity/" + uid).optString("fingerprint");
    if (!fingerprint.matches("[0-9a-f]{64}")) return false;
    String marker = "forumEnrollmentIndex/" + fingerprint;
    if (firebase.get("requests/" + uid).length() > 0) {
      if (!firebase.flag(marker)) firebase.putBoolean(marker, true);
      return true;
    }
    return firebase.flag(marker);
  }

  void ensureCanWrite() throws Exception {
    bindInstallation();
    if (firebase.flag("forumBans/" + uid)) throw new SecurityException("Không thể thực hiện thao tác này.");
  }

  private JSONObject usage(String kind, String contentId) throws Exception {
    if (hasRegistration()) return new JSONObject();
    JSONObject installation = firebase.get("forumIdentity/" + uid);
    String fingerprint = installation.optString("fingerprint");
    if (!fingerprint.matches("[0-9a-f]{64}")) throw new SecurityException("Không thực hiện được.");
    long server = firebase.serverTimeMillis();
    long day = ForumProtocol.vietnamDay(server);
    String path = "forumDailySlots/" + fingerprint + "/" + day + "/" + kind;
    for (int slot = 0; slot < 3; slot++) {
      String slotPath = path + "/" + slot;
      if (firebase.get(slotPath).length() != 0) continue;
      try {
        firebase.put(slotPath, new JSONObject()
            .put("id", contentId).put("day", day).put("uid", uid)
            .put("reservedAt", FirebaseRest.timestamp()));
        return new JSONObject().put("quotaDay", day).put("quotaSlot", slot);
      } catch (FirebaseRest.PermissionDenied denied) {
        if (slot == 2) throw new IllegalStateException(
            "Không thể đăng thêm nội dung lúc này.");
      }
    }
    throw new IllegalStateException("Không thể đăng thêm nội dung lúc này.");
  }

  String createTopic(String category, String title, String body, List<String> poll) throws Exception {
    if (!ForumProtocol.validCategory(category)) throw new IllegalArgumentException("Chuyên mục không hợp lệ.");
    String heading = ForumProtocol.text(title, 4, 140, "Tiêu đề");
    String content = ForumProtocol.text(body, 5, 5000, "Nội dung");
    if (currentName().isEmpty()) throw new IllegalStateException("Hãy đặt tên hiển thị trước khi đăng bài.");
    ensureCanWrite();
    String id = ForumProtocol.id();
    JSONObject payload = new JSONObject().put("uid", uid).put("category", category)
        .put("title", heading).put("body", content).put("locked", false)
        .put("createdAt", FirebaseRest.timestamp())
        .put("lastAt", FirebaseRest.timestamp())
        .put("rankAt", FirebaseRest.timestamp());
    if (poll != null && !poll.isEmpty()) {
      JSONArray options = ForumProtocol.pollOptions(poll);
      payload.put("pollOptions", options).put("pollClosed", false);
    }
    JSONObject quota = usage("topics", id);
    for (java.util.Iterator<String> keys = quota.keys(); keys.hasNext();) {
      String key = keys.next(); payload.put(key, quota.get(key));
    }
    firebase.put("forumTopics/" + id, payload);
    return id;
  }

  String reply(String topic, String parent, String mention, String content) throws Exception {
    if (!ForumProtocol.validId(topic)) throw new IllegalArgumentException("Chủ đề không hợp lệ.");
    if (!parent.isEmpty() && !ForumProtocol.validId(parent)) throw new IllegalArgumentException("Bình luận được trả lời không hợp lệ.");
    if (!mention.isEmpty() && !ForumProtocol.validUid(mention)) throw new IllegalArgumentException("Người được nhắc không hợp lệ.");
    String message = ForumProtocol.text(content, 1, 3000, "Bình luận");
    if (currentName().isEmpty()) throw new IllegalStateException("Hãy đặt tên hiển thị trước khi bình luận.");
    ensureCanWrite();
    String id = ForumProtocol.id();
    JSONObject entry = new JSONObject().put("uid", uid).put("text", message)
        .put("parent", parent).put("mention", mention).put("createdAt", FirebaseRest.timestamp());
    JSONObject quota = usage("posts", id);
    for (java.util.Iterator<String> keys = quota.keys(); keys.hasNext();) {
      String key = keys.next(); entry.put(key, quota.get(key));
    }
    firebase.put("forumPosts/" + topic + "/" + id, entry);
    try {
      JSONObject saved = firebase.get("forumPosts/" + topic + "/" + id);
      long postedAt = saved.optLong("createdAt");
      if (postedAt > 0) {
        JSONObject parentTopic = firebase.get("forumTopics/" + topic);
        long offset = parentTopic.optLong("rankAt", parentTopic.optLong("lastAt"))
            - parentTopic.optLong("lastAt");
        firebase.patch("forumTopics/" + topic,
            new JSONObject().put("lastAt", postedAt).put("lastPost", id)
                .put("rankAt", postedAt + offset));
      }
    } catch (Exception ignored) {}
    notifyReplies(topic, id, parent, mention);
    return id;
  }

  void setLiked(String topic, String post, boolean liked) throws Exception {
    if (!ForumProtocol.validId(topic) || (!post.isEmpty() && !ForumProtocol.validId(post))) {
      throw new IllegalArgumentException("Nội dung không hợp lệ.");
    }
    ensureCanWrite();
    String path = post.isEmpty() ? "forumTopicLikes/" + topic + "/" + uid
        : "forumPostLikes/" + topic + "/" + post + "/" + uid;
    if (liked) firebase.put(path, new JSONObject().put("likedAt", FirebaseRest.timestamp()));
    else firebase.deleteForumReaction(path);
  }

  boolean didLike(String topic, String post) throws Exception {
    String path = post.isEmpty() ? "forumTopicLikes/" + topic + "/" + uid
        : "forumPostLikes/" + topic + "/" + post + "/" + uid;
    return firebase.get(path).length() > 0;
  }

  void castVote(String topic, int choice) throws Exception {
    if (!ForumProtocol.validId(topic) || choice < 0 || choice > 5) {
      throw new IllegalArgumentException("Bình chọn không hợp lệ.");
    }
    ensureCanWrite();
    String fingerprint = firebase.get("forumIdentity/" + uid).optString("fingerprint");
    if (!fingerprint.matches("[0-9a-f]{64}")) throw new SecurityException("Không thực hiện được.");
    String guardPath = "forumPollGuards/" + topic + "/" + fingerprint;
    JSONObject guard = firebase.get(guardPath);
    if (guard.length() == 0) {
      firebase.put(guardPath, new JSONObject().put("uid", uid).put("choice", choice)
          .put("createdAt", FirebaseRest.timestamp()));
    } else if (!uid.equals(guard.optString("uid")) || choice != guard.optInt("choice", -1)) {
      throw new IllegalStateException("Đã bình chọn.");
    }
    firebase.put("forumPollVotes/" + topic + "/" + uid,
        new JSONObject().put("choice", choice).put("createdAt", FirebaseRest.timestamp()));
  }

  void report(String topic, String post) throws Exception {
    if (!ForumProtocol.validId(topic) || (!post.isEmpty() && !ForumProtocol.validId(post))) {
      throw new IllegalArgumentException("Nội dung không hợp lệ.");
    }
    bindInstallation();
    String id = ForumProtocol.id();
    firebase.put("forumReports/" + topic + "/" + id,
        new JSONObject().put("uid", uid).put("post", post)
            .put("createdAt", FirebaseRest.timestamp()));
    if (isModerator() || isOwner()) hide(topic, post);
  }

  void reportUser(String topic, String post, String target) throws Exception {
    if (!ForumProtocol.validId(topic) || (!post.isEmpty() && !ForumProtocol.validId(post))
        || !ForumProtocol.validUid(target) || uid.equals(target)) {
      throw new IllegalArgumentException("Tài khoản không hợp lệ.");
    }
    bindInstallation();
    JSONObject entry = firebase.get(post.isEmpty() ? "forumTopics/" + topic
        : "forumPosts/" + topic + "/" + post);
    if (!target.equals(entry.optString("uid"))) {
      throw new IllegalArgumentException("Không tìm thấy tài khoản.");
    }
    firebase.put("forumReports/" + topic + "/" + ForumProtocol.id(),
        new JSONObject().put("uid", uid).put("post", post)
            .put("targetUid", target).put("createdAt", FirebaseRest.timestamp()));
  }

  void blockUser(String target, boolean blocked) throws Exception {
    if (!isOwner() || !ForumProtocol.validUid(target) || uid.equals(target)) {
      throw new SecurityException("Không thực hiện được.");
    }
    String fingerprint = firebase.get("forumIdentity/" + target).optString("fingerprint");
    String own = firebase.get("forumIdentity/" + uid).optString("fingerprint");
    if (!fingerprint.matches("[0-9a-f]{64}") || fingerprint.equals(own)) {
      throw new IllegalStateException("Không tìm thấy thiết bị người dùng.");
    }
    JSONObject changes = new JSONObject()
        .put("forumBans/" + target, blocked ? true : JSONObject.NULL)
        .put("forumDeviceBans/" + fingerprint, blocked ? true : JSONObject.NULL);
    firebase.update(changes);
  }

  void updateOwnTopic(String topic, String newTitle, String newBody) throws Exception {
    if (!ForumProtocol.validId(topic)) throw new IllegalArgumentException("Chủ đề không hợp lệ.");
    ensureCanWrite();
    JSONObject previous = firebase.get("forumTopics/" + topic);
    if (!uid.equals(previous.optString("uid"))) throw new SecurityException("Không thực hiện được.");
    String event = archiveEdit(topic, "topic", previous);
    firebase.patch("forumTopics/" + topic,
        new JSONObject().put("title", ForumProtocol.text(newTitle, 4, 140, "Tiêu đề"))
            .put("body", ForumProtocol.text(newBody, 5, 5000, "Nội dung"))
            .put("editedAt", FirebaseRest.timestamp()).put("editEvent", event));
  }

  void updateOwnComment(String topic, String post, String newText) throws Exception {
    if (!ForumProtocol.validId(topic) || !ForumProtocol.validId(post)) {
      throw new IllegalArgumentException("Bình luận không hợp lệ.");
    }
    ensureCanWrite();
    JSONObject previous = firebase.get("forumPosts/" + topic + "/" + post);
    if (!uid.equals(previous.optString("uid"))) throw new SecurityException("Không thực hiện được.");
    String event = archiveEdit(topic, post, previous);
    firebase.patch("forumPosts/" + topic + "/" + post,
        new JSONObject().put("text", ForumProtocol.text(newText, 1, 3000, "Bình luận"))
            .put("editedAt", FirebaseRest.timestamp()).put("editEvent", event));
  }

  void softDelete(String topic, String post) throws Exception {
    if (!ForumProtocol.validId(topic) || (!post.isEmpty() && !ForumProtocol.validId(post))) {
      throw new IllegalArgumentException("Nội dung không hợp lệ.");
    }
    ensureCanWrite();
    String path = post.isEmpty() ? "forumTopics/" + topic : "forumPosts/" + topic + "/" + post;
    JSONObject record = firebase.get(path);
    if (!uid.equals(record.optString("uid"))) throw new SecurityException("Bạn chỉ được xóa bài của mình.");
    String recordId = post.isEmpty() ? topic : post;
    firebase.put("forumDeleteAudit/" + uid + "/" + recordId,
        new JSONObject().put("topic", topic).put("post", post)
            .put("content", record).put("deletedAt", FirebaseRest.timestamp()));
    firebase.deleteForumContent(path);
  }
  boolean isModerator() throws Exception {
    JSONObject permissions;
    try {
      permissions = firebase.get("forumModerators/" + uid);
    } catch (FirebaseRest.PermissionDenied denied) {
      return false;
    }
    if (!permissions.optBoolean("enabled")) return false;
    JSONObject decision = firebase.get("decisions/" + uid);
    return "approved".equals(decision.optString("state"))
        && permissions.optLong("decisionAt") == decision.optLong("decidedAt")
        && permissions.optString("policy").equals(decision.optString("policy"))
        && (permissions.optLong("expiresAt") == 0
            || permissions.optLong("expiresAt") > firebase.serverTimeMillis());
  }

  private String archiveEdit(String topic, String post, JSONObject previous) throws Exception {
    String event = ForumProtocol.id();
    firebase.put("forumEditAudit/" + topic + "/" + post + "/" + event,
        new JSONObject().put("content", previous).put("editedAt", FirebaseRest.timestamp()));
    return event;
  }

  private void notifyReplies(String topic, String post, String parent, String mention) {
    try {
      JSONObject entry = firebase.get("forumTopics/" + topic);
      String parentAuthor = "";
      if (!parent.isEmpty()) {
        parentAuthor = firebase.get("forumPosts/" + topic + "/" + parent).optString("uid");
        if (!parentAuthor.isEmpty() && !parentAuthor.equals(uid)) {
          deliverNotice(parentAuthor, topic, post,
              parentAuthor.equals(mention) ? "mention" : "reply");
        }
      }
      String origin = entry.optString("uid");
      if (!origin.isEmpty() && !origin.equals(uid) && parent.isEmpty()) {
        deliverNotice(origin, topic, post, "reply");
      }
      if (!mention.isEmpty() && !mention.equals(uid) && !mention.equals(parentAuthor)) {
        deliverNotice(mention, topic, post, "mention");
      }
    } catch (Exception ignored) {}
  }

  private void deliverNotice(String recipient, String topic, String post, String kind) throws Exception {
    if (!ForumProtocol.validUid(recipient)) return;
    firebase.put("forumNotices/" + recipient + "/" + post + "_" + kind,
        new JSONObject().put("topic", topic).put("post", post).put("by", uid)
            .put("kind", kind).put("createdAt", FirebaseRest.timestamp()));
  }

  void closePoll(String topic) throws Exception {
    if (!ForumProtocol.validId(topic)) throw new IllegalArgumentException("Chủ đề không hợp lệ.");
    firebase.putBoolean("forumTopics/" + topic + "/pollClosed", true);
  }

  void changeLock(String topic, boolean locked) throws Exception {
    if (!ForumProtocol.validId(topic)) throw new IllegalArgumentException("Chủ đề không hợp lệ.");
    ensureCanWrite();
    if (isModerator() || isOwner()) {
      firebase.patch("forumTopics/" + topic,
          new JSONObject().put("locked", locked).put("moderationLocked", locked));
    } else {
      firebase.putBoolean("forumTopics/" + topic + "/locked", locked);
    }
    moderationLog(topic, locked ? "lock" : "unlock");
  }

  void setPinned(String topic, String post, boolean pinned) throws Exception {
    if (!ForumProtocol.validId(topic) || (!post.isEmpty() && !ForumProtocol.validId(post))) {
      throw new IllegalArgumentException("Nội dung không hợp lệ.");
    }
    String path = post.isEmpty() ? "forumPinnedTopics/" + topic : "forumPinnedPosts/" + topic + "/" + post;
    if (pinned) firebase.putBoolean(path, true);
    else firebase.deleteForumReaction(path);
    moderationLog(topic, pinned ? "pin" : "unpin");
  }

  void hide(String topic, String post) throws Exception {
    if (!ForumProtocol.validId(topic) || (!post.isEmpty() && !ForumProtocol.validId(post))) {
      throw new IllegalArgumentException("Nội dung không hợp lệ.");
    }
    String path = post.isEmpty() ? "forumTopics/" + topic : "forumPosts/" + topic + "/" + post;
    JSONObject record = firebase.get(path);
    if (record.length() == 0) return;
    record.remove("name");
    record.remove("authorName");
    String archive = "forumModerationArchive/" + topic + "/" + (post.isEmpty() ? "topic" : post);
    firebase.put(archive, new JSONObject().put("content", record)
        .put("by", uid).put("hiddenAt", FirebaseRest.timestamp()));
    firebase.deleteForumContent(path);
    moderationLog(topic, post.isEmpty() ? "hide_topic" : "hide_post");
  }

  private void moderationLog(String topic, String action) {
    try {
      firebase.put("forumModerationLog/" + topic + "/" + ForumProtocol.id(),
          new JSONObject().put("by", uid).put("action", action)
              .put("createdAt", FirebaseRest.timestamp()));
    } catch (Exception ignored) {}
  }

}
