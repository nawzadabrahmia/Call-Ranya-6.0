package android.content;
import java.io.File;
public class Context {
  public static final String NOTIFICATION_SERVICE = "notification";
  public static File FILES = new File("/tmp");
  public Context getApplicationContext() { return this; }
  public File getFilesDir() { return FILES; }
  public Object getSystemService(String n) { return null; }
}
