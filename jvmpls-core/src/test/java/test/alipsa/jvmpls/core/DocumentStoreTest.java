package test.alipsa.jvmpls.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.core.DocumentStore;

class DocumentStoreTest {

  @Test
  void toOffsetLineZeroCharZero() {
    assertEquals(0, DocumentStore.toOffset("hello\nworld", 0, 0));
  }

  @Test
  void toOffsetSecondLine() {
    assertEquals(6, DocumentStore.toOffset("hello\nworld", 1, 0));
  }

  @Test
  void toOffsetWithCharOffset() {
    assertEquals(8, DocumentStore.toOffset("hello\nworld", 1, 2));
  }

  @Test
  void toOffsetHandlesCrLf() {
    String text = "line0\r\nline1\r\nline2";
    assertEquals(0, DocumentStore.toOffset(text, 0, 0));
    assertEquals(7, DocumentStore.toOffset(text, 1, 0));
    assertEquals(14, DocumentStore.toOffset(text, 2, 0));
  }

  @Test
  void toOffsetHandlesCr() {
    String text = "line0\rline1\rline2";
    assertEquals(6, DocumentStore.toOffset(text, 1, 0));
    assertEquals(12, DocumentStore.toOffset(text, 2, 0));
  }

  @Test
  void toOffsetClampsToLength() {
    String text = "ab";
    assertEquals(2, DocumentStore.toOffset(text, 0, 5));
  }

  @Test
  void applyEditReplaceRange() {
    DocumentStore store = new DocumentStore();
    store.put("file:///a.java", "hello world");

    String result = store.applyEdit("file:///a.java", 0, 6, 0, 11, "there");
    assertEquals("hello there", result);
    assertEquals("hello there", store.get("file:///a.java"));
  }

  @Test
  void applyEditInsertion() {
    DocumentStore store = new DocumentStore();
    store.put("file:///a.java", "helloworld");

    // Insert " " between "hello" and "world"
    String result = store.applyEdit("file:///a.java", 0, 5, 0, 5, " ");
    assertEquals("hello world", result);
  }

  @Test
  void applyEditDeletion() {
    DocumentStore store = new DocumentStore();
    store.put("file:///a.java", "hello world");

    // Delete "hello "
    String result = store.applyEdit("file:///a.java", 0, 0, 0, 6, "");
    assertEquals("world", result);
  }

  @Test
  void applyEditMultiline() {
    DocumentStore store = new DocumentStore();
    store.put("file:///a.java", "line0\nline1\nline2");

    // Replace "line1" with "replaced"
    String result = store.applyEdit("file:///a.java", 1, 0, 1, 5, "replaced");
    assertEquals("line0\nreplaced\nline2", result);
  }

  @Test
  void applyEditThrowsForMissingUri() {
    DocumentStore store = new DocumentStore();
    assertThrows(
        IllegalArgumentException.class,
        () -> store.applyEdit("file:///missing.java", 0, 0, 0, 0, "text"));
  }
}
