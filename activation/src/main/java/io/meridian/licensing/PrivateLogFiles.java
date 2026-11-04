package io.meridian.licensing;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class PrivateLogFiles {
  private static final int MAGIC = 0x53464c32;
  private static final int BLOCK = 64 * 1024;
  public static final int PAGE_CHARS = 12_000;
  private final File directory;
  private final SecretKey key;
  public interface Writer { void write(OutputStream destination) throws Exception; }

  public PrivateLogFiles(File directory, SecretKey key) { this.directory = directory; this.key = key; }

  private File file(String id) {
    if (id == null || !id.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Tệp nhật ký không hợp lệ.");
    return new File(directory, id + ".bin");
  }

  public boolean exists(String id) { return id != null && id.matches("[0-9a-f]{32}") && file(id).isFile(); }
  public void delete(String id) { if (exists(id)) file(id).delete(); }

  public void prune(java.util.Set<String> retained, long before) {
    File[] items = directory.listFiles();
    if (items == null) return;
    for (File item : items) {
      String name = item.getName();
      if (name.matches("[0-9a-f]{32}\\.(?:bin|part)") && item.lastModified() < before
          && !retained.contains(name.substring(0, 32))) item.delete();
    }
  }

  public String write(String text) throws Exception {
    return write(destination -> {
      OutputStreamWriter writer = new OutputStreamWriter(destination, StandardCharsets.UTF_8);
      writer.write(text); writer.flush();
    });
  }

  public String write(Writer writer) throws Exception {
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Không tạo được nơi lưu nhật ký trên máy.");
    String id = UUID.randomUUID().toString().replace("-", "");
    File target = file(id), temporary = new File(directory, id + ".part");
    boolean complete = false;
    try {
      try (DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temporary)))) {
        stream.writeInt(MAGIC);
        OutputStream encrypted = new OutputStream() {
          final byte[] buffer = new byte[BLOCK];
          int used, total, index;
          @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }
          @Override public void write(byte[] bytes, int offset, int count) throws IOException {
            if (count > SupportAttachment.MAX_BYTES - total) throw SupportAttachment.tooLarge();
            total += count;
            while (count > 0) {
              int size = Math.min(count, buffer.length - used);
              System.arraycopy(bytes, offset, buffer, used, size); used += size; offset += size; count -= size;
              if (used == buffer.length) flushBlock();
            }
          }
          void flushBlock() throws IOException {
            if (used == 0) return;
            try {
              Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key);
              cipher.updateAAD(("meridian.support.log:" + id + ":" + index++).getBytes(StandardCharsets.UTF_8));
              byte[] bytes = cipher.doFinal(buffer, 0, used);
              stream.writeInt(used); stream.write(cipher.getIV()); stream.write(bytes); used = 0;
            } catch (Exception e) { throw new IOException("Không mã hóa được nhật ký trên máy.", e); }
          }
          @Override public void close() throws IOException { flushBlock(); stream.writeInt(0); }
        };
        try (OutputStream output = encrypted) { writer.write(output); }
      }
      if (!temporary.renameTo(target)) throw new IOException("Không lưu được tệp nhật ký trên máy.");
      complete = true; return id;
    } finally { if (!complete) { temporary.delete(); target.delete(); } }
  }

  public SupportAttachment.Source source(String id) {
    File target = file(id);
    return () -> {
      DataInputStream stream = new DataInputStream(new BufferedInputStream(new FileInputStream(target)));
      try {
        if (stream.readInt() != MAGIC) throw new IOException("Định dạng nhật ký trên máy không hợp lệ.");
      } catch (Exception e) { stream.close(); throw e; }
      return new InputStream() {
        byte[] buffer = new byte[0]; int position, total, index; boolean ended;
        private boolean next() throws IOException {
          if (position < buffer.length) return true;
          if (ended) return false;
          int size = stream.readInt();
          if (size == 0) {
            if (stream.read() != -1) throw new IOException("Nhật ký trên máy có dữ liệu thừa.");
            ended = true; return false;
          }
          if (size < 1 || size > BLOCK || size > SupportAttachment.MAX_BYTES - total) throw new IOException("Kích thước nhật ký trên máy không hợp lệ.");
          byte[] iv = new byte[12], encrypted = new byte[size + 16]; stream.readFully(iv); stream.readFully(encrypted);
          try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(("meridian.support.log:" + id + ":" + index++).getBytes(StandardCharsets.UTF_8));
            buffer = cipher.doFinal(encrypted); position = 0; total += size; return true;
          } catch (Exception e) { throw new IOException("Nhật ký trên máy bị hỏng hoặc đã thay đổi.", e); }
        }
        @Override public int read() throws IOException { return next() ? buffer[position++] & 255 : -1; }
        @Override public int read(byte[] bytes, int offset, int count) throws IOException {
          if (count == 0) return 0;
          if (!next()) return -1;
          int size = Math.min(count, buffer.length - position);
          System.arraycopy(buffer, position, bytes, offset, size); position += size; return size;
        }
        @Override public void close() throws IOException { stream.close(); }
      };
    };
  }

  public String page(String id, int number) throws Exception {
    if (number < 0 || number > SupportAttachment.MAX_BYTES / PAGE_CHARS + 1) throw new IllegalArgumentException("Trang nhật ký không hợp lệ.");
    try (InputStreamReader reader = new InputStreamReader(source(id).open(), StandardCharsets.UTF_8)) {
      long left = (long) number * PAGE_CHARS;
      while (left > 0) {
        long skipped = reader.skip(left);
        if (skipped == 0) return "";
        left -= skipped;
      }
      char[] chars = new char[PAGE_CHARS + 1]; int length = 0, count;
      while (length < chars.length && (count = reader.read(chars, length, chars.length - length)) != -1) length += count;
      return new String(chars, 0, length);
    }
  }
}
