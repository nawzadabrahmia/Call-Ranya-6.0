package android.database;
public interface Cursor extends java.io.Closeable {
  boolean moveToFirst(); boolean moveToNext(); String getString(int i); int getInt(int i); long getLong(int i);
  double getDouble(int i); boolean isNull(int i); int getColumnIndex(String n); void close();
}
